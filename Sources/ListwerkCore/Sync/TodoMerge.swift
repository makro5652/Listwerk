import Foundation

public struct FieldConflict: Equatable, Sendable {
    public enum Winner: Equatable, Sendable { case local, remote }
    public var field: String
    public var local: String
    public var remote: String
    public var winner: Winner
}

struct CompletionState: Equatable {
    var isCompleted: Bool
    var percent: Int
}

extension TodoItem {
    var completionState: CompletionState {
        get { CompletionState(isCompleted: isCompleted, percent: percentComplete) }
        set { isCompleted = newValue.isCompleted; percentComplete = newValue.percent }
    }
}

/// Feldweiser Dreiwege-Abgleich (Spezifikation, Abschnitt 4, „Konflikte“).
///
/// - Hat sich ein Feld nur auf einer Seite gegenüber `base` geändert, gilt diese Änderung.
/// - Haben beide Seiten dasselbe Feld unterschiedlich geändert, gewinnt die Fassung mit dem neueren
///   `LAST-MODIFIED` (bei Gleichstand die lokale). Die unterlegene Fassung wird als Konflikt gemeldet.
enum TodoMerge {
    struct Result {
        var merged: TodoItem
        var conflicts: [FieldConflict]
    }

    static func merge(base: TodoItem?, local: TodoItem, remote: TodoItem) -> Result {
        var merged = remote
        var conflicts: [FieldConflict] = []
        let localWins = (local.lastModified ?? .distantPast) >= (remote.lastModified ?? .distantPast)

        func field<V: Equatable>(_ name: String, _ get: (TodoItem) -> V, _ set: (inout TodoItem, V) -> Void,
                                 describe: (V) -> String = { "\($0)" }) {
            let l = get(local), r = get(remote)
            if l == r { return }
            if let base {
                let b = get(base)
                if l == b { return }
                if r == b { set(&merged, l); return }
            }
            if localWins { set(&merged, l) }
            conflicts.append(FieldConflict(field: name, local: describe(l), remote: describe(r), winner: localWins ? .local : .remote))
        }

        func day(_ d: ICalDate?) -> String { d?.valueString ?? "–" }

        field("title", { $0.title }, { $0.title = $1 })
        field("notes", { $0.notes }, { $0.notes = $1 })
        field("url", { $0.url }, { $0.url = $1 }, describe: { $0 ?? "–" })
        field("start", { $0.start }, { $0.start = $1 }, describe: day)
        field("due", { $0.due }, { $0.due = $1 }, describe: day)
        field("completion", { $0.completionState }, { $0.completionState = $1 },
              describe: { $0.isCompleted ? "erledigt" : "offen (\($0.percent) %)" })
        field("priority", { $0.priority }, { $0.priority = $1 })
        field("tags", { $0.tags }, { $0.tags = $1 }, describe: { $0.joined(separator: ", ") })
        field("parent", { $0.parentUID }, { $0.parentUID = $1 }, describe: { $0 ?? "–" })
        field("sortOrder", { $0.sortOrder }, { $0.sortOrder = $1 }, describe: { $0.map(String.init) ?? "–" })
        field("color", { $0.color }, { $0.color = $1 }, describe: { $0 ?? "–" })
        field("reminders", { $0.reminders }, { $0.reminders = $1 }, describe: { "\($0.count) Erinnerung(en)" })
        field("attachments", { $0.attachments }, { $0.attachments = $1 }, describe: { "\($0.count) Anhang/Anhänge" })
        field("recurrence", { $0.recurrence }, { $0.recurrence = $1 }, describe: { $0 ?? "–" })
        field("planwerk", { $0.planwerk }, { $0.planwerk = $1 },
              describe: { $0.map { "\($0.kind.rawValue)/\($0.group ?? "–")/\($0.assignee ?? "–")" } ?? "–" })
        return Result(merged: merged, conflicts: conflicts)
    }
}
