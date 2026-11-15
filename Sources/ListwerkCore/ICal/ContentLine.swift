import Foundation

/// Parameter einer iCalendar-Zeile, z. B. `TZID=Africa/Nairobi`. Der Wert bleibt roh (mit Anführungszeichen),
/// damit das Zurückschreiben verlustfrei ist.
public struct ICalParam: Equatable, Hashable, Sendable {
    public var name: String
    public var value: String

    public init(name: String, value: String) {
        self.name = name.uppercased()
        self.value = value
    }

    public var unquotedValue: String {
        if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
            return String(value.dropFirst().dropLast())
        }
        return value
    }
}

/// Eine entfaltete iCalendar-Inhaltszeile: `NAME;PARAM=x:WERT`.
public struct ContentLine: Equatable, Hashable, Sendable {
    public var name: String
    public var params: [ICalParam]
    /// Wert so, wie er in der Datei steht (Text-Escapes unverändert).
    public var value: String

    public init(name: String, params: [ICalParam] = [], value: String) {
        self.name = name.uppercased()
        self.params = params
        self.value = value
    }

    public func param(_ name: String) -> String? {
        let n = name.uppercased()
        return params.first { $0.name == n }?.unquotedValue
    }

    /// Zerlegt eine entfaltete Zeile. Doppelpunkte und Semikolons in Anführungszeichen trennen nicht.
    public init?(parsing line: String) {
        var inQuotes = false
        var colon: String.Index?
        var idx = line.startIndex
        while idx < line.endIndex {
            let ch = line[idx]
            if ch == "\"" { inQuotes.toggle() }
            if ch == ":" && !inQuotes { colon = idx; break }
            idx = line.index(after: idx)
        }
        guard let colon, colon != line.startIndex else { return nil }
        let left = line[line.startIndex..<colon]
        let rawValue = String(line[line.index(after: colon)...])

        var parts: [String] = []
        var current = ""
        inQuotes = false
        for ch in left {
            if ch == "\"" { inQuotes.toggle() }
            if ch == ";" && !inQuotes {
                parts.append(current)
                current = ""
            } else {
                current.append(ch)
            }
        }
        parts.append(current)
        guard let first = parts.first, !first.isEmpty else { return nil }

        var params: [ICalParam] = []
        for p in parts.dropFirst() {
            if let eq = p.firstIndex(of: "=") {
                params.append(ICalParam(name: String(p[p.startIndex..<eq]), value: String(p[p.index(after: eq)...])))
            } else {
                params.append(ICalParam(name: p, value: ""))
            }
        }
        self.init(name: first, params: params, value: rawValue)
    }

    /// Die Zeile ohne Faltung.
    public var unfolded: String {
        var s = name
        for p in params {
            s += ";" + p.name + (p.value.isEmpty ? "" : "=" + p.value)
        }
        return s + ":" + value
    }
}

// MARK: - Text, Faltung

public enum ICalText {
    /// Escaping für TEXT-Werte nach RFC 5545 §3.3.11.
    public static func escape(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var prevCR = false
        for u in s.unicodeScalars {
            switch u {
            case "\\": out.append(contentsOf: "\\\\".unicodeScalars)
            case ";": out.append(contentsOf: "\\;".unicodeScalars)
            case ",": out.append(contentsOf: "\\,".unicodeScalars)
            case "\n":
                if !prevCR { out.append(contentsOf: "\\n".unicodeScalars) }
            case "\r": out.append(contentsOf: "\\n".unicodeScalars)
            default: out.append(u)
            }
            prevCR = (u == "\r")
        }
        return String(out)
    }

    public static func unescape(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        var it = s.unicodeScalars.makeIterator()
        while let u = it.next() {
            if u == "\\" {
                guard let n = it.next() else { out.append(u); break }
                switch n {
                case "n", "N": out.append("\n")
                case "\\", ";", ",": out.append(n)
                default:
                    out.append(u)
                    out.append(n)
                }
            } else {
                out.append(u)
            }
        }
        return String(out)
    }

    /// Teilt an nicht maskierten Kommas (für CATEGORIES) und entfernt die Escapes.
    public static func splitList(_ s: String) -> [String] {
        var items: [String] = []
        var current = String.UnicodeScalarView()
        var escaped = false
        for u in s.unicodeScalars {
            if escaped {
                current.append("\\")
                current.append(u)
                escaped = false
            } else if u == "\\" {
                escaped = true
            } else if u == "," {
                items.append(unescape(String(current)))
                current = String.UnicodeScalarView()
            } else {
                current.append(u)
            }
        }
        if escaped { current.append("\\") }
        items.append(unescape(String(current)))
        return items.filter { !$0.isEmpty }
    }

    /// Entfaltet den Dateitext in logische Zeilen (CRLF oder LF, Fortsetzung mit Leerzeichen oder Tab).
    public static func unfold(_ text: String) -> [String] {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        var lines: [String] = []
        for raw in normalized.split(separator: "\n", omittingEmptySubsequences: false) {
            if let first = raw.first, first == " " || first == "\t", !lines.isEmpty {
                lines[lines.count - 1] += raw.dropFirst()
            } else {
                lines.append(String(raw))
            }
        }
        while let last = lines.last, last.isEmpty { lines.removeLast() }
        return lines
    }

    /// Faltet eine Zeile auf höchstens 75 Oktette, ohne UTF-8-Zeichen zu zerteilen. Endet mit CRLF.
    public static func fold(_ line: String) -> String {
        let bytes = Array(line.utf8)
        if bytes.count <= 75 { return line + "\r\n" }
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count + bytes.count / 70 * 3 + 2)
        var i = 0
        var limit = 75
        while i < bytes.count {
            var end = min(i + limit, bytes.count)
            while end < bytes.count && end > i + 1 && (bytes[end] & 0xC0) == 0x80 { end -= 1 }
            out.append(contentsOf: bytes[i..<end])
            i = end
            if i < bytes.count {
                out.append(contentsOf: [13, 10, 32])
                limit = 74
            }
        }
        out.append(contentsOf: [13, 10])
        return String(decoding: out, as: UTF8.self)
    }
}
