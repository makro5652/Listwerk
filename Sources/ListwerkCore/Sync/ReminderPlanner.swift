import Foundation

/// Eine anstehende lokale Benachrichtigung.
public struct PlannedNotification: Equatable, Sendable {
    public var id: String          // stabil: "<liste>|<uid>|<index>"
    public var fireDate: Date
    public var title: String
    public var body: String
    public var listHref: String
    public var uid: String
}

/// Berechnet aus den Einträgen, was als Nächstes ausgelöst werden soll. Rein rechnerisch, ohne Systemzugriff;
/// die Plattformen übergeben das Ergebnis an `UNUserNotificationCenter` bzw. `AlarmManager`.
public enum ReminderPlanner {
    /// iPadOS erlaubt höchstens 64 geplante lokale Benachrichtigungen; geplant werden die zeitlich nächsten.
    public static let systemLimit = 64

    public static func plan(records: [TaskRecord], now: Date, limit: Int = systemLimit, timeZone: TimeZone = .current) -> [PlannedNotification] {
        var out: [PlannedNotification] = []
        for rec in records where !rec.isDeleted && !rec.item.isCompleted {
            for (i, r) in rec.item.reminders.enumerated() where r.firesLocally {
                guard let fire = fireDate(for: r, item: rec.item, timeZone: timeZone), fire > now else { continue }
                out.append(PlannedNotification(
                    id: "\(rec.listHref)|\(rec.item.uid)|\(i)",
                    fireDate: fire,
                    title: rec.item.title.isEmpty ? "Erinnerung" : rec.item.title,
                    body: rec.item.notes.split(separator: "\n").first.map(String.init) ?? "",
                    listHref: rec.listHref,
                    uid: rec.item.uid
                ))
            }
        }
        return Array(out.sorted { $0.fireDate < $1.fireDate }.prefix(limit))
    }

    public static func fireDate(for r: Reminder, item: TodoItem, timeZone: TimeZone = .current) -> Date? {
        switch r.trigger {
        case .absolute(let d):
            return d
        case .relative(let offset, let toEnd):
            // Ganztägige Fälligkeit ohne Uhrzeit: Bezugspunkt ist 09:00 Ortszeit des Tages.
            guard let anchor = (toEnd ? item.due : item.start) ?? (toEnd ? item.start : item.due) else { return nil }
            return base(anchor, timeZone: timeZone).addingTimeInterval(offset)
        }
    }

    private static func base(_ d: ICalDate, timeZone: TimeZone) -> Date {
        guard d.isAllDay else { return d.date(in: timeZone) }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal.date(from: DateComponents(year: d.year, month: d.month, day: d.day, hour: 9)) ?? d.date(in: timeZone)
    }
}
