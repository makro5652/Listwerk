import Foundation

/// Unverschlüsselte Sicherung aller Listen als JSON. Einträge stehen als iCalendar-Text darin,
/// damit nichts verloren geht (auch Felder anderer Programme).
public struct ListwerkBackup: Codable, Sendable {
    public struct List: Codable, Sendable {
        public var name: String
        public var color: String?
        public var tasks: [String]
    }
    public var format = "listwerk-backup"
    public var version = 1
    public var exported: Date
    public var lists: [List]

    static let encoder: JSONEncoder = {
        let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = [.prettyPrinted, .sortedKeys]; return e
    }()
    static let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }()
}

public enum BackupError: Error, LocalizedError {
    case notABackup
    public var errorDescription: String? { "Die Datei ist keine Listwerk-Sicherung." }
}

extension Store {
    public func exportBackup(now: Date = Date()) throws -> Data {
        let lists = try lists().map { l in
            ListwerkBackup.List(name: l.name, color: l.color, tasks: try tasks(inList: l.href).map(\.ics))
        }
        return try ListwerkBackup.encoder.encode(ListwerkBackup(exported: now, lists: lists))
    }

    /// Stellt eine Sicherung wieder her. Listen mit gleichem Namen werden ergänzt, Einträge mit gleicher UID
    /// überschrieben; nichts wird gelöscht. Ein erneuter Import derselben Datei verdoppelt also nichts.
    @discardableResult
    public func importBackup(_ data: Data, homePath: String) throws -> (lists: Int, tasks: Int) {
        guard let backup = try? ListwerkBackup.decoder.decode(ListwerkBackup.self, from: data),
              backup.format == "listwerk-backup" else { throw BackupError.notABackup }
        var tasks = 0
        try db.transaction {   // alles oder nichts
            let existing = try lists()
            for l in backup.lists {
                let href = try existing.first { $0.name == l.name }?.href
                    ?? createList(name: l.name, color: l.color, homePath: homePath).href
                for ics in l.tasks {
                    if try saveICS(ics, inList: href) != nil { tasks += 1 }
                }
            }
        }
        return (backup.lists.count, tasks)
    }
}
