import Foundation
@testable import ListwerkCore

/// In-Memory-CalDAV-Server mit ETags, Sync-Tokens und Tombstones. Verhält sich wie sabre/dav in den
/// Punkten, auf die sich die Sync-Engine verlässt (siehe Spezifikation, Abschnitt 4).
final class FakeServer: CalDAVService, @unchecked Sendable {
    struct Resource { var ics: String; var etag: String; var modRev: Int }
    struct Collection {
        var name: String
        var color: String?
        var items: [String: Resource] = [:]
        var tombstones: [String: Int] = [:]
        var canWrite = true
    }

    private let lock = NSLock()
    private(set) var collections: [String: Collection] = [:]
    private var rev = 1
    private var etagCounter = 0
    private(set) var calls: [String] = []

    // Schalter für Sonderverhalten
    var supportsSyncCollection = true
    var invalidateTokens = false
    var omitETagOnPut = false
    var movesSupported = true
    var failAuth = false

    let home = "/remote.php/dav/calendars/marv/"
    var clock = Date(timeIntervalSince1970: 1_794_398_400)

    func count(_ prefix: String) -> Int { lock.lock(); defer { lock.unlock() }; return calls.filter { $0.hasPrefix(prefix) }.count }
    func resetCalls() { lock.lock(); calls = []; lock.unlock() }

    private func log(_ s: String) throws {
        calls.append(s)
        if failAuth { throw DAVError.unauthorized }
    }

    private func nextETag() -> String { etagCounter += 1; return "\"e\(etagCounter)\"" }

    // MARK: Testhilfen (Änderungen "durch ein anderes Gerät")

    @discardableResult
    func addCollection(_ name: String, color: String? = nil, canWrite: Bool = true) -> String {
        lock.lock(); defer { lock.unlock() }
        let href = home + Store.slug(name) + "/"
        collections[href] = Collection(name: name, color: color, canWrite: canWrite)
        rev += 1
        return href
    }

    @discardableResult
    func seed(_ list: String, _ item: TodoItem, file: String? = nil) -> String {
        lock.lock(); defer { lock.unlock() }
        var doc = ICalDocument(newWithUID: item.uid)
        var it = item
        doc.apply(it, now: clock)
        it = doc.makeItem()
        let href = list + (file ?? SyncEngine.fileName(for: item.uid))
        rev += 1
        collections[list]!.items[href] = Resource(ics: doc.serialized(), etag: nextETag(), modRev: rev)
        return href
    }

    /// Ändert einen Eintrag wie ein anderer Client: `clock` bestimmt LAST-MODIFIED.
    func edit(_ list: String, uid: String, _ change: (inout TodoItem) -> Void) {
        lock.lock(); defer { lock.unlock() }
        let (href, res) = collections[list]!.items.first { ICalDocument(parsing: $0.value.ics)?.makeItem().uid == uid }!
        var doc = ICalDocument(parsing: res.ics)!
        var item = doc.makeItem()
        change(&item)
        doc.apply(item, now: clock)
        rev += 1
        collections[list]!.items[href] = Resource(ics: doc.serialized(), etag: nextETag(), modRev: rev)
    }

    func remove(_ list: String, uid: String) {
        lock.lock(); defer { lock.unlock() }
        guard let (href, _) = collections[list]!.items.first(where: { ICalDocument(parsing: $0.value.ics)?.makeItem().uid == uid }) else { return }
        rev += 1
        collections[list]!.items[href] = nil
        collections[list]!.tombstones[href] = rev
    }

    func item(_ list: String, uid: String) -> TodoItem? {
        lock.lock(); defer { lock.unlock() }
        for (_, r) in collections[list]?.items ?? [:] {
            if let i = ICalDocument(parsing: r.ics)?.makeItem(), i.uid == uid { return i }
        }
        return nil
    }

    func titles(_ list: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return (collections[list]?.items.values.compactMap { ICalDocument(parsing: $0.ics)?.makeItem().title } ?? []).sorted()
    }

    func hrefs(_ list: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return collections[list]?.items.keys.sorted() ?? []
    }

    // MARK: CalDAVService

    func discover() async throws -> (principal: String, home: String) {
        lock.lock(); defer { lock.unlock() }
        try log("discover")
        return ("/remote.php/dav/principals/users/marv/", home)
    }

    func listCollections(home: String) async throws -> [RemoteList] {
        lock.lock(); defer { lock.unlock() }
        try log("listCollections")
        return collections.sorted { $0.key < $1.key }.enumerated().map { i, kv in
            RemoteList(href: kv.key, name: kv.value.name, color: kv.value.color, order: Double(i),
                       ctag: "ctag-\(rev)", syncToken: "tok-\(rev)", canWrite: kv.value.canWrite)
        }
    }

    func collectionState(_ list: String) async throws -> (ctag: String?, syncToken: String?) {
        lock.lock(); defer { lock.unlock() }
        try log("collectionState")
        return ("ctag-\(rev)", "tok-\(rev)")
    }

