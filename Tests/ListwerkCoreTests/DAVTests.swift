import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import ListwerkCore

final class MockTransport: HTTPTransport, @unchecked Sendable {
    typealias Reply = (status: Int, headers: [String: String], body: String)
    private let lock = NSLock()
    private(set) var requests: [URLRequest] = []
    var handler: (URLRequest) -> Reply

    init(_ handler: @escaping (URLRequest) -> Reply) { self.handler = handler }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lock.lock(); requests.append(request); lock.unlock()
        let r = handler(request)
        let resp = HTTPURLResponse(url: request.url!, statusCode: r.status, httpVersion: "HTTP/1.1", headerFields: r.headers)!
        return (Data(r.body.utf8), resp)
    }

    var last: URLRequest { requests.last! }
    func body(_ i: Int = -1) -> String {
        let r = i < 0 ? requests[requests.count + i] : requests[i]
        return r.httpBody.map { String(decoding: $0, as: UTF8.self) } ?? ""
    }
}

private func ok(_ body: String, status: Int = 207, headers: [String: String] = [:]) -> MockTransport.Reply { (status, headers, body) }

final class DAVTests: XCTestCase {
    private let server = URL(string: "https://cloud.example.org")!
    private func client(_ t: MockTransport, base: URL? = nil) -> CalDAVClient {
        CalDAVClient(serverURL: base ?? server, loginName: "nutzer", appPassword: "geheim-app-pw", transport: t)
    }

    // Nachgebaut nach dem Antwortformat von Nextcloud (sabre/dav); gegen eine echte Instanz noch nicht geprüft.
    private let collectionsXML = """
    <?xml version="1.0"?>
    <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav"
      xmlns:cs="http://calendarserver.org/ns/" xmlns:oc="http://owncloud.org/ns" xmlns:x1="http://apple.com/ns/ical/">
     <d:response><d:href>/remote.php/dav/calendars/nutzer/</d:href>
      <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Arbeit</d:displayname>
        <x1:calendar-color>#1E66F5FF</x1:calendar-color><x1:calendar-order>3</x1:calendar-order>
        <cs:getctag>http://sabre.io/ns/sync/42</cs:getctag><d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>
        <cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set>
        <d:current-user-privilege-set><d:privilege><d:all/></d:privilege></d:current-user-privilege-set>
        <d:owner><d:href>/remote.php/dav/principals/users/nutzer/</d:href></d:owner>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/persoenlich/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Termine</d:displayname>
        <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/team%20a_shared_by_anna/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Team A</d:displayname>
        <cal:supported-calendar-component-set><cal:comp name="VTODO"/><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
        <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>
        <d:owner><d:href>/remote.php/dav/principals/users/anna/</d:href></d:owner>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/trashbin/</d:href>
      <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
    </d:multistatus>
    """

    func testListCollectionsFiltersAndReadsProperties() async throws {
        let t = MockTransport { _ in ok(self.collectionsXML) }
        let lists = try await client(t).listCollections(home: "/remote.php/dav/calendars/nutzer/")
        XCTAssertEqual(lists.map(\.name), ["Arbeit", "Team A"], "nur Listen mit VTODO")
        let s = lists[0]
        XCTAssertEqual(s.href, "/remote.php/dav/calendars/nutzer/arbeit/")
        XCTAssertEqual(s.color, "#1e66f5")
        XCTAssertEqual(s.order, 3)
        XCTAssertEqual(s.syncToken, "http://sabre.io/ns/sync/42")
        XCTAssertTrue(s.canWrite)
        XCTAssertFalse(s.isShared)
        XCTAssertFalse(lists[1].canWrite, "nur lesen")
        XCTAssertTrue(lists[1].isShared)
        XCTAssertEqual(lists[1].ownerName, "anna")
        XCTAssertEqual(t.last.httpMethod, "PROPFIND")
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Depth"), "1")
    }

    func testAuthHeaderAndUserAgent() async throws {
        let t = MockTransport { _ in ok(self.collectionsXML) }
        _ = try await client(t).listCollections(home: "/remote.php/dav/calendars/nutzer/")
        let expected = "Basic " + Data("nutzer:geheim-app-pw".utf8).base64EncodedString()
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Authorization"), expected)
        XCTAssertTrue(t.last.value(forHTTPHeaderField: "User-Agent")!.hasPrefix("Listwerk/"))
    }

