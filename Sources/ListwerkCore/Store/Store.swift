import Foundation

// MARK: - Modelle der lokalen Datenbank

public struct Account: Equatable, Sendable {
    public var serverURL: URL
    public var loginName: String
    public var principalPath: String?
    public var homePath: String?

    public init(serverURL: URL, loginName: String, principalPath: String? = nil, homePath: String? = nil) {
        self.serverURL = serverURL
        self.loginName = loginName
        self.principalPath = principalPath
        self.homePath = homePath
    }
}

public struct TaskList: Equatable, Identifiable, Sendable {
    /// Serverpfad der Collection, z. B. `/remote.php/dav/calendars/nutzer/arbeit/`.
    public var href: String
    public var name: String
    public var color: String?
    public var position: Double
    public var ctag: String?
    public var syncToken: String?
    public var canWrite: Bool
    public var isShared: Bool
    public var ownerName: String?
    public var isPlanwerkLinked: Bool
    var pendingCreate: Bool
    var pendingDelete: Bool
    var pendingProps: Bool

    public var id: String { href }

    public init(href: String, name: String, color: String? = nil, position: Double = 0, ctag: String? = nil,
                syncToken: String? = nil, canWrite: Bool = true, isShared: Bool = false, ownerName: String? = nil,
                isPlanwerkLinked: Bool = false) {
        self.href = href; self.name = name; self.color = color; self.position = position
        self.ctag = ctag; self.syncToken = syncToken; self.canWrite = canWrite
        self.isShared = isShared; self.ownerName = ownerName; self.isPlanwerkLinked = isPlanwerkLinked
        self.pendingCreate = false; self.pendingDelete = false; self.pendingProps = false
    }
}

public struct TaskRecord: Equatable, Identifiable, Sendable {
    public var item: TodoItem
    public var listHref: String
    /// Pfad der Ressource auf dem Server; nil, solange der Eintrag nie hochgeladen wurde.
    public var href: String?
    public var etag: String?
    public var isDirty: Bool
    public var isDeleted: Bool
    var ics: String
    var baseICS: String?

    public var id: String { listHref + "|" + item.uid }
}

struct PendingMove: Equatable {
    var uid: String
    var fromList: String
    var fromHref: String
    var toList: String
}

public struct ConflictEntry: Equatable, Sendable {
    public var uid: String
    public var field: String
    public var localValue: String
    public var remoteValue: String
    public var at: Date
}

// MARK: - Store

public final class Store: @unchecked Sendable {
    public let db: SQLiteDatabase
    private static let schemaVersion = 1

    public init(path: String) throws {
        db = try SQLiteDatabase(path: path)
        try migrate()
    }

    public static func inMemory() throws -> Store { try Store(path: ":memory:") }