    func changes(list: String, since token: String?) async throws -> RemoteChanges {
        lock.lock(); defer { lock.unlock() }
        try log("changes \(token ?? "nil")")
        guard supportsSyncCollection else { throw DAVError.http(status: 501, detail: "nicht unterstützt") }
        guard let col = collections[list] else { throw DAVError.http(status: 404, detail: "") }
        var since = 0
        if let token {
            guard !invalidateTokens, token.hasPrefix("tok-"), let n = Int(token.dropFirst(4)) else { return RemoteChanges(tokenInvalid: true) }
            since = n
        }
        let changed = col.items.filter { $0.value.modRev > since }.sorted { $0.key < $1.key }
            .map { RemoteEntry(href: $0.key, etag: $0.value.etag) }
        let removed = col.tombstones.filter { $0.value > since }.map(\.key).sorted()
        return RemoteChanges(changed: changed, removed: removed, newToken: "tok-\(rev)")
    }

    func listAll(list: String) async throws -> [RemoteEntry] {
        lock.lock(); defer { lock.unlock() }
        try log("listAll")
        return (collections[list]?.items ?? [:]).sorted { $0.key < $1.key }.map { RemoteEntry(href: $0.key, etag: $0.value.etag) }
    }

    func fetch(list: String, hrefs: [String]) async throws -> [RemoteResource] {
        lock.lock(); defer { lock.unlock() }
        try log("fetch \(hrefs.count)")
        return hrefs.compactMap { h in collections[list]?.items[h].map { RemoteResource(href: h, etag: $0.etag, ics: $0.ics) } }
    }

    func put(href: String, ics: String, etag: String?, create: Bool) async throws -> PutResult {
        lock.lock(); defer { lock.unlock() }
        try log("put \(create ? "create" : "update") \(href)")
        guard let listHref = collections.keys.first(where: { href.hasPrefix($0) }) else { throw DAVError.http(status: 404, detail: "") }
        guard collections[listHref]!.canWrite else { throw DAVError.http(status: 403, detail: "schreibgeschützt") }
        let existing = collections[listHref]!.items[href]
        if create, existing != nil { return .preconditionFailed }
        if !create {
            guard let existing, existing.etag == etag else { return .preconditionFailed }
        }
        rev += 1
        let newTag = nextETag()
        collections[listHref]!.items[href] = Resource(ics: ics, etag: newTag, modRev: rev)
        collections[listHref]!.tombstones[href] = nil
        return .ok(etag: omitETagOnPut ? nil : newTag)
    }

    func delete(href: String, etag: String?) async throws -> DeleteResult {
        lock.lock(); defer { lock.unlock() }
        try log("delete \(href)")
        guard let listHref = collections.keys.first(where: { href.hasPrefix($0) }),
              let existing = collections[listHref]!.items[href] else { return .notFound }
        if let etag, etag != existing.etag { return .preconditionFailed }
        rev += 1
        collections[listHref]!.items[href] = nil
        collections[listHref]!.tombstones[href] = rev
        return .ok
    }

    func move(from: String, to: String) async throws -> MoveResult {
        lock.lock(); defer { lock.unlock() }
        try log("move \(from) -> \(to)")
        guard movesSupported else { return .failed(status: 501) }
        guard let src = collections.keys.first(where: { from.hasPrefix($0) }),
              let dst = collections.keys.first(where: { to.hasPrefix($0) }),
              let res = collections[src]!.items[from] else { return .failed(status: 404) }
        rev += 1
        collections[src]!.items[from] = nil
        collections[src]!.tombstones[from] = rev
        collections[dst]!.items[to] = Resource(ics: res.ics, etag: res.etag, modRev: rev)
        return .ok
    }

    func makeCollection(href: String, name: String, color: String?) async throws {
        lock.lock(); defer { lock.unlock() }
        try log("mkcalendar \(href)")
        if collections[href] != nil { throw DAVError.http(status: 405, detail: "existiert") }
        collections[href] = Collection(name: name, color: color)
        rev += 1
    }

    func updateCollection(href: String, name: String?, color: String?) async throws {
        lock.lock(); defer { lock.unlock() }
        try log("proppatch \(href)")
        if let name { collections[href]?.name = name }
        if let color { collections[href]?.color = color }
        rev += 1
    }

    func deleteCollection(href: String) async throws {
        lock.lock(); defer { lock.unlock() }
        try log("deleteCollection \(href)")
        collections[href] = nil
        rev += 1
    }
}

/// Testumgebung: Store + Fake-Server + Engine mit festem Takt.
struct Env {
    let store: Store
    let server: FakeServer
    let engine: SyncEngine
    var list: String

    static func make(items: [TodoItem] = []) async throws -> Env {
        let store = try Store.inMemory()
        let server = FakeServer()
        let list = server.addCollection("Schule", color: "#1e66f5")
        for i in items { server.seed(list, i) }
        try store.saveAccount(Account(serverURL: URL(string: "https://cloud.example.org")!, loginName: "marv",
                                      principalPath: "/remote.php/dav/principals/users/marv/", homePath: server.home))
        let engine = SyncEngine(store: store, service: server, now: { Date(timeIntervalSince1970: 1_794_398_400 + 600) })
        return Env(store: store, server: server, engine: engine, list: list)
    }

    @discardableResult
    func sync() async throws -> SyncReport { try await engine.syncAll() }
}

func todo(_ title: String, uid: String? = nil, _ configure: (inout TodoItem) -> Void = { _ in }) -> TodoItem {
    var t = TodoItem(uid: uid ?? title.lowercased().replacingOccurrences(of: " ", with: "-"), title: title)
    configure(&t)
    return t
}
