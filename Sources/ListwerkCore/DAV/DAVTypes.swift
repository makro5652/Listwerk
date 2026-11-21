import Foundation

public struct RemoteList: Equatable, Sendable {
    public var href: String
    public var name: String
    public var color: String?
    public var order: Double?
    public var ctag: String?
    public var syncToken: String?
    public var canWrite: Bool
    public var isShared: Bool
    public var ownerName: String?

    public init(href: String, name: String, color: String? = nil, order: Double? = nil, ctag: String? = nil,
                syncToken: String? = nil, canWrite: Bool = true, isShared: Bool = false, ownerName: String? = nil) {
        self.href = href; self.name = name; self.color = color; self.order = order; self.ctag = ctag
        self.syncToken = syncToken; self.canWrite = canWrite; self.isShared = isShared; self.ownerName = ownerName
    }
}

public struct RemoteEntry: Equatable, Sendable {
    public var href: String
    public var etag: String?
    public init(href: String, etag: String?) { self.href = href; self.etag = etag }
}

public struct RemoteResource: Equatable, Sendable {
    public var href: String
    public var etag: String?
    public var ics: String
    public init(href: String, etag: String?, ics: String) { self.href = href; self.etag = etag; self.ics = ics }
}

public struct RemoteChanges: Equatable, Sendable {
    public var changed: [RemoteEntry]
    public var removed: [String]
    public var newToken: String?
    /// Der Server kennt das gespeicherte Sync-Token nicht mehr; ein vollständiger Abgleich ist nötig.
    public var tokenInvalid: Bool

    public init(changed: [RemoteEntry] = [], removed: [String] = [], newToken: String? = nil, tokenInvalid: Bool = false) {
        self.changed = changed; self.removed = removed; self.newToken = newToken; self.tokenInvalid = tokenInvalid
    }
}

public enum PutResult: Equatable, Sendable {
    /// `etag` fehlt, wenn der Server die Daten verändert gespeichert hat; dann muss neu geladen werden.
    case ok(etag: String?)
    case preconditionFailed
}

public enum DeleteResult: Equatable, Sendable {
    case ok
    case notFound
    case preconditionFailed
}

public enum MoveResult: Equatable, Sendable {
    case ok
    case failed(status: Int)
}

public enum DAVError: Error, Equatable {
    case unauthorized
    case http(status: Int, detail: String)
    case invalidResponse(String)
    case network(String)
}

/// Alles, was die Sync-Engine vom Server braucht. Pfade sind Serverpfade (`/remote.php/dav/...`).
public protocol CalDAVService: Sendable {
    func discover() async throws -> (principal: String, home: String)
    func listCollections(home: String) async throws -> [RemoteList]
    /// Billige Abfrage von CTag und Sync-Token einer Liste.
    func collectionState(_ list: String) async throws -> (ctag: String?, syncToken: String?)
    func changes(list: String, since token: String?) async throws -> RemoteChanges
    func listAll(list: String) async throws -> [RemoteEntry]
    func fetch(list: String, hrefs: [String]) async throws -> [RemoteResource]
    /// `etag == nil` und `create == true`: `If-None-Match: *`. Sonst `If-Match: etag`.
    func put(href: String, ics: String, etag: String?, create: Bool) async throws -> PutResult
    func delete(href: String, etag: String?) async throws -> DeleteResult
    func move(from: String, to: String) async throws -> MoveResult
    func makeCollection(href: String, name: String, color: String?) async throws
    func updateCollection(href: String, name: String?, color: String?, order: Double?) async throws
    func deleteCollection(href: String) async throws
}
