import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Ein Planwerk-Projekt, wie `GET /api/state` es liefert (Spalten der Tabelle `projects`).
public struct PlanwerkProject: Decodable, Equatable, Sendable {
    public let id: Int
    public let name: String
    public let color: String?
    public let archived: Bool?
    /// Volle URL der verknüpften Nextcloud-Aufgabenliste, z. B. `https://cloud.example.org/remote.php/dav/calendars/marv/klasse-9a/`.
    public let ncCalendar: String?
    public let ncCalendarName: String?

    enum CodingKeys: String, CodingKey {
        case id, name, color, archived
        case ncCalendar = "nc_calendar"
        case ncCalendarName = "nc_calendar_name"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        color = try c.decodeIfPresent(String.self, forKey: .color)
        // SQLite liefert 0/1; ältere Stände könnten true/false senden.
        if let b = try? c.decodeIfPresent(Bool.self, forKey: .archived) { archived = b }
        else if let i = try? c.decodeIfPresent(Int.self, forKey: .archived) { archived = i != 0 }
        else { archived = nil }
        ncCalendar = try c.decodeIfPresent(String.self, forKey: .ncCalendar)
        ncCalendarName = try c.decodeIfPresent(String.self, forKey: .ncCalendarName)
    }
}

public struct PlanwerkGroup: Decodable, Equatable, Sendable {
    public let id: Int
    public let projectId: Int
    public let name: String
    enum CodingKeys: String, CodingKey { case id, name; case projectId = "project_id" }
}

public struct PlanwerkMember: Decodable, Equatable, Sendable {
    public let id: Int
    public let name: String
}

/// Eine Aufgabe aus dem Gantt-Plan (Tabelle `tasks`).
public struct PlanwerkTask: Decodable, Equatable, Sendable {
    public let id: Int
    public let projectId: Int
    public let title: String
    public let notes: String?
    public let kind: String?
    public let startDate: String?
    public let endDate: String?
    public let done: Int?
    public let groupId: Int?
    public let assigneeId: Int?
    enum CodingKeys: String, CodingKey {
        case id, title, notes, kind, done
        case projectId = "project_id", startDate = "start_date", endDate = "end_date", groupId = "group_id", assigneeId = "assignee_id"
    }

    /// Listen-Href der angezeigten Planwerk-Einträge; keine echte Liste, nur zum Erkennen.
    public static let listPrefix = "planwerk:/"
    public static func taskID(_ rec: TaskRecord) -> Int? {
        rec.listHref.hasPrefix(listPrefix) ? Int(rec.item.uid.dropFirst("planwerk-".count)) : nil
    }
}

public struct PlanwerkState: Decodable, Equatable, Sendable {
    public let rev: Int
    public let projects: [PlanwerkProject]
    public let groups: [PlanwerkGroup]
    public let members: [PlanwerkMember]
    public let tasks: [PlanwerkTask]?

    /// Offene, terminierte Aufgaben als schreibgeschützte Einträge (für „Geplant“). Aufgaben aus Projekten, deren
    /// Nextcloud-Liste schon in `skipLists` liegt, fehlen: sie kommen bereits über den Abgleich.
    public func plannedRecords(skipLists: Set<String> = []) -> [TaskRecord] {
        let skip = Set(links.filter { skipLists.contains($0.listPath) }.map(\.projectID))
        let open = Dictionary(uniqueKeysWithValues: projects.filter { $0.archived != true }.map { ($0.id, $0) })
        return (tasks ?? []).compactMap { t in
            guard t.done ?? 0 == 0, let p = open[t.projectId], !skip.contains(p.id),
                  let day = t.endDate ?? t.startDate else { return nil }
            let ymd = day.prefix(10).split(separator: "-").compactMap { Int($0) }
            guard ymd.count == 3 else { return nil }
            var item = TodoItem(uid: "planwerk-\(t.id)", title: t.title, notes: t.notes ?? "")
            item.due = .day(ymd[0], ymd[1], ymd[2])
            item.tags = [p.name]
            item.color = p.color
            item.planwerk = PlanwerkMeta(kind: PlanwerkKind(rawValue: t.kind ?? "") ?? .task,
                                         group: groups.first { $0.id == t.groupId }?.name,
                                         assignee: members.first { $0.id == t.assigneeId }?.name)
            return TaskRecord(item: item, listHref: PlanwerkTask.listPrefix + "\(p.id)/", href: nil, etag: nil,
                              isDirty: false, isDeleted: false, ics: "", baseICS: nil)
        }
    }

