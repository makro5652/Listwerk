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

    /// Bearbeitbare Form einer Regel (Oberfläche „Benutzerdefiniert“). Nur was `next` auch ausrechnen kann.
    public struct Rule: Equatable, Sendable {
        public enum Freq: String, CaseIterable, Sendable { case daily = "DAILY", weekly = "WEEKLY", monthly = "MONTHLY", yearly = "YEARLY" }
        public var freq: Freq
        public var interval: Int
        /// RRULE-Kürzel MO … SU, nur bei wöchentlich.
        public var byDay: [String]
        /// Letzter Tag (einschließlich).
        public var until: Date?

        public init(freq: Freq, interval: Int = 1, byDay: [String] = [], until: Date? = nil) {
            self.freq = freq; self.interval = interval; self.byDay = byDay; self.until = until
        }

        public static let dayOrder = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]

        /// nil bei Regeln, die Listwerk nur durchreicht (z. B. BYMONTHDAY, COUNT).
        public init?(_ rrule: String) {
            var p: [String: String] = [:]
            for part in rrule.split(separator: ";") {
                let kv = part.split(separator: "=", maxSplits: 1).map(String.init)
                guard kv.count == 2 else { return nil }
                p[kv[0].uppercased()] = kv[1]
            }
            guard Set(p.keys).isSubset(of: ["FREQ", "INTERVAL", "BYDAY", "UNTIL", "WKST"]),
                  let f = p["FREQ"].flatMap({ Freq(rawValue: $0.uppercased()) }) else { return nil }
            let days = p["BYDAY"]?.split(separator: ",").map { $0.uppercased() } ?? []
            guard days.allSatisfy(Self.dayOrder.contains), days.isEmpty || f == .weekly else { return nil }
            var until: Date?
            if let u = p["UNTIL"] {
                let c = Array(u.prefix(8))
                guard c.count == 8, let y = Int(String(c[0..<4])), let m = Int(String(c[4..<6])), let d = Int(String(c[6..<8])) else { return nil }
                until = Calendar.current.date(from: DateComponents(year: y, month: m, day: d))
            }
            self.init(freq: f, interval: max(1, Int(p["INTERVAL"] ?? "") ?? 1),
                      byDay: Self.dayOrder.filter(days.contains), until: until)
        }

        public var rrule: String {
            var s = "FREQ=\(freq.rawValue)"
            if interval > 1 { s += ";INTERVAL=\(interval)" }
            if freq == .weekly, !byDay.isEmpty { s += ";BYDAY=" + Self.dayOrder.filter(byDay.contains).joined(separator: ",") }
            if let until {
                let c = Calendar.current.dateComponents([.year, .month, .day], from: until)
                s += String(format: ";UNTIL=%04d%02d%02d", c.year!, c.month!, c.day!)
            }
            return s
        }

        /// Kurze Wochentagsnamen in `dayOrder`-Reihenfolge, in der Sprache der App (Mo … So / Mon … Sun).
        public static var shortDayNames: [String] { ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"].map { L($0) } }

        /// „Alle 2 Wochen am Mo, Mi bis 24.12.2026“ bzw. „Every 2 weeks on Mon, Wed until 12/24/2026“
        public var text: String {
            let names = Dictionary(uniqueKeysWithValues: zip(Self.dayOrder, Self.shortDayNames))
            let one = [Freq.daily: L("Täglich"), .weekly: L("Wöchentlich"), .monthly: L("Monatlich"), .yearly: L("Jährlich")][freq]!
            let many = [Freq.daily: L("Tage"), .weekly: L("Wochen"), .monthly: L("Monate"), .yearly: L("Jahre")][freq]!
            var s = interval == 1 ? one : L("Alle {0} {1}", interval, many)
            if !byDay.isEmpty { s += L(" am ") + byDay.compactMap { names[$0] }.joined(separator: ", ") }
            if let until { s += L(" bis ") + until.lwFormatted(date: .numeric, time: .omitted) }
            return s
        }
    }
}
