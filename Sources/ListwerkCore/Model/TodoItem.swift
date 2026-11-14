import Foundation

public enum PlanwerkKind: String, Sendable, CaseIterable, Codable {
    case task, milestone, experiment

    /// Tag, den Planwerk zusätzlich in `CATEGORIES` führt.
    public var categoryTag: String? {
        switch self {
        case .task: return nil
        case .milestone: return "Meilenstein"
        case .experiment: return "Experiment"
        }
    }
}

/// Felder, die Planwerk in verknüpften Listen verwaltet (siehe Spezifikation, Abschnitt 8).
public struct PlanwerkMeta: Equatable, Sendable, Codable {
    public var kind: PlanwerkKind
    public var group: String?
    public var assignee: String?

    public init(kind: PlanwerkKind = .task, group: String? = nil, assignee: String? = nil) {
        self.kind = kind
        self.group = group
        self.assignee = assignee
    }
}

public struct Reminder: Equatable, Sendable, Identifiable {
    public enum Trigger: Equatable, Sendable {
        case absolute(Date)
        /// Sekunden relativ zu Beginn bzw. Fälligkeit; negativ = davor.
        case relative(TimeInterval, toEnd: Bool)
    }

    public let id: UUID
    public var trigger: Trigger
    public var message: String?
    public var action: String
    /// Originalzeilen des VALARM, solange die Erinnerung unverändert ist. Erhält fremde Felder.
    var rawLines: [String]?

    public init(trigger: Trigger, message: String? = nil, action: String = "DISPLAY") {
        self.id = UUID()
        self.trigger = trigger
        self.message = message
        self.action = action
        self.rawLines = nil
    }

    public static func == (a: Reminder, b: Reminder) -> Bool {
        a.trigger == b.trigger && a.message == b.message && a.action == b.action
    }

    /// Wird nur lokal angezeigt (DISPLAY/AUDIO). E-Mail-Erinnerungen bleiben erhalten, lösen aber nichts aus.
    public var firesLocally: Bool { action.uppercased() != "EMAIL" }
}

public struct AttachmentRef: Equatable, Sendable, Identifiable {
    public var id: String { uri.isEmpty ? (filename ?? "inline") : uri }
    public var uri: String
    public var mimeType: String?
    public var filename: String?
    /// Eingebettete Base64-Anhänge anderer Clients: werden angezeigt und erhalten, nie selbst erzeugt.
    public var isInline: Bool
    var raw: ContentLine?

    public init(uri: String, mimeType: String? = nil, filename: String? = nil) {
        self.uri = uri
        self.mimeType = mimeType
        self.filename = filename
        self.isInline = false
        self.raw = nil
    }

    public static func == (a: AttachmentRef, b: AttachmentRef) -> Bool {
        a.uri == b.uri && a.mimeType == b.mimeType && a.filename == b.filename && a.isInline == b.isInline
    }
}

/// Ein Eintrag der To-do-Liste, unabhängig vom Dateiformat.
public struct TodoItem: Equatable, Identifiable, Sendable {
    public var uid: String
    public var title: String
    public var notes: String
    public var url: String?
    public var start: ICalDate?
    public var due: ICalDate?
    public var isCompleted: Bool
    public var completedAt: Date?
    public var percentComplete: Int
    /// iCalendar-Priorität: 0 = keine, 1–4 hoch, 5 mittel, 6–9 niedrig.
    public var priority: Int
    public var tags: [String]
    public var parentUID: String?
    public var sortOrder: Int?
    /// `#RRGGBB`
    public var color: String?
    public var reminders: [Reminder]
    public var attachments: [AttachmentRef]
    /// Wert von RRULE, unverändert bis Meilenstein 6.
    public var recurrence: String?
    public var planwerk: PlanwerkMeta?
    public var created: Date?
    public var lastModified: Date?

    public var id: String { uid }

    public init(
        uid: String = UUID().uuidString.lowercased(),
        title: String = "",
        notes: String = "",
        url: String? = nil,
        start: ICalDate? = nil,
        due: ICalDate? = nil,
        isCompleted: Bool = false,
        completedAt: Date? = nil,
        percentComplete: Int = 0,
        priority: Int = 0,
        tags: [String] = [],
        parentUID: String? = nil,
        sortOrder: Int? = nil,
        color: String? = nil,
        reminders: [Reminder] = [],
        attachments: [AttachmentRef] = [],
        recurrence: String? = nil,
        planwerk: PlanwerkMeta? = nil,
        created: Date? = nil,
        lastModified: Date? = nil
    ) {
        self.uid = uid
        self.title = title
        self.notes = notes
        self.url = url
        self.start = start
        self.due = due
        self.isCompleted = isCompleted
        self.completedAt = completedAt
        self.percentComplete = percentComplete
        self.priority = priority
        self.tags = tags
        self.parentUID = parentUID
        self.sortOrder = sortOrder
        self.color = color
        self.reminders = reminders
        self.attachments = attachments
        self.recurrence = recurrence
        self.planwerk = planwerk
        self.created = created
        self.lastModified = lastModified
    }
}

public enum TodoPriority: Int, CaseIterable, Sendable {
    case none = 0, high = 1, medium = 5, low = 9

    public init(icalValue: Int) {
        switch icalValue {
        case 1...4: self = .high
        case 5: self = .medium
        case 6...9: self = .low
        default: self = .none
        }
    }
}