    private func migrate() throws {
        guard db.userVersion < Self.schemaVersion else { return }
        try db.transaction {
            try db.execute("""
            CREATE TABLE IF NOT EXISTS account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                server_url TEXT NOT NULL, login_name TEXT NOT NULL,
                principal TEXT, home_set TEXT)
            """)
            try db.execute("""
            CREATE TABLE IF NOT EXISTS task_list (
                href TEXT PRIMARY KEY, name TEXT NOT NULL, color TEXT,
                position REAL NOT NULL DEFAULT 0, ctag TEXT, sync_token TEXT,
                can_write INTEGER NOT NULL DEFAULT 1, is_shared INTEGER NOT NULL DEFAULT 0, owner TEXT,
                planwerk_linked INTEGER NOT NULL DEFAULT 0,
                pending_create INTEGER NOT NULL DEFAULT 0, pending_delete INTEGER NOT NULL DEFAULT 0,
                pending_props INTEGER NOT NULL DEFAULT 0)
            """)
            try db.execute("""
            CREATE TABLE IF NOT EXISTS task (
                uid TEXT NOT NULL,
                list_href TEXT NOT NULL REFERENCES task_list(href) ON DELETE CASCADE ON UPDATE CASCADE,
                href TEXT, etag TEXT,
                ics TEXT NOT NULL, base_ics TEXT,
                title TEXT NOT NULL, due_key TEXT, is_done INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 0, sort_order INTEGER, parent_uid TEXT, color TEXT,
                dirty INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (list_href, uid))
            """)
            try db.execute("CREATE INDEX IF NOT EXISTS task_order ON task(list_href, sort_order)")
            try db.execute("CREATE INDEX IF NOT EXISTS task_due ON task(due_key)")
            try db.execute("CREATE INDEX IF NOT EXISTS task_parent ON task(parent_uid)")
            try db.execute("CREATE INDEX IF NOT EXISTS task_dirty ON task(dirty) WHERE dirty = 1")
            try db.execute("""
            CREATE TABLE IF NOT EXISTS pending_move (
                uid TEXT PRIMARY KEY, from_list TEXT NOT NULL, from_href TEXT NOT NULL, to_list TEXT NOT NULL)
            """)
            try db.execute("""
            CREATE TABLE IF NOT EXISTS conflict_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, field TEXT NOT NULL,
                local_value TEXT NOT NULL, remote_value TEXT NOT NULL, at REAL NOT NULL)
            """)
            try db.execute("CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        db.userVersion = Self.schemaVersion
    }

    // MARK: Konto

    public func saveAccount(_ a: Account) throws {
        try db.execute("""
        INSERT INTO account(id, server_url, login_name, principal, home_set) VALUES (1, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET server_url=excluded.server_url, login_name=excluded.login_name,
            principal=excluded.principal, home_set=excluded.home_set
        """, [.text(a.serverURL.absoluteString), .text(a.loginName), .opt(a.principalPath), .opt(a.homePath)])
    }

    public func account() throws -> Account? {
        try db.queryOne("SELECT server_url, login_name, principal, home_set FROM account WHERE id = 1") { r in
            Account(serverURL: URL(string: r.text(0))!, loginName: r.text(1), principalPath: r.optText(2), homePath: r.optText(3))
        }
    }

    /// Abmelden: löscht alle lokalen Daten.
    public func wipe() throws {
        try db.transaction {
            for t in ["pending_move", "conflict_log", "task", "task_list", "account", "setting"] {
                try db.execute("DELETE FROM \(t)")
            }
        }
    }

    public func setting(_ key: String) throws -> String? {
        try db.queryOne("SELECT value FROM setting WHERE key = ?", [.text(key)]) { $0.text(0) }
    }

    public func setSetting(_ key: String, _ value: String?) throws {
        if let value {
            try db.execute("INSERT INTO setting(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                           [.text(key), .text(value)])
        } else {
            try db.execute("DELETE FROM setting WHERE key = ?", [.text(key)])
        }
    }

    // MARK: Listen

    private static let listColumns = """
    href, name, color, position, ctag, sync_token, can_write, is_shared, owner, planwerk_linked,
    pending_create, pending_delete, pending_props
    """

    private static func list(_ r: SQLRow) -> TaskList {
        var l = TaskList(href: r.text(0), name: r.text(1), color: r.optText(2), position: r.double(3),
                         ctag: r.optText(4), syncToken: r.optText(5), canWrite: r.bool(6), isShared: r.bool(7),
                         ownerName: r.optText(8), isPlanwerkLinked: r.bool(9))
        l.pendingCreate = r.bool(10); l.pendingDelete = r.bool(11); l.pendingProps = r.bool(12)
        return l
    }

    public func lists() throws -> [TaskList] {
        try db.query("SELECT \(Self.listColumns) FROM task_list WHERE pending_delete = 0 ORDER BY position, name COLLATE NOCASE", [], Self.list)
    }

    func allListsIncludingPending() throws -> [TaskList] {
        try db.query("SELECT \(Self.listColumns) FROM task_list ORDER BY position, name COLLATE NOCASE", [], Self.list)
    }

    public func list(_ href: String) throws -> TaskList? {
        try db.queryOne("SELECT \(Self.listColumns) FROM task_list WHERE href = ?", [.text(href)], Self.list)
    }

    /// Übernimmt die Listen vom Server. Lokal angelegte, noch nicht hochgeladene Listen bleiben bestehen;
    /// vom Server verschwundene Listen werden samt Einträgen entfernt.
    func applyRemoteLists(_ remote: [RemoteList]) throws {
        try db.transaction {
            let known = Set(remote.map(\.href))
            for l in try allListsIncludingPending() where !known.contains(l.href) && !l.pendingCreate {
                try db.execute("DELETE FROM task_list WHERE href = ?", [.text(l.href)])
            }
            for (i, r) in remote.enumerated() {
                let existing = try list(r.href)
                if let existing, existing.pendingProps {
                    // Lokale Umbenennung/Färbung hat Vorrang, bis sie hochgeladen ist.
                    try db.execute("UPDATE task_list SET can_write=?, is_shared=?, owner=? WHERE href=?",
                                   [.bool(r.canWrite), .bool(r.isShared), .opt(r.ownerName), .text(r.href)])
                    continue
                }
                try db.execute("""
                INSERT INTO task_list(href, name, color, position, can_write, is_shared, owner)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(href) DO UPDATE SET name=excluded.name, color=excluded.color, position=excluded.position,
                    can_write=excluded.can_write, is_shared=excluded.is_shared, owner=excluded.owner,
                    pending_create=0
                """, [.text(r.href), .text(r.name), .opt(r.color), .double(r.order ?? Double(i)),
                      .bool(r.canWrite), .bool(r.isShared), .opt(r.ownerName)])
            }
        }
    }

    /// Legt eine Liste lokal an; `MKCALENDAR` folgt beim nächsten Abgleich.
    @discardableResult
    public func createList(name: String, color: String?, homePath: String) throws -> TaskList {
        var slug = Self.slug(name)
        var href = homePath + slug + "/"
        var n = 2
        while try list(href) != nil { slug = Self.slug(name) + "-\(n)"; href = homePath + slug + "/"; n += 1 }
        let pos = (try db.queryOne("SELECT COALESCE(MAX(position), 0) + 1 FROM task_list") { $0.double(0) }) ?? 0
        try db.execute("INSERT INTO task_list(href, name, color, position, pending_create) VALUES (?, ?, ?, ?, 1)",
                       [.text(href), .text(name), .opt(color), .double(pos)])
        return try list(href)!
    }

    public func updateList(_ href: String, name: String? = nil, color: String?? = nil) throws {
        if let name { try db.execute("UPDATE task_list SET name=?, pending_props=1 WHERE href=?", [.text(name), .text(href)]) }
        if let color { try db.execute("UPDATE task_list SET color=?, pending_props=1 WHERE href=?", [.opt(color), .text(href)]) }
    }

    public func setListPosition(_ href: String, _ position: Double) throws {
        try db.execute("UPDATE task_list SET position=? WHERE href=?", [.double(position), .text(href)])
    }

    public func setPlanwerkLinked(_ href: String, _ linked: Bool) throws {
        try db.execute("UPDATE task_list SET planwerk_linked=? WHERE href=?", [.bool(linked), .text(href)])
    }

    /// Markiert die Liste zum Löschen (erst beim Abgleich auf dem Server). Nie hochgeladene Listen verschwinden sofort.
    public func deleteList(_ href: String) throws {
        guard let l = try list(href) else { return }
        if l.pendingCreate {
            try db.execute("DELETE FROM task_list WHERE href = ?", [.text(href)])
        } else {
            try db.execute("UPDATE task_list SET pending_delete = 1 WHERE href = ?", [.text(href)])
        }
    }

    func updateListSyncState(_ href: String, ctag: String?, syncToken: String?) throws {
        try db.execute("UPDATE task_list SET ctag=?, sync_token=? WHERE href=?", [.opt(ctag), .opt(syncToken), .text(href)])
    }

    func clearListFlags(_ href: String) throws {
        try db.execute("UPDATE task_list SET pending_create=0, pending_props=0 WHERE href=?", [.text(href)])
    }

    func removeListLocally(_ href: String) throws {
        try db.execute("DELETE FROM task_list WHERE href = ?", [.text(href)])
    }

    static func slug(_ name: String) -> String {
        let folded = name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "de_DE"))
            .replacingOccurrences(of: "ß", with: "ss")
        var out = ""
        var lastDash = true
        for ch in folded.unicodeScalars {
            if (ch.value >= 97 && ch.value <= 122) || (ch.value >= 48 && ch.value <= 57) {
                out.unicodeScalars.append(ch); lastDash = false
            } else if !lastDash {
                out.append("-"); lastDash = true
            }
        }
        while out.hasSuffix("-") { out.removeLast() }
        return out.isEmpty ? "liste" : out
    }

    // MARK: Einträge lesen

    private static let taskColumns = "uid, list_href, href, etag, ics, base_ics, dirty, deleted"

    private func records(where clause: String, _ params: [SQLValue] = [], order: String = "sort_order IS NULL, sort_order, rowid") throws -> [TaskRecord] {
        try db.query("SELECT \(Self.taskColumns) FROM task WHERE \(clause) ORDER BY \(order)", params) { r in
            guard let doc = ICalDocument(parsing: r.text(4)) else { return nil as TaskRecord? }
            var item = doc.makeItem()
            if item.uid.isEmpty { item.uid = r.text(0) }
            return TaskRecord(item: item, listHref: r.text(1), href: r.optText(2), etag: r.optText(3),
                              isDirty: r.bool(6), isDeleted: r.bool(7), ics: r.text(4), baseICS: r.optText(5))
        }.compactMap { $0 }
    }

    public func tasks(inList href: String) throws -> [TaskRecord] {
        try records(where: "list_href = ? AND deleted = 0", [.text(href)])
    }

    public func allTasks() throws -> [TaskRecord] {
        try records(where: "deleted = 0", order: "due_key IS NULL, due_key, rowid")
    }

    public func record(uid: String, inList href: String) throws -> TaskRecord? {
        try records(where: "list_href = ? AND uid = ?", [.text(href), .text(uid)]).first
    }

    func dirtyRecords(inList href: String) throws -> [TaskRecord] {
        try records(where: "list_href = ? AND dirty = 1", [.text(href)])
    }

    func recordsWithHref(inList href: String) throws -> [TaskRecord] {
        try records(where: "list_href = ? AND href IS NOT NULL", [.text(href)])
    }

    public func openTaskCount(inList href: String) throws -> Int {
        try db.queryOne("SELECT COUNT(*) FROM task WHERE list_href=? AND deleted=0 AND is_done=0", [.text(href)]) { $0.int(0) } ?? 0
    }

    // MARK: Einträge schreiben

    private func derived(_ item: TodoItem) -> [SQLValue] {
        [.text(item.title), .opt(item.due?.sortKey), .bool(item.isCompleted), .int(Int64(item.priority)),
         .opt(item.sortOrder), .opt(item.parentUID), .opt(item.color)]
    }

    /// Legt einen Eintrag an oder aktualisiert ihn. Ändert sich nichts, bleibt der Datensatz unberührt.
    @discardableResult
    public func save(_ item: TodoItem, inList listHref: String, now: Date = Date()) throws -> TaskRecord {
        try db.transaction {
            let existing = try record(uid: item.uid, inList: listHref)
            var doc = existing.flatMap { ICalDocument(parsing: $0.ics) } ?? ICalDocument(newWithUID: item.uid)
            doc.apply(item, now: now)
            let ics = doc.serialized()
            if let existing {
                if ics == existing.ics && !existing.isDeleted { return existing }
                try db.execute("""
                UPDATE task SET ics=?, title=?, due_key=?, is_done=?, priority=?, sort_order=?, parent_uid=?, color=?,
                    dirty=1, deleted=0 WHERE list_href=? AND uid=?
                """, [.text(ics)] + derived(doc.makeItem()) + [.text(listHref), .text(item.uid)])
            } else {
                try db.execute("""
                INSERT INTO task(ics, title, due_key, is_done, priority, sort_order, parent_uid, color, dirty, deleted, list_href, uid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)
                """, [.text(ics)] + derived(doc.makeItem()) + [.text(listHref), .text(item.uid)])
            }
            return try record(uid: item.uid, inList: listHref)!
        }
    }

    /// Löscht den Eintrag samt Unteraufgaben. Nie hochgeladene Einträge verschwinden sofort,
    /// alle anderen werden beim nächsten Abgleich auf dem Server gelöscht.
    public func delete(uid: String, inList listHref: String) throws {
        try db.transaction {
            for u in try descendantUIDs(of: uid, inList: listHref) + [uid] {
                try db.execute("DELETE FROM task WHERE list_href=? AND uid=? AND href IS NULL", [.text(listHref), .text(u)])
                try db.execute("UPDATE task SET deleted=1, dirty=1 WHERE list_href=? AND uid=?", [.text(listHref), .text(u)])
                try db.execute("DELETE FROM pending_move WHERE uid=?", [.text(u)])
            }
        }
    }

    /// „Rückgängig“ für einen eben gelöschten Eintrag, solange noch nicht synchronisiert wurde.
    public func undelete(uid: String, inList listHref: String) throws {
        try db.execute("UPDATE task SET deleted=0, dirty=1 WHERE list_href=? AND uid=?", [.text(listHref), .text(uid)])
    }

    func descendantUIDs(of uid: String, inList listHref: String) throws -> [String] {
        var result: [String] = []
        var frontier = [uid]
        while let next = frontier.popLast() {
            let kids = try db.query("SELECT uid FROM task WHERE list_href=? AND parent_uid=? AND deleted=0",
                                    [.text(listHref), .text(next)]) { $0.text(0) }
            for k in kids where !result.contains(k) && k != uid { result.append(k); frontier.append(k) }
        }
        return result
    }

    /// Verschiebt einen Eintrag samt Unteraufgaben in eine andere Liste.
    public func move(uid: String, fromList: String, toList: String) throws {
        guard fromList != toList else { return }
        try db.transaction {
            for u in [uid] + (try descendantUIDs(of: uid, inList: fromList)) {
                guard let rec = try record(uid: u, inList: fromList) else { continue }
                // Eine gleichnamige UID im Ziel würde den Primärschlüssel verletzen.
                if try record(uid: u, inList: toList) != nil { continue }
                let pending = try db.queryOne("SELECT from_list, from_href FROM pending_move WHERE uid=?", [.text(u)]) { ($0.text(0), $0.text(1)) }
                // Hochgeladene Einträge verschiebt der Server per MOVE (pending_move); dirty bleibt unberührt.
                try db.execute("UPDATE task SET list_href=? WHERE list_href=? AND uid=?",
                               [.text(toList), .text(fromList), .text(u)])
                guard let href = rec.href else { continue }  // nie hochgeladen: wird im Ziel neu angelegt
                if let (origList, origHref) = pending {
                    if origList == toList {
                        try db.execute("DELETE FROM pending_move WHERE uid=?", [.text(u)])
                    } else {
                        try db.execute("UPDATE pending_move SET to_list=? WHERE uid=?", [.text(toList), .text(u)])
                        _ = origHref
                    }
                } else {
                    try db.execute("INSERT INTO pending_move(uid, from_list, from_href, to_list) VALUES (?, ?, ?, ?)",
                                   [.text(u), .text(fromList), .text(href), .text(toList)])
                }
            }
        }
    }

    func pendingMoves() throws -> [PendingMove] {
        try db.query("SELECT uid, from_list, from_href, to_list FROM pending_move") {
            PendingMove(uid: $0.text(0), fromList: $0.text(1), fromHref: $0.text(2), toList: $0.text(3))
        }
    }

    func clearPendingMove(uid: String) throws {
        try db.execute("DELETE FROM pending_move WHERE uid=?", [.text(uid)])
    }

    /// Setzt den Eintrag hinter `afterUID` (nil = an den Anfang). Berechnet den Sortierwert zwischen den Nachbarn;
    /// ist kein Abstand mehr frei, wird die Liste in 1024er-Schritten neu nummeriert.
    public func reorder(uid: String, inList listHref: String, after afterUID: String?, now: Date = Date()) throws {
        try db.transaction {
            var ordered = try tasks(inList: listHref).filter { $0.item.parentUID == nil }
            guard let movedIndex = ordered.firstIndex(where: { $0.item.uid == uid }) else { return }
            let moved = ordered.remove(at: movedIndex)
            let insertAt = afterUID.flatMap { id in ordered.firstIndex { $0.item.uid == id }.map { $0 + 1 } } ?? 0
            let prev = insertAt > 0 ? ordered[insertAt - 1].item.sortOrder : nil
            let next = insertAt < ordered.count ? ordered[insertAt].item.sortOrder : nil
            let hasPrev = insertAt > 0, hasNext = insertAt < ordered.count

            var value: Int?
            switch (hasPrev, prev, hasNext, next) {
            case (false, _, false, _): value = 1024
            case (false, _, true, let n?): value = n - 1024
            case (true, let p?, false, _): value = p + 1024
            case (true, let p?, true, let n?) where n - p >= 2: value = p + (n - p) / 2
            default: value = nil
            }

            if let value {
                var item = moved.item
                item.sortOrder = value
                try save(item, inList: listHref, now: now)
            } else {
                ordered.insert(moved, at: insertAt)
                for (i, rec) in ordered.enumerated() {
                    var item = rec.item
                    item.sortOrder = (i + 1) * 1024
                    try save(item, inList: listHref, now: now)
                }
            }
        }
    }

    // MARK: Abgleich (intern)

    func markSynced(uid: String, listHref: String, href: String, etag: String?, ics: String) throws {
        let item = ICalDocument(parsing: ics)?.makeItem()
        var params: [SQLValue] = [.text(href), .opt(etag), .text(ics), .text(ics)]
        var set = "href=?, etag=?, ics=?, base_ics=?, dirty=0"
        if let item {
            set += ", title=?, due_key=?, is_done=?, priority=?, sort_order=?, parent_uid=?, color=?"
            params += derived(item)
        }
        try db.execute("UPDATE task SET \(set) WHERE list_href=? AND uid=?", params + [.text(listHref), .text(item?.uid ?? uid)])
    }

    /// Setzt Pfad (und optional ETag und Basisstand) einer Ressource. `nil` löscht den jeweiligen Wert.
    func setHref(uid: String, listHref: String, href: String?) throws {
        try db.execute("UPDATE task SET href=? WHERE list_href=? AND uid=?", [.opt(href), .text(listHref), .text(uid)])
    }

    func setHref(uid: String, listHref: String, href: String?, etag: String?, baseICS: String?) throws {
        try db.execute("UPDATE task SET href=?, etag=?, base_ics=? WHERE list_href=? AND uid=?",
                       [.opt(href), .opt(etag), .opt(baseICS), .text(listHref), .text(uid)])
    }

    /// Entfernt einen Eintrag endgültig aus der lokalen Datenbank (nach erfolgreichem DELETE oder Serverlöschung).
    func purge(uid: String, listHref: String) throws {
        try db.execute("DELETE FROM task WHERE list_href=? AND uid=?", [.text(listHref), .text(uid)])
        try db.execute("DELETE FROM pending_move WHERE uid=?", [.text(uid)])
    }

    /// Schreibt den Serverstand in einen lokal nicht geänderten Eintrag (oder legt ihn an).
    func upsertFromServer(listHref: String, href: String, etag: String?, ics: String) throws {
        guard let item = ICalDocument(parsing: ics)?.makeItem(), !item.uid.isEmpty else { return }
        try db.transaction {
            let exists = try record(uid: item.uid, inList: listHref) != nil
            if exists {
                try db.execute("""
                UPDATE task SET href=?, etag=?, ics=?, base_ics=?, dirty=0, deleted=0, title=?, due_key=?, is_done=?, priority=?,
                    sort_order=?, parent_uid=?, color=? WHERE list_href=? AND uid=?
                """, [.text(href), .opt(etag), .text(ics), .text(ics)] + derived(item) + [.text(listHref), .text(item.uid)])
            } else {
                try db.execute("""
                INSERT INTO task(href, etag, ics, base_ics, dirty, deleted, title, due_key, is_done, priority, sort_order, parent_uid, color,
                    list_href, uid) VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, [.text(href), .opt(etag), .text(ics), .text(ics)] + derived(item) + [.text(listHref), .text(item.uid)])
            }
        }
    }

    /// Schreibt eine zusammengeführte Fassung: `ics` ist der Serverstand plus lokale Änderungen und wird erneut hochgeladen.
    func storeMerged(uid: String, listHref: String, serverICS: String, serverETag: String?, mergedICS: String) throws {
        guard let item = ICalDocument(parsing: mergedICS)?.makeItem() else { return }
        try db.execute("""
        UPDATE task SET ics=?, base_ics=?, etag=?, dirty=1, title=?, due_key=?, is_done=?, priority=?, sort_order=?, parent_uid=?, color=?
        WHERE list_href=? AND uid=?
        """, [.text(mergedICS), .text(serverICS), .opt(serverETag)] + derived(item) + [.text(listHref), .text(uid)])
    }

    // MARK: Konfliktprotokoll

    func logConflict(uid: String, field: String, local: String, remote: String, at: Date) throws {
        try db.execute("INSERT INTO conflict_log(uid, field, local_value, remote_value, at) VALUES (?, ?, ?, ?, ?)",
                       [.text(uid), .text(field), .text(local), .text(remote), .double(at.timeIntervalSince1970)])
    }

    public func conflicts(forUID uid: String) throws -> [ConflictEntry] {
        try db.query("SELECT uid, field, local_value, remote_value, at FROM conflict_log WHERE uid=? ORDER BY id DESC", [.text(uid)]) {
            ConflictEntry(uid: $0.text(0), field: $0.text(1), localValue: $0.text(2), remoteValue: $0.text(3),
                          at: Date(timeIntervalSince1970: $0.double(4)))
        }
    }
}
