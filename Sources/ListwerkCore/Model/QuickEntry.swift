import Foundation

/// Schnelleingabe: „Elternbrief morgen 8 Uhr !“ → Titel, Fälligkeit, Wichtigkeit, Wiederholung.
/// Erkannte Wörter werden aus dem Titel entfernt. Versteht:
/// heute, morgen, übermorgen, (am/nächsten) Montag … Sonntag, 24.11., 24.11.2026, in 3 Tagen/Wochen/Monaten,
/// 8 Uhr, 8:30, 8.30 Uhr, um 14, „!“ (wichtig), täglich, werktags, wöchentlich, monatlich, jährlich,
/// jeden Tag/Montag, alle 2 Tage/Wochen/Monate/Jahre.
public struct QuickEntry: Equatable, Sendable {
    public var title: String
    public var due: ICalDate?
    public var important: Bool
    public var recurrence: String?

    private static let weekdays = ["sonntag": 1, "montag": 2, "dienstag": 3, "mittwoch": 4, "donnerstag": 5, "freitag": 6, "samstag": 7]
    private static let rruleDays = [1: "SU", 2: "MO", 3: "TU", 4: "WE", 5: "TH", 6: "FR", 7: "SA"]

    public static func parse(_ text: String, now: Date = Date(), calendar cal: Calendar = .current) -> QuickEntry {
        var words = text.split(whereSeparator: \.isWhitespace).map(String.init)
        var keep = [Bool](repeating: true, count: words.count)
        let today = cal.startOfDay(for: now)
        var day: Date?
        var time: (h: Int, m: Int)?
        var important = false
        var rule: String?

        func low(_ i: Int) -> String { i < words.count ? words[i].lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ",")) : "" }
        func drop(_ r: ClosedRange<Int>) { for k in r where k < keep.count { keep[k] = false } }
        func unit(_ w: String) -> (Calendar.Component, String)? {
            if w.hasPrefix("tag") { return (.day, "DAILY") }
            if w.hasPrefix("woche") { return (.weekOfYear, "WEEKLY") }
            if w.hasPrefix("monat") { return (.month, "MONTHLY") }
            if w.hasPrefix("jahr") { return (.year, "YEARLY") }
            return nil
        }
        /// Nächster Wochentag ab heute (heute zählt mit).
        func upcoming(_ weekday: Int) -> Date {
            let diff = (weekday - cal.component(.weekday, from: today) + 7) % 7
            return cal.date(byAdding: .day, value: diff, to: today)!
        }
        /// „8“, „8:30“, „8.30“, „14h“ → Uhrzeit, sonst nil.
        func clock(_ w: String) -> (Int, Int)? {
            var s = w
            if s.hasSuffix("uhr") { s.removeLast(3) } else if s.hasSuffix("h") { s.removeLast() }
            let parts = s.split(separator: s.contains(":") ? ":" : ".", omittingEmptySubsequences: false).map(String.init)
            guard (1...2).contains(parts.count), let h = Int(parts[0]), (0...23).contains(h), parts[0].count <= 2 else { return nil }
            let m = parts.count == 2 ? Int(parts[1]) : 0
            guard let m, (0...59).contains(m), parts.count == 1 || parts[1].count == 2 else { return nil }
            return (h, m)
        }

        var i = 0
        while i < words.count {
            let w = low(i)
            if w.allSatisfy({ $0 == "!" }) && !w.isEmpty { important = true; drop(i...i) }
            else if w == "heute" { day = today; drop(i...i) }
            else if w == "morgen" { day = cal.date(byAdding: .day, value: 1, to: today); drop(i...i) }
            else if w == "übermorgen" { day = cal.date(byAdding: .day, value: 2, to: today); drop(i...i) }
            else if let wd = weekdays[w] { day = upcoming(wd); drop(i...i) }
            else if ["täglich", "werktags", "wöchentlich", "monatlich", "jährlich"].contains(w) {
                rule = ["täglich": Recurrence.daily, "werktags": Recurrence.weekdays, "wöchentlich": Recurrence.weekly,
                        "monatlich": Recurrence.monthly, "jährlich": Recurrence.yearly][w]
                drop(i...i)
            }
            else if w == "jeden" || w == "jede" || w == "jedes" {
                let n = low(i + 1)
                if let wd = weekdays[n] { rule = "FREQ=WEEKLY;BYDAY=\(rruleDays[wd]!)"; day = day ?? upcoming(wd); drop(i...i + 1) }
                else if let (_, f) = unit(n) { rule = "FREQ=\(f)"; drop(i...i + 1) }
            }
            else if w == "alle", let n = Int(low(i + 1)), n > 0, let (_, f) = unit(low(i + 2)) {
                rule = n == 1 ? "FREQ=\(f)" : "FREQ=\(f);INTERVAL=\(n)"; drop(i...i + 2)
            }
            else if w == "in", let n = Int(low(i + 1)), n >= 0, let (c, _) = unit(low(i + 2)) {
                day = cal.date(byAdding: c, value: n, to: today); drop(i...i + 2)
            }
            else if let d = date(w, today: today, calendar: cal) { day = d; drop(i...i) }
            else if w == "um", let t = clock(low(i + 1)) {
                time = t; drop(i...i + 1)
                if low(i + 2) == "uhr" { drop(i + 2...i + 2) }
            }
            else if low(i + 1) == "uhr", let t = clock(w) { time = t; drop(i...i + 1) }
            else if w.contains(":") || w.hasSuffix("uhr"), let t = clock(w) { time = t; drop(i...i) }
            // „am“ und „nächsten“ nur vor einem erkannten Datum entfernen.
            if (w == "am" || w.hasPrefix("nächste")) && i + 1 < words.count {
                let n = low(i + 1)
                if weekdays[n] != nil || date(n, today: today, calendar: cal) != nil { keep[i] = false }
            }
            i += 1
        }

        if rule != nil, day == nil { day = today }
        var due: ICalDate?
        if var d = day ?? (time != nil ? today : nil) {
            if let t = time {
                d = cal.date(bySettingHour: t.h, minute: t.m, second: 0, of: d)!
                // Nur eine Uhrzeit, die heute schon vorbei ist: morgen.
                if day == nil, d <= now { d = cal.date(byAdding: .day, value: 1, to: d)! }
                due = ICalDate(date: d, allDay: false, in: cal.timeZone)
            } else {
                let c = cal.dateComponents([.year, .month, .day], from: d)
                due = .day(c.year!, c.month!, c.day!)
            }
        }
        words = zip(words, keep).filter(\.1).map(\.0)
        let title = words.joined(separator: " ")
        return QuickEntry(title: title.isEmpty ? text.trimmingCharacters(in: .whitespaces) : title,
                          due: due, important: important, recurrence: rule)
    }

    /// „24.11.“, „24.11.26“, „24.11.2026“. Ohne Jahr: das nächste solche Datum ab heute.
    private static func date(_ w: String, today: Date, calendar cal: Calendar) -> Date? {
        let p = w.split(separator: ".", omittingEmptySubsequences: false)
        guard (2...3).contains(p.count) || (p.count == 3 && p[2].isEmpty),
              let d = Int(p[0]), let m = Int(p[1]), (1...31).contains(d), (1...12).contains(m) else { return nil }
        var y = cal.component(.year, from: today)
        let explicitYear = p.count == 3 && !p[2].isEmpty
        if explicitYear {
            guard let yy = Int(p[2]) else { return nil }
            y = yy < 100 ? 2000 + yy : yy
        } else if p.count == 2 {
            return nil   // „24.11“ ohne Schlusspunkt ist eher eine Zahl oder Uhrzeit
        }
        guard var r = cal.date(from: DateComponents(year: y, month: m, day: d)),
              cal.component(.day, from: r) == d else { return nil }
        if !explicitYear, r < today { r = cal.date(byAdding: .year, value: 1, to: r)! }
        return r
    }
}
