import Foundation

public struct ThemeColors: Codable, Equatable, Sendable {
    public var background: String
    public var sidebar: String
    public var surface: String
    public var surfaceElevated: String
    public var textPrimary: String
    public var textSecondary: String
    public var accent: String
    public var separator: String
    public var success: String
    public var warning: String
    public var danger: String
}

/// Ein Design als Satz semantischer Farbrollen. Ansichten verwenden nur diese Rollen.
public struct Theme: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var isDark: Bool
    public var colors: ThemeColors
    public var license: String?
}

public struct EntryColorOption: Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    /// Dieser Wert wird im Eintrag gespeichert (`#rrggbb`).
    public var storedHex: String
}

public struct EntryPalette: Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var colors: [EntryColorOption]
}

public enum ThemeCatalog {
    public static let systemID = "system"
    public static let defaultLightID = "papier"
    public static let defaultDarkID = "graphit"

    private struct CatppuccinFile: Decodable {
        struct C: Decodable { let id: String; let name: String; let latte: String; let frappe: String; let macchiato: String; let mocha: String }
        let id: String; let name: String; let colors: [C]
    }

    private struct KartenFile: Decodable {
        struct C: Decodable { let id: String; let name: String; let hex: String; let hexDark: String }
        let id: String; let name: String; let colors: [C]
    }

    private static func json(_ sub: String) -> [Data] {
        guard let dir = Bundle.module.resourceURL?.appendingPathComponent(sub, isDirectory: true),
              let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [] }
        return names.filter { $0.hasSuffix(".json") }.sorted()
            .compactMap { try? Data(contentsOf: dir.appendingPathComponent($0)) }
    }

    /// Alle mitgelieferten Designs (ohne „System“, das je nach Gerät Hell oder Dunkel wählt).
    public static let all: [Theme] = {
        let order = ["papier", "graphit", "catppuccin-latte", "catppuccin-frappe", "catppuccin-macchiato", "catppuccin-mocha"]
        let themes = json("themes").compactMap { try? JSONDecoder().decode(Theme.self, from: $0) }
        return themes.sorted { (order.firstIndex(of: $0.id) ?? 99) < (order.firstIndex(of: $1.id) ?? 99) }
    }()

    public static func theme(id: String) -> Theme? { all.first { $0.id == id } }

    /// Löst „System“ auf und fällt bei unbekannten Kennungen auf Papier/Graphit zurück.
    public static func resolve(id: String, systemIsDark: Bool) -> Theme {
        if id != systemID, let t = theme(id: id) { return t }
        return theme(id: systemIsDark ? defaultDarkID : defaultLightID) ?? all[0]
    }

    // MARK: Eintragsfarben

    private static let catppuccin: CatppuccinFile? = json("palettes").lazy
        .compactMap { try? JSONDecoder().decode(CatppuccinFile.self, from: $0) }.first { $0.id == "catppuccin" }

    private static let karten: KartenFile? = json("palettes").lazy
        .compactMap { try? JSONDecoder().decode(KartenFile.self, from: $0) }.first { $0.id == "farbkarten" }

    public static var palettes: [EntryPalette] {
        var out: [EntryPalette] = []
        if let c = catppuccin {
            out.append(EntryPalette(id: c.id, name: c.name, colors: c.colors.map { EntryColorOption(id: $0.id, name: $0.name, storedHex: $0.latte) }))
        }
        if let k = karten {
            out.append(EntryPalette(id: k.id, name: k.name, colors: k.colors.map { EntryColorOption(id: $0.id, name: $0.name, storedHex: $0.hex) }))
        }
        return out
    }

    /// Farbe, wie sie im aktuellen Design dargestellt wird. Gespeichert ist immer der Hellwert;
    /// in dunklen Designs wird derselbe Farbton in der passenden Variante gezeigt. Unbekannte Werte
    /// (z. B. von anderen Clients) bleiben unverändert.
    public static func displayHex(stored: String, in theme: Theme) -> String {
        let s = stored.lowercased()
        if let c = catppuccin?.colors.first(where: { $0.latte.lowercased() == s }) {
            if theme.id.hasPrefix("catppuccin-") {
                switch theme.id {
                case "catppuccin-latte": return c.latte
                case "catppuccin-frappe": return c.frappe
                case "catppuccin-macchiato": return c.macchiato
                default: return c.mocha
                }
            }
            return theme.isDark ? c.mocha : c.latte
        }
        if let k = karten?.colors.first(where: { $0.hex.lowercased() == s }) {
            return theme.isDark ? k.hexDark : k.hex
        }
        return stored
    }
}

/// Kontrast nach WCAG 2.x.
public enum Contrast {
    public static func luminance(_ hex: String) -> Double {
        guard let (r, g, b) = CSSColorNames.rgb(hex) else { return 0 }
        func lin(_ v: Int) -> Double {
            let c = Double(v) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    public static func ratio(_ a: String, _ b: String) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
