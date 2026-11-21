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

// MARK: Aqua (angelehnt an Mac OS X): Nadelstreifen und Gel-Kugeln. Die Farben stehen in themes/aqua.json.

extension Theme {
    var isAqua: Bool { id == "aqua" }
}

/// Hintergrund einer Ansicht; im Aqua-Design mit Nadelstreifen.
struct ThemeBackground: View {
    @Environment(\.theme) private var theme

    var body: some View {
        if theme.isAqua {
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(theme.color(\.background)))
                var y: CGFloat = 0
                while y < size.height {
                    ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 2)), with: .color(.white.opacity(0.6)))
                    y += 4
                }
            }
        } else {
            theme.color(\.background)
        }
    }
}

/// Abhak-Kreis; im Aqua-Design eine glänzende Gel-Kugel.
struct CheckCircle: View {
    @Environment(\.theme) private var theme
    let done: Bool
    let tint: Color
    var size: CGFloat = 18

    var body: some View {
        Group {
            if theme.isAqua {
                ZStack {
                    if done {
                        Circle().fill(tint)
                        Circle().fill(LinearGradient(colors: [.white.opacity(0.5), .clear, .black.opacity(0.15)], startPoint: .top, endPoint: .bottom))
                    } else {
                        Circle().fill(LinearGradient(colors: [.white, Color(hex: "#dcdcdc")], startPoint: .top, endPoint: .bottom))
                    }
                    Circle().strokeBorder(done ? tint.opacity(0.8) : Color(hex: "#9a9a9a"), lineWidth: 1)
                    // Glanzlicht oben wie bei den Aqua-Knöpfen.
                    Capsule().fill(LinearGradient(colors: [.white.opacity(0.9), .white.opacity(0)], startPoint: .top, endPoint: .bottom))
                        .frame(width: size * 0.64, height: size * 0.32)
                        .offset(y: -size * 0.24)
                    if done { Image(systemName: "checkmark").font(.system(size: size * 0.5, weight: .bold)).foregroundStyle(.white) }
                }
                .frame(width: size, height: size)
            } else {
                Image(systemName: done ? "checkmark.circle.fill" : "circle").foregroundStyle(tint)
            }
        }
        .accessibilityLabel(done ? "Wieder öffnen" : "Erledigen")
    }
}
