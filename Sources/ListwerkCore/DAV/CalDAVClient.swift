import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let cfg = URLSessionConfiguration.ephemeral
            cfg.timeoutIntervalForRequest = 30
            cfg.httpShouldSetCookies = false
            cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: cfg)
        }
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { cont in
            let task = session.dataTask(with: request) { data, response, error in
                if let error {
                    cont.resume(throwing: DAVError.network(error.localizedDescription))
                } else if let http = response as? HTTPURLResponse {
                    cont.resume(returning: (data ?? Data(), http))
                } else {
                    cont.resume(throwing: DAVError.invalidResponse("Keine HTTP-Antwort"))
                }
            }
            task.resume()
        }
    }
}

/// CalDAV-Client für Nextcloud (sabre/dav). Spricht ausschließlich HTTPS mit Basic-Anmeldung per App-Passwort.
public struct CalDAVClient: CalDAVService {
    public let serverURL: URL
    private let loginName: String
    private let appPassword: String
    private let transport: HTTPTransport
    private let userAgent = "Listwerk/\(ListwerkCoreInfo.version)"

    public init(serverURL: URL, loginName: String, appPassword: String, transport: HTTPTransport = URLSessionTransport()) {
        self.serverURL = serverURL
        self.loginName = loginName
        self.appPassword = appPassword
        self.transport = transport
    }

    /// Pfad zur DAV-Wurzel, auch wenn Nextcloud in einem Unterverzeichnis läuft.
    var davRoot: String {
        var p = serverURL.path
        if !p.hasSuffix("/") { p += "/" }
        return p + "remote.php/dav/"
    }

    // MARK: HTTP

    private func url(for path: String) throws -> URL {
        guard var c = URLComponents(url: serverURL, resolvingAgainstBaseURL: false) else {
            throw DAVError.invalidResponse("Ungültige Server-Adresse")
        }
        c.percentEncodedPath = path
        c.query = nil
        guard let u = c.url else { throw DAVError.invalidResponse("Ungültiger Pfad: \(path)") }
        return u
    }

