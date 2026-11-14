import SwiftUI
import ListwerkCore

extension Color {
    /// "#rrggbb" → Color. Ungültige Werte ergeben Grau.
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { self = .gray; return }
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue: Theme = ThemeCatalog.theme(id: "papier") ?? ThemeCatalog.all[0]
}

extension EnvironmentValues {
    var theme: Theme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

extension Theme {
    func color(_ keyPath: KeyPath<ThemeColors, String>) -> Color { Color(hex: colors[keyPath: keyPath]) }
}
