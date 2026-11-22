import Foundation

/// Sprache der Oberfläche. Standard: Englisch. Einstellbar unter Einstellungen → Darstellung → Sprache.
public enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case en, de
    public var id: String { rawValue }
    /// Name in der jeweiligen Sprache selbst (für den Sprachwähler).
    public var nativeName: String { self == .en ? "English" : "Deutsch" }
}

/// Einfache, plattformunabhängige Übersetzung: Schlüssel ist der deutsche Originaltext, die Tabelle liefert Englisch.
/// Fehlt ein Eintrag, erscheint der deutsche Text (prüft `scripts/check_translations.py`).
/// Platzhalter: `{0}`, `{1}`, … (Reihenfolge in der Übersetzung frei).
public enum L10n {
    nonisolated(unsafe) public static var language: AppLanguage = .en

    /// Gebietsschema für Datumsangaben: gewählte Sprache, Region vom System.
    public static var locale: Locale {
        let region = Locale.current.region?.identifier ?? (language == .en ? "US" : "DE")
        return Locale(identifier: "\(language.rawValue)_\(region)")
    }

    public static func tr(_ german: String, _ args: [Any] = []) -> String {
        var s = language == .de ? german : (L10nTable.en[german] ?? german)
        for (i, a) in args.enumerated() { s = s.replacingOccurrences(of: "{\(i)}", with: "\(a)") }
        return s
    }
}

/// Übersetzt einen deutschen Oberflächentext in die gewählte Sprache.
public func L(_ german: String, _ args: Any...) -> String { L10n.tr(german, args) }

extension Date {
    /// Wie `formatted(date:time:)`, aber in der Sprache der App.
    public func lwFormatted(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle) -> String {
        formatted(Date.FormatStyle(date: date, time: time, locale: L10n.locale, calendar: .current, timeZone: .current))
    }
}