    /// Projekte mit Nextcloud-Liste, samt den Gruppen- und Personennamen als Auswahlwerte.
    public var links: [PlanwerkLink] {
        let people = members.map(\.name).sorted()
        return projects.compactMap { p in
            guard p.archived != true, let url = p.ncCalendar, let c = URLComponents(string: url) else { return nil }
            var path = c.percentEncodedPath
            if !path.hasSuffix("/") { path += "/" }
            return PlanwerkLink(
                projectID: p.id, projectName: p.name, projectColor: p.color, listPath: path,
                groups: groups.filter { $0.projectId == p.id }.map(\.name),
                people: people
            )
        }
    }
}

/// Verknüpfung einer Nextcloud-Liste mit einem Planwerk-Projekt.
public struct PlanwerkLink: Equatable, Sendable {
    public let projectID: Int
    public let projectName: String
    public let projectColor: String?
    /// Serverpfad der Liste (prozentkodiert), vergleichbar mit `TaskList.href`.
    public let listPath: String
    public let groups: [String]
    public let people: [String]
}

/// Schnittstelle zu Planwerk. Ohne konfigurierten Server ist die App vollständig nutzbar (Spezifikation, Abschnitt 8).
public protocol PlanwerkConnector: Sendable {
    func fetchState() async throws -> PlanwerkState
    func currentRevision() async throws -> Int
    func triggerNextcloudSync() async throws
}

extension PlanwerkConnector {
    public func linkedLists() async throws -> [PlanwerkLink] { try await fetchState().links }
}

/// Planwerk über die REST-API, angemeldet mit einem Sync-Schlüssel (`Authorization: Bearer pwk_…`).
public struct PlanwerkClient: PlanwerkConnector {
    public let baseURL: URL
    private let token: String
    private let transport: HTTPTransport

    public init(baseURL: URL, token: String, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL
        self.token = token
        self.transport = transport
    }

    private func request(_ method: String, _ path: String) -> URLRequest {
        var r = URLRequest(url: baseURL.appendingPathComponent(path))
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        return r
    }

    private func perform(_ req: URLRequest) async throws -> Data {
        let (data, resp) = try await transport.send(req)
        if resp.statusCode == 401 { throw DAVError.unauthorized }
        guard (200..<300).contains(resp.statusCode) else {
            throw DAVError.http(status: resp.statusCode, detail: String(decoding: data.prefix(200), as: UTF8.self))
        }
        return data
    }

    public func fetchState() async throws -> PlanwerkState {
        let data = try await perform(request("GET", "api/state"))
        do { return try JSONDecoder().decode(PlanwerkState.self, from: data) }
        catch { throw DAVError.invalidResponse("Planwerk-Antwort nicht lesbar: \(error)") }
    }

    public func currentRevision() async throws -> Int {
        struct Rev: Decodable { let rev: Int }
        return try JSONDecoder().decode(Rev.self, from: try await perform(request("GET", "api/rev"))).rev
    }

    public func triggerNextcloudSync() async throws {
        _ = try await perform(request("POST", "api/nextcloud/sync"))
    }

    /// Aufgabe im Gantt-Plan abhaken bzw. wieder öffnen.
    public func setDone(taskID: Int, _ done: Bool) async throws {
        var r = request("PUT", "api/tasks/\(taskID)")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = Data(#"{"done":\#(done ? 1 : 0)}"#.utf8)
        _ = try await perform(r)
    }
}

/// Merkt sich die zuletzt gesehene Revision und lädt `/api/state` nur bei Änderungen (höchstens einmal pro `minInterval`).
public actor PlanwerkLinkCache {
    private let connector: PlanwerkConnector
    private var lastRev: Int?
    private var links: [PlanwerkLink] = []
    private var lastCheck = Date.distantPast
    private let minInterval: TimeInterval

    public init(connector: PlanwerkConnector, minInterval: TimeInterval = 60) {
        self.connector = connector
        self.minInterval = minInterval
    }

    public func current(now: Date = Date()) async throws -> [PlanwerkLink] {
        if now.timeIntervalSince(lastCheck) < minInterval, lastRev != nil { return links }
        lastCheck = now
        let rev = try await connector.currentRevision()
        if rev != lastRev {
            links = try await connector.linkedLists()
            lastRev = rev
        }
        return links
    }

    /// Markiert Listen als Planwerk-verknüpft (Pfadvergleich mit den lokalen Listen).
    public func apply(to store: Store, now: Date = Date()) async throws {
        let current = try await current(now: now)
        let paths = Set(current.map(\.listPath))
        for l in try store.lists() {
            let linked = paths.contains(l.href)
            if linked != l.isPlanwerkLinked { try store.setPlanwerkLinked(l.href, linked) }
        }
    }
}
