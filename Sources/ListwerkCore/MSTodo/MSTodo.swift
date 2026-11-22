import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Einseitiger Import aus Microsoft To Do (Graph-API, privates Konto, Rechte `Tasks.Read`).
/// Anmeldung per Geräte-Code: kein Redirect, kein Geheimnis, nur eine eigene App-ID (Client-ID).
public struct MSTodoClient: Sendable {
    public struct DeviceCode: Sendable {
        public let userCode: String
        public let verificationURL: URL
        let deviceCode: String
        public let interval: Int
        public let expiresIn: Int
    }
    public struct Tokens: Sendable { public let access: String; public let refresh: String }

    struct MSList: Decodable { let id: String; let displayName: String }
    struct MSTask: Decodable {
        struct Body: Decodable { let content: String? }
        struct When: Decodable { let dateTime: String; let timeZone: String? }
        let id: String
        let title: String
        let status: String
        let importance: String?
        let body: Body?
        let dueDateTime: When?
        let reminderDateTime: When?
        let isReminderOn: Bool?
        let completedDateTime: When?
    }
    struct Page<T: Decodable>: Decodable {
        let value: [T]
        let next: String?
        enum CodingKeys: String, CodingKey { case value; case next = "@odata.nextLink" }
    }
    public struct Imported: Sendable { let lists: [(MSList, [MSTask])] }

    private let clientID: String
    private let transport: HTTPTransport
    private static let auth = "https://login.microsoftonline.com/consumers/oauth2/v2.0/"
    private static let scope = "Tasks.Read offline_access"

    public init(clientID: String, transport: HTTPTransport = URLSessionTransport()) {
        self.clientID = clientID
        self.transport = transport
    }