    func testDiscover() async throws {
        let t = MockTransport { req in
            if req.url!.absoluteString.hasSuffix("/remote.php/dav/") {
                return ok("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/nutzer/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            }
            return ok("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/remote.php/dav/principals/users/nutzer/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>https://cloud.example.org/remote.php/dav/calendars/nutzer/</d:href></c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
        }
        let r = try await client(t).discover()
        XCTAssertEqual(r.principal, "/remote.php/dav/principals/users/nutzer/")
        XCTAssertEqual(r.home, "/remote.php/dav/calendars/nutzer/", "absolute URL wird auf den Pfad gekürzt")
    }

    func testSubdirectoryInstallation() async throws {
        let t = MockTransport { _ in ok("<d:multistatus xmlns:d=\"DAV:\"/>") }
        _ = try? await client(t, base: URL(string: "https://example.org/nextcloud")!).discover()
        XCTAssertEqual(t.requests[0].url?.absoluteString, "https://example.org/nextcloud/remote.php/dav/")
    }

    func testSyncCollectionParsesChangedAndRemoved() async throws {
        let xml = """
        <?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href><d:propstat><d:prop><d:getetag>"aaa"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/b.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
        <d:sync-token>http://sabre.io/ns/sync/43</d:sync-token></d:multistatus>
        """
        let t = MockTransport { _ in ok(xml) }
        let ch = try await client(t).changes(list: "/remote.php/dav/calendars/nutzer/arbeit/", since: "http://sabre.io/ns/sync/42")
        XCTAssertEqual(ch.changed, [RemoteEntry(href: "/remote.php/dav/calendars/nutzer/arbeit/a.ics", etag: "\"aaa\"")])
        XCTAssertEqual(ch.removed, ["/remote.php/dav/calendars/nutzer/arbeit/b.ics"])
        XCTAssertEqual(ch.newToken, "http://sabre.io/ns/sync/43")
        XCTAssertTrue(t.body().contains("<d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>"))
        XCTAssertEqual(t.last.httpMethod, "REPORT")
    }

    func testSyncCollectionInvalidToken() async throws {
        let t = MockTransport { _ in (403, [:], "<d:error xmlns:d=\"DAV:\"><d:valid-sync-token/></d:error>") }
        let ch = try await client(t).changes(list: "/x/", since: "alt")
        XCTAssertTrue(ch.tokenInvalid)
    }

    func testFetchParsesCalendarDataWithEntities() async throws {
        let xml = """
        <?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag>
        <cal:calendar-data>BEGIN:VCALENDAR&#13;
        VERSION:2.0&#13;
        BEGIN:VTODO&#13;
        UID:a&#13;
        SUMMARY:Fragen &amp; Antworten&#13;
        END:VTODO&#13;
        END:VCALENDAR&#13;
        </cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """
        let t = MockTransport { _ in ok(xml) }
        let res = try await client(t).fetch(list: "/remote.php/dav/calendars/nutzer/arbeit/", hrefs: ["/remote.php/dav/calendars/nutzer/arbeit/a.ics"])
        XCTAssertEqual(res.count, 1)
        XCTAssertEqual(res[0].etag, "\"e1\"")
        let item = try XCTUnwrap(ICalDocument(parsing: res[0].ics)).makeItem()
        XCTAssertEqual(item.uid, "a")
        XCTAssertEqual(item.title, "Fragen & Antworten")
        XCTAssertTrue(t.body().contains("<d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href>"))
    }

    func testFetchBatchesRequests() async throws {
        let t = MockTransport { _ in ok("<d:multistatus xmlns:d=\"DAV:\"/>") }
        _ = try await client(t).fetch(list: "/l/", hrefs: (0..<120).map { "/l/\($0).ics" })
        XCTAssertEqual(t.requests.count, 3, "50 + 50 + 20")
    }

    func testPutHeadersAndResults() async throws {
        let t = MockTransport { req in
            if req.value(forHTTPHeaderField: "If-Match") == "\"alt\"" { return (412, [:], "") }
            return (201, ["ETag": "\"neu\""], "")
        }
        let c = client(t)
        let created = try await c.put(href: "/l/a.ics", ics: "X", etag: nil, create: true)
        XCTAssertEqual(created, .ok(etag: "\"neu\""))
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "If-None-Match"), "*")
        XCTAssertNil(t.last.value(forHTTPHeaderField: "If-Match"))
        XCTAssertTrue(t.last.value(forHTTPHeaderField: "Content-Type")!.hasPrefix("text/calendar"))

        let conflict = try await c.put(href: "/l/a.ics", ics: "X", etag: "\"alt\"", create: false)
        XCTAssertEqual(conflict, .preconditionFailed)
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "If-Match"), "\"alt\"")
    }

    func testErrorsMapToDAVError() async throws {
        let c401 = client(MockTransport { _ in (401, [:], "") })
        do { _ = try await c401.listAll(list: "/l/"); XCTFail() } catch { XCTAssertEqual(error as? DAVError, .unauthorized) }
        let c507 = client(MockTransport { _ in (507, [:], "Speicher voll") })
        do { _ = try await c507.put(href: "/l/a.ics", ics: "X", etag: nil, create: true); XCTFail() } catch {
            guard case DAVError.http(let status, let detail) = error else { return XCTFail("\(error)") }
            XCTAssertEqual(status, 507)
            XCTAssertEqual(detail, "Speicher voll")
        }
    }

    func testDeleteAndMove() async throws {
        let t = MockTransport { req in
            switch req.httpMethod {
            case "DELETE": return (req.value(forHTTPHeaderField: "If-Match") == "\"x\"" ? 204 : 412, [:], "")
            default: return (201, [:], "")
            }
        }
        let c = client(t)
        let d1 = try await c.delete(href: "/l/a.ics", etag: "\"x\"")
        let d2 = try await c.delete(href: "/l/a.ics", etag: "\"y\"")
        XCTAssertEqual(d1, .ok)
        XCTAssertEqual(d2, .preconditionFailed)
        let m = try await c.move(from: "/l/a.ics", to: "/m/a.ics")
        XCTAssertEqual(m, .ok)
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Destination"), "https://cloud.example.org/m/a.ics")
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Overwrite"), "F")
    }

    func testMakeAndUpdateCollectionEscapeNames() async throws {
        let t = MockTransport { _ in (201, [:], "") }
        try await client(t).makeCollection(href: "/remote.php/dav/calendars/nutzer/x/", name: "Eltern & <Kinder>", color: "#ff0000")
        XCTAssertEqual(t.last.httpMethod, "MKCALENDAR")
        XCTAssertTrue(t.body().contains("Eltern &amp; &lt;Kinder&gt;"))
        XCTAssertTrue(t.body().contains("<c:comp name=\"VTODO\"/>"))
    }

    // MARK: Login Flow v2

    func testLoginFlowV2() async throws {
        var polls = 0
        let t = MockTransport { req in
            if req.url!.path.hasSuffix("/login/v2") {
                return (200, [:], #"{"poll":{"token":"tok123","endpoint":"https://cloud.example.org/index.php/login/v2/poll"},"login":"https://cloud.example.org/index.php/login/v2/flow/abc"}"#)
            }
            polls += 1
            if polls < 3 { return (404, [:], "") }
            return (200, [:], #"{"server":"https://cloud.example.org","loginName":"nutzer","appPassword":"app-pw-xyz"}"#)
        }
        let flow = LoginFlowV2(transport: t)
        let session = try await flow.start(server: URL(string: "https://cloud.example.org/")!)
        XCTAssertEqual(session.loginURL.absoluteString, "https://cloud.example.org/index.php/login/v2/flow/abc")
        XCTAssertEqual(t.requests[0].url?.absoluteString, "https://cloud.example.org/index.php/login/v2")
        let creds = try await flow.waitForCredentials(session, interval: 0.01, timeout: 5)
        XCTAssertEqual(creds, NextcloudCredentials(serverURL: URL(string: "https://cloud.example.org")!, loginName: "nutzer", appPassword: "app-pw-xyz"))
        XCTAssertEqual(polls, 3)
        XCTAssertEqual(t.body(), "token=tok123")
    }
}

