import Foundation

/// Datum oder Zeitpunkt aus einem iCalendar-Wert. Die Bestandteile bleiben erhalten, damit ein Datum
/// ohne Uhrzeit (`VALUE=DATE`) und ein Zeitpunkt mit `TZID` unverändert zurückgeschrieben werden.
public struct ICalDate: Equatable, Hashable, Sendable {
    public enum Kind: Equatable, Hashable, Sendable {
        case day
        case floating
        case utc
        case zoned(String)
    }

    public var year: Int
    public var month: Int
    public var day: Int
    public var hour: Int
    public var minute: Int
    public var second: Int
    public var kind: Kind

    public init(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, kind: Kind) {
        self.year = year; self.month = month; self.day = day
        self.hour = hour; self.minute = minute; self.second = second
        self.kind = kind
    }

    public static func day(_ y: Int, _ m: Int, _ d: Int) -> ICalDate {
        ICalDate(year: y, month: m, day: d, kind: .day)
    }

    public var isAllDay: Bool { kind == .day }

    // MARK: Parsen

    /// `value` ist der Rohwert (`20261120`, `20261120T140000`, `20261120T140000Z`), `params` die Zeilenparameter.
    public init?(value: String, params: [ICalParam]) {
        let v = value.trimmingCharacters(in: .whitespaces)
        let tzid = params.first { $0.name == "TZID" }?.unquotedValue
        let isDateType = params.first { $0.name == "VALUE" }?.unquotedValue.uppercased() == "DATE"
        let chars = Array(v.utf8)

        func num(_ from: Int, _ len: Int) -> Int? {
            guard from + len <= chars.count else { return nil }
            var n = 0
            for b in chars[from..<(from + len)] {
                guard b >= 48 && b <= 57 else { return nil }
                n = n * 10 + Int(b - 48)
            }
            return n
        }

        guard let y = num(0, 4), let mo = num(4, 2), let d = num(6, 2) else { return nil }
        if chars.count == 8 || isDateType {
            self.init(year: y, month: mo, day: d, kind: .day)
            return
        }
        guard chars.count >= 15, chars[8] == 84 /* T */,
              let h = num(9, 2), let mi = num(11, 2), let s = num(13, 2) else { return nil }
        let isUTC = chars.count >= 16 && chars[15] == 90 /* Z */
        let kind: Kind = isUTC ? .utc : (tzid.map { .zoned($0) } ?? .floating)
        self.init(year: y, month: mo, day: d, hour: h, minute: mi, second: s, kind: kind)
    }

    // MARK: Schreiben

    public var valueString: String {
        let date = String(format: "%04d%02d%02d", year, month, day)
        switch kind {
        case .day: return date
        case .utc: return date + String(format: "T%02d%02d%02dZ", hour, minute, second)
        case .floating, .zoned: return date + String(format: "T%02d%02d%02d", hour, minute, second)
        }
    }

    public var params: [ICalParam] {
        switch kind {
        case .day: return [ICalParam(name: "VALUE", value: "DATE")]
        case .zoned(let tz): return [ICalParam(name: "TZID", value: tz)]
        case .utc, .floating: return []
        }
    }

    public func line(named name: String) -> ContentLine {
        ContentLine(name: name, params: params, value: valueString)
    }

    // MARK: Umrechnung

    public func date(in fallback: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian)
        switch kind {
        case .utc: cal.timeZone = TimeZone(identifier: "UTC")!
        case .zoned(let id): cal.timeZone = TimeZone(identifier: id) ?? fallback
        case .day, .floating: cal.timeZone = fallback
        }
        let comps = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        return cal.date(from: comps) ?? Date(timeIntervalSince1970: 0)
    }

    /// Ein Zeitpunkt aus der Oberfläche. Mit Uhrzeit wird UTC geschrieben, das ist für alle Clients eindeutig.
    public init(date: Date, allDay: Bool, in tz: TimeZone = .current) {
        var cal = Calendar(identifier: .gregorian)
        if allDay {
            cal.timeZone = tz
            let c = cal.dateComponents([.year, .month, .day], from: date)
            self.init(year: c.year!, month: c.month!, day: c.day!, kind: .day)
        } else {
            cal.timeZone = TimeZone(identifier: "UTC")!
            let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
            self.init(year: c.year!, month: c.month!, day: c.day!,
                      hour: c.hour!, minute: c.minute!, second: c.second!, kind: .utc)
        }
    }

    /// Sortierschlüssel für die Datenbank: Tage vor Zeitpunkten desselben Tages, sonst chronologisch.
    public var sortKey: String {
        switch kind {
        case .day:
            return String(format: "%04d-%02d-%02d", year, month, day)
        default:
            let d = date()
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = TimeZone(identifier: "UTC")!
            let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: d)
            return String(format: "%04d-%02d-%02dT%02d:%02d:%02dZ", c.year!, c.month!, c.day!, c.hour!, c.minute!, c.second!)
        }
    }

    /// Kalendertag in der Ortszeit des Geräts (für „Heute“, „Überfällig“).
    public func localDay(in tz: TimeZone = .current) -> (year: Int, month: Int, day: Int) {
        if kind == .day { return (year, month, day) }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let c = cal.dateComponents([.year, .month, .day], from: date(in: tz))
        return (c.year!, c.month!, c.day!)
    }
}

// MARK: - UTC-Zeitstempel, Dauer

public enum ICalStamp {
    private static let utc = TimeZone(identifier: "UTC")!

    public static func string(_ date: Date) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = utc
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return String(format: "%04d%02d%02dT%02d%02d%02dZ", c.year!, c.month!, c.day!, c.hour!, c.minute!, c.second!)
    }

    public static func parse(_ s: String) -> Date? {
        guard let d = ICalDate(value: s, params: []), d.kind != .day else { return nil }
        return d.date()
    }
}

public enum ICalDuration {
    /// `-P1D`, `PT15M`, `P1W`, `-P1DT2H30M` → Sekunden (negativ = vor dem Bezugspunkt).
    public static func parse(_ s: String) -> TimeInterval? {
        var chars = Substring(s.trimmingCharacters(in: .whitespaces))
        var sign = 1.0
        if chars.hasPrefix("-") { sign = -1; chars = chars.dropFirst() } else if chars.hasPrefix("+") { chars = chars.dropFirst() }
        guard chars.hasPrefix("P") else { return nil }
        chars = chars.dropFirst()
        var total = 0.0
        var inTime = false
        var number = ""
        for ch in chars {
            if ch == "T" { inTime = true; continue }
            if ch.isNumber { number.append(ch); continue }
            guard let n = Double(number) else { return nil }
            number = ""
            switch (ch, inTime) {
            case ("W", false): total += n * 604_800
            case ("D", false): total += n * 86_400
            case ("H", true): total += n * 3_600
            case ("M", true): total += n * 60
            case ("S", true): total += n
            default: return nil
            }
        }
        return number.isEmpty ? sign * total : nil
    }

    public static func format(_ seconds: TimeInterval) -> String {
        let neg = seconds < 0
        var rest = Int(abs(seconds).rounded())
        if rest == 0 { return "PT0S" }
        let days = rest / 86_400; rest %= 86_400
        let hours = rest / 3_600; rest %= 3_600
        let minutes = rest / 60; rest %= 60
        var out = neg ? "-P" : "P"
        if days > 0 { out += "\(days)D" }
        if hours > 0 || minutes > 0 || rest > 0 {
            out += "T"
            if hours > 0 { out += "\(hours)H" }
            if minutes > 0 { out += "\(minutes)M" }
            if rest > 0 { out += "\(rest)S" }
        }
        return out
    }
}