    private func form(_ endpoint: String, _ fields: [String: String]) async throws -> (Data, Int) {
        var req = URLRequest(url: URL(string: Self.auth + endpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        req.httpBody = Data(fields.map { "\($0)=\($1.addingPercentEncoding(withAllowedCharacters: allowed)!)" }
            .joined(separator: "&").utf8)
        let (data, resp) = try await transport.send(req)
        return (data, resp.statusCode)
    }

    private static func fail(_ data: Data, _ status: Int) -> DAVError {
        struct E: Decodable { let error_description: String? }
        let d = (try? JSONDecoder().decode(E.self, from: data))?.error_description ?? String(decoding: data.prefix(200), as: UTF8.self)
        return .http(status: status, detail: d)
    }

    public func startDeviceLogin() async throws -> DeviceCode {
        let (data, status) = try await form("devicecode", ["client_id": clientID, "scope": Self.scope])
        guard status == 200 else { throw Self.fail(data, status) }
        struct R: Decodable {
            let user_code: String, device_code: String, verification_uri: String, expires_in: Int, interval: Int?
        }
        let r = try JSONDecoder().decode(R.self, from: data)
        guard let url = URL(string: r.verification_uri) else { throw DAVError.invalidResponse(L("Ungültige Anmeldeadresse")) }
        return DeviceCode(userCode: r.user_code, verificationURL: url, deviceCode: r.device_code,
                          interval: r.interval ?? 5, expiresIn: r.expires_in)
    }

    /// Nil, solange der Nutzer den Code noch nicht bestätigt hat.
    public func pollDeviceLogin(_ d: DeviceCode) async throws -> Tokens? {
        let (data, status) = try await form("token", [
            "client_id": clientID, "grant_type": "urn:ietf:params:oauth:grant-type:device_code", "device_code": d.deviceCode])
        struct E: Decodable { let error: String? }   // die echte Antwort enthält auch Listen (error_codes)
        if status == 400, let e = try? JSONDecoder().decode(E.self, from: data),
           ["authorization_pending", "slow_down"].contains(e.error) { return nil }
        return try Self.tokens(data, status)
    }

    public func refresh(_ refreshToken: String) async throws -> Tokens {
        let (data, status) = try await form("token", [
            "client_id": clientID, "grant_type": "refresh_token", "refresh_token": refreshToken, "scope": Self.scope])
        return try Self.tokens(data, status)
    }

    private static func tokens(_ data: Data, _ status: Int) throws -> Tokens {
        guard status == 200 else { throw fail(data, status) }
        struct R: Decodable { let access_token: String; let refresh_token: String }
        let r = try JSONDecoder().decode(R.self, from: data)
        return Tokens(access: r.access_token, refresh: r.refresh_token)
    }

    private func get<T: Decodable>(_ url: URL, _ token: String) async throws -> Page<T> {
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        guard resp.statusCode == 200 else { throw Self.fail(data, resp.statusCode) }
        return try JSONDecoder().decode(Page<T>.self, from: data)
    }

    private func all<T: Decodable>(_ first: String, _ token: String) async throws -> [T] {
        var out: [T] = [], next: URL? = URL(string: first)
        while let url = next {
            let page: Page<T> = try await get(url, token)
            out += page.value
            next = page.next.flatMap(URL.init(string:))
        }
        return out
    }

    public func fetchAll(accessToken: String) async throws -> Imported {
        let base = "https://graph.microsoft.com/v1.0/me/todo/lists"
        let lists: [MSList] = try await all(base, accessToken)
        var out: [(MSList, [MSTask])] = []
        for l in lists {
            out.append((l, try await all("\(base)/\(l.id)/tasks?$top=100", accessToken)))
        }
        return Imported(lists: out)
    }
}

// MARK: - Abbildung auf Listwerk

extension MSTodoClient.MSTask {
    /// Stabile UID, damit ein erneuter Import denselben Eintrag aktualisiert statt zu verdoppeln.
    var uid: String { "mstodo-" + id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" } }

    // shortcut: Zeitzonennamen von Microsoft (Windows-Namen) werden als UTC gelesen; To Do liefert Datum/Erinnerung in UTC.
    private static func date(_ w: MSTodoClient.MSTask.When?) -> Date? {
        guard let s = w?.dateTime, s.count >= 19 else { return nil }
        return ISO8601DateFormatter().date(from: String(s.prefix(19)) + "Z")
    }

    func apply(to item: TodoItem) -> TodoItem {
        var i = item
        i.title = title
        i.notes = body?.content ?? ""
        i.priority = ["high": 1, "low": 9][importance ?? ""] ?? 0
        i.isCompleted = status == "completed"
        i.completedAt = i.isCompleted ? Self.date(completedDateTime) : nil
        i.percentComplete = i.isCompleted ? 100 : 0
        // To Do kennt nur Fälligkeitstage; die Uhrzeit ist immer 00:00.
        i.due = dueDateTime.flatMap { w in
            let p = w.dateTime.prefix(10).split(separator: "-").compactMap { Int($0) }
            return p.count == 3 ? ICalDate.day(p[0], p[1], p[2]) : nil
        }
        i.reminders = isReminderOn == true ? Self.date(reminderDateTime).map { [Reminder(trigger: .absolute($0))] } ?? [] : []
        return i
    }
}

extension Store {
    /// Schreibt die To-Do-Listen als Listwerk-Listen (neu angelegt, beim nächsten Abgleich zu Nextcloud) und
    /// aktualisiert Einträge anhand ihrer UID. In To Do gelöschte Einträge bleiben hier bestehen.
    @discardableResult
    public func importMSTodo(_ data: MSTodoClient.Imported, homePath: String) throws -> (lists: Int, tasks: Int) {
        var tasks = 0
        try db.transaction {   // eine Transaktion: schnell, und bei einem Fehler bleibt nichts Halbes zurück
        for (l, items) in data.lists {
            let key = "mstodo.list." + l.id
            var known: String? = nil
            if let h = try setting(key), try list(h) != nil { known = h }
            let href = try known ?? createList(name: l.displayName, color: nil, homePath: homePath).href
            try setSetting(key, href)
            for t in items {
                let existing = try record(uid: t.uid, inList: href)?.item
                try save(t.apply(to: existing ?? TodoItem(uid: t.uid)), inList: href)
                tasks += 1
            }
        }
        }
        return (data.lists.count, tasks)
    }
}