    private func request(_ method: String, _ path: String, headers: [String: String] = [:], body: String? = nil) throws -> URLRequest {
        var req = URLRequest(url: try url(for: path))
        req.httpMethod = method
        let token = Data("\(loginName):\(appPassword)".utf8).base64EncodedString()
        req.setValue("Basic \(token)", forHTTPHeaderField: "Authorization")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body {
            req.httpBody = Data(body.utf8)
            if headers["Content-Type"] == nil { req.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type") }
        }
        return req
    }

    private func send(_ req: URLRequest, accept: Set<Int>) async throws -> (Data, HTTPURLResponse) {
        let (data, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        guard accept.contains(resp.statusCode) else {
            throw DAVError.http(status: resp.statusCode, detail: String(decoding: data.prefix(300), as: UTF8.self))
        }
        return (data, resp)
    }

    private func multistatus(_ method: String, _ path: String, depth: String, body: String, accept: Set<Int> = [207]) async throws -> Multistatus {
        let req = try request(method, path, headers: ["Depth": depth], body: body)
        let (data, _) = try await send(req, accept: accept)
        return try Multistatus.parse(data)
    }

    private func header(_ resp: HTTPURLResponse, _ name: String) -> String? {
        for (k, v) in resp.allHeaderFields where "\(k)".caseInsensitiveCompare(name) == .orderedSame { return "\(v)" }
        return nil
    }

    // MARK: Entdeckung

    public func discover() async throws -> (principal: String, home: String) {
        let first = try await multistatus("PROPFIND", davRoot, depth: "0", body: """
        <?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>
        """)
        guard let principal = first.responses.compactMap({ $0.prop(NS.dav, "current-user-principal")?.child(NS.dav, "href")?.trimmedText }).first
        else { throw DAVError.invalidResponse("current-user-principal fehlt") }
        let principalPath = Multistatus.normalizeHref(principal)

        let second = try await multistatus("PROPFIND", principalPath, depth: "0", body: """
        <?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>
        """)
        guard let home = second.responses.compactMap({ $0.prop(NS.caldav, "calendar-home-set")?.child(NS.dav, "href")?.trimmedText }).first
        else { throw DAVError.invalidResponse("calendar-home-set fehlt") }
        return (principalPath, Multistatus.normalizeHref(home))
    }

    public func listCollections(home: String) async throws -> [RemoteList] {
        let ms = try await multistatus("PROPFIND", home, depth: "1", body: """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
          <d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><a:calendar-order/><cs:getctag/><d:sync-token/>
            <c:supported-calendar-component-set/><d:current-user-privilege-set/><d:owner/></d:prop>
        </d:propfind>
        """)
        var lists: [RemoteList] = []
        for r in ms.responses {
            guard r.prop(NS.dav, "resourcetype")?.child(NS.caldav, "calendar") != nil else { continue }
            let comps = r.prop(NS.caldav, "supported-calendar-component-set")?.children(NS.caldav, "comp").compactMap { $0.attributes["name"]?.uppercased() } ?? []
            guard comps.contains("VTODO") else { continue }
            let privileges = Set(r.prop(NS.dav, "current-user-privilege-set")?.children(NS.dav, "privilege").flatMap { $0.children.map(\.name) } ?? [])
            let canWrite = privileges.isEmpty || privileges.contains("all") || privileges.contains("write") || privileges.contains("write-content")
            let owner = r.prop(NS.dav, "owner")?.child(NS.dav, "href")?.trimmedText
            let name = r.prop(NS.dav, "displayname")?.trimmedText
            let href = r.href.hasSuffix("/") ? r.href : r.href + "/"
            lists.append(RemoteList(
                href: href,
                name: (name?.isEmpty == false ? name! : href.split(separator: "/").last.map(String.init) ?? "Liste"),
                color: r.prop(NS.apple, "calendar-color").flatMap { Self.normalizeColor($0.trimmedText) },
                order: r.prop(NS.apple, "calendar-order").flatMap { Double($0.trimmedText) },
                ctag: r.prop(NS.cs, "getctag")?.trimmedText,
                syncToken: r.prop(NS.dav, "sync-token")?.trimmedText,
                canWrite: canWrite,
                isShared: owner.map { !home.hasPrefix($0) && !$0.isEmpty && !Self.ownerMatchesHome($0, home: home) } ?? false,
                ownerName: owner.flatMap { $0.split(separator: "/").last.map(String.init) }
            ))
        }
        return lists
    }

    /// Eigene Listen: Der Principal-Name (`/principals/users/nutzer/`) steckt auch im Home-Pfad (`/calendars/nutzer/`).
    private static func ownerMatchesHome(_ owner: String, home: String) -> Bool {
        guard let name = owner.split(separator: "/").last else { return true }
        return home.split(separator: "/").contains(name)
    }

    /// `#1E66F5FF` → `#1e66f5`; unbekannte Formen werden verworfen.
    static func normalizeColor(_ s: String) -> String? {
        var v = s.trimmingCharacters(in: .whitespaces)
        guard v.hasPrefix("#") else { return nil }
        v.removeFirst()
        if v.count == 8 { v = String(v.prefix(6)) }
        guard v.count == 6, Int(v, radix: 16) != nil else { return nil }
        return "#" + v.lowercased()
    }

    public func collectionState(_ list: String) async throws -> (ctag: String?, syncToken: String?) {
        let ms = try await multistatus("PROPFIND", list, depth: "0", body: """
        <?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
        <d:prop><cs:getctag/><d:sync-token/></d:prop></d:propfind>
        """)
        let r = ms.responses.first
        return (r?.prop(NS.cs, "getctag")?.trimmedText, r?.prop(NS.dav, "sync-token")?.trimmedText)
    }

    // MARK: Änderungen holen

    public func changes(list: String, since token: String?) async throws -> RemoteChanges {
        let body = """
        <?xml version="1.0" encoding="utf-8"?><d:sync-collection xmlns:d="DAV:">
        <d:sync-token>\(XMLText.escape(token ?? ""))</d:sync-token><d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>
        """
        let req = try request("REPORT", list, headers: ["Depth": "0"], body: body)
        let (data, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        if [403, 409, 412].contains(resp.statusCode), String(decoding: data, as: UTF8.self).contains("valid-sync-token") {
            return RemoteChanges(tokenInvalid: true)
        }
        guard resp.statusCode == 207 else {
            throw DAVError.http(status: resp.statusCode, detail: String(decoding: data.prefix(300), as: UTF8.self))
        }
        let ms = try Multistatus.parse(data)
        var out = RemoteChanges(newToken: ms.syncToken)
        for r in ms.responses where r.href.lowercased().hasSuffix(".ics") {
            if r.status == 404 { out.removed.append(r.href) }
            else { out.changed.append(RemoteEntry(href: r.href, etag: r.prop(NS.dav, "getetag")?.trimmedText)) }
        }
        return out
    }

    public func listAll(list: String) async throws -> [RemoteEntry] {
        let ms = try await multistatus("PROPFIND", list, depth: "1", body: """
        <?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>
        """)
        return ms.responses.filter { $0.href.lowercased().hasSuffix(".ics") }
            .map { RemoteEntry(href: $0.href, etag: $0.prop(NS.dav, "getetag")?.trimmedText) }
    }

    public func fetch(list: String, hrefs: [String]) async throws -> [RemoteResource] {
        var out: [RemoteResource] = []
        for chunk in stride(from: 0, to: hrefs.count, by: 50).map({ Array(hrefs[$0..<min($0 + 50, hrefs.count)]) }) {
            let items = chunk.map { "<d:href>\(XMLText.escape($0))</d:href>" }.joined()
            let ms = try await multistatus("REPORT", list, depth: "1", body: """
            <?xml version="1.0" encoding="utf-8"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
            <d:prop><d:getetag/><c:calendar-data/></d:prop>\(items)</c:calendar-multiget>
            """)
            for r in ms.responses {
                guard let ics = r.prop(NS.caldav, "calendar-data")?.text, !ics.isEmpty else { continue }
                out.append(RemoteResource(href: r.href, etag: r.prop(NS.dav, "getetag")?.trimmedText, ics: ics))
            }
        }
        return out
    }

    // MARK: Schreiben

    public func put(href: String, ics: String, etag: String?, create: Bool) async throws -> PutResult {
        var headers = ["Content-Type": "text/calendar; charset=utf-8"]
        if create { headers["If-None-Match"] = "*" } else if let etag { headers["If-Match"] = etag }
        let req = try request("PUT", href, headers: headers, body: ics)
        let (data, resp) = try await transport.send(req)
        switch resp.statusCode {
        case 200, 201, 204: return .ok(etag: header(resp, "ETag"))
        case 401: throw DAVError.unauthorized
        case 412: return .preconditionFailed
        default: throw DAVError.http(status: resp.statusCode, detail: String(decoding: data.prefix(300), as: UTF8.self))
        }
    }

    public func delete(href: String, etag: String?) async throws -> DeleteResult {
        let req = try request("DELETE", href, headers: etag.map { ["If-Match": $0] } ?? [:])
        let (data, resp) = try await transport.send(req)
        switch resp.statusCode {
        case 200, 202, 204: return .ok
        case 401: throw DAVError.unauthorized
        case 404, 410: return .notFound
        case 412: return .preconditionFailed
        default: throw DAVError.http(status: resp.statusCode, detail: String(decoding: data.prefix(300), as: UTF8.self))
        }
    }

    public func move(from: String, to: String) async throws -> MoveResult {
        let dest = try url(for: to).absoluteString
        let req = try request("MOVE", from, headers: ["Destination": dest, "Overwrite": "F"])
        let (_, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        return [200, 201, 204].contains(resp.statusCode) ? .ok : .failed(status: resp.statusCode)
    }

    public func makeCollection(href: String, name: String, color: String?) async throws {
        let colorXML = color.map { "<a:calendar-color>\(XMLText.escape($0))</a:calendar-color>" } ?? ""
        let req = try request("MKCALENDAR", href, body: """
        <?xml version="1.0" encoding="utf-8"?><c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
        <d:set><d:prop><d:displayname>\(XMLText.escape(name))</d:displayname>\(colorXML)
        <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>
        """)
        _ = try await send(req, accept: [200, 201])
    }

    public func updateCollection(href: String, name: String?, color: String?) async throws {
        var props = ""
        if let name { props += "<d:displayname>\(XMLText.escape(name))</d:displayname>" }
        if let color { props += "<a:calendar-color>\(XMLText.escape(color))</a:calendar-color>" }
        guard !props.isEmpty else { return }
        let req = try request("PROPPATCH", href, body: """
        <?xml version="1.0" encoding="utf-8"?><d:propertyupdate xmlns:d="DAV:" xmlns:a="http://apple.com/ns/ical/">
        <d:set><d:prop>\(props)</d:prop></d:set></d:propertyupdate>
        """)
        _ = try await send(req, accept: [200, 207])
    }

    public func deleteCollection(href: String) async throws {
        let req = try request("DELETE", href)
        let (_, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        guard [200, 204, 404].contains(resp.statusCode) else {
            throw DAVError.http(status: resp.statusCode, detail: "Liste konnte nicht gelöscht werden")
        }
    }
}
