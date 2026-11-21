import Foundation

/// Die einfachen Wiederholungen wie in Microsoft To Do (täglich, werktags, wöchentlich, monatlich, jährlich, je mit
/// Intervall). Andere RRULEs werden nur durchgereicht. Gleiche Regeln wie die Android-Fassung (`Recurrence.kt`).
public enum Recurrence {
    public static let daily = "FREQ=DAILY"
    public static let weekdays = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    public static let weekly = "FREQ=WEEKLY"
    public static let monthly = "FREQ=MONTHLY"
    public static let yearly = "FREQ=YEARLY"

    // Gregorianische Wochentage: 1 = Sonntag.
    private static let days = ["SU": 1, "MO": 2, "TU": 3, "WE": 4, "TH": 5, "FR": 6, "SA": 7]

    /// Nächster Tag nach `from` (Kalendertag in `calendar`); nil, wenn die Regel nicht unterstützt wird oder abgelaufen ist.
    public static func next(_ rrule: String, from: Date, calendar: Calendar = .current) -> Date? {
        var p: [String: String] = [:]
        for part in rrule.split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { p[kv[0].uppercased()] = kv[1] }
        }
        guard Set(p.keys).isSubset(of: ["FREQ", "INTERVAL", "BYDAY", "UNTIL", "WKST"]) else { return nil }
        let n = max(1, Int(p["INTERVAL"] ?? "") ?? 1)
        var byDay: Set<Int>?
        if let b = p["BYDAY"] {
            var set = Set<Int>()
            for d in b.split(separator: ",") { guard let w = days[d.trimmingCharacters(in: .whitespaces).uppercased()] else { return nil }; set.insert(w) }
            byDay = set
        }
        let start = calendar.startOfDay(for: from)
        var next: Date?
        switch p["FREQ"]?.uppercased() {
        case "DAILY": next = calendar.date(byAdding: .day, value: n, to: start)
        case "WEEKLY":
            if let byDay, !byDay.isEmpty {
                var d = calendar.date(byAdding: .day, value: 1, to: start)!
                while !byDay.contains(calendar.component(.weekday, from: d)) {
                    d = calendar.date(byAdding: .day, value: 1, to: d)!
                    // Nach dem Wochenende ggf. n-1 Wochen überspringen.
                    if calendar.component(.weekday, from: d) == 2, n > 1 { d = calendar.date(byAdding: .weekOfYear, value: n - 1, to: d)! }
                }
                next = d
            } else { next = calendar.date(byAdding: .weekOfYear, value: n, to: start) }
        case "MONTHLY": next = calendar.date(byAdding: .month, value: n, to: start)
        case "YEARLY": next = calendar.date(byAdding: .year, value: n, to: start)
        default: return nil
        }
        guard let next else { return nil }
        if let u = p["UNTIL"], u.count >= 8 {
            let f = DateFormatter(); f.calendar = calendar; f.timeZone = calendar.timeZone; f.dateFormat = "yyyyMMdd"
            if let until = f.date(from: String(u.prefix(8))), next > until { return nil }
        }
        return next
    }
}
