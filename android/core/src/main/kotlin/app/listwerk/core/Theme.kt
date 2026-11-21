package app.listwerk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

@Serializable
data class ThemeColors(
    val background: String,
    val sidebar: String,
    val surface: String,
    val surfaceElevated: String,
    val textPrimary: String,
    val textSecondary: String,
    val accent: String,
    val separator: String,
    val success: String,
    val warning: String,
    val danger: String,
)

/** Ein Design als Satz semantischer Farbrollen. Ansichten verwenden nur diese Rollen. */
@Serializable
data class Theme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val colors: ThemeColors,
    val license: String? = null,
)

data class EntryColorOption(
    val id: String,
    val name: String,
    /** Dieser Wert wird im Eintrag gespeichert (`#rrggbb`). */
    val storedHex: String,
)

data class EntryPalette(val id: String, val name: String, val colors: List<EntryColorOption>)

@Serializable
private class CatppuccinFile(val id: String, val name: String, val colors: List<C>) {
    @Serializable
    class C(val id: String, val name: String, val latte: String, val frappe: String, val macchiato: String, val mocha: String)
}

@Serializable
private class KartenFile(val id: String, val name: String, val colors: List<C>) {
    @Serializable
    class C(val id: String, val name: String, val hex: String, val hexDark: String)
}

/**
 * Designs und Eintragsfarben, geladen aus den JSON-Dateien von `shared/themes` und `shared/palettes`.
 * Die App liefert die Dateiinhalte aus ihren Assets, die Tests aus den Ressourcen.
 */
class ThemeCatalog(themeJson: List<String>, paletteJson: List<String>) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Alle mitgelieferten Designs (ohne „System“, das je nach Gerät Hell oder Dunkel wählt). */
    val all: List<Theme> = run {
        val order = listOf("papier", "graphit", "catppuccin-latte", "catppuccin-frappe", "catppuccin-macchiato", "catppuccin-mocha", "aqua")
        themeJson.mapNotNull { runCatching { json.decodeFromString<Theme>(it) }.getOrNull() }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
    }

    private val catppuccin: CatppuccinFile? = paletteJson.firstNotNullOfOrNull {
        runCatching { json.decodeFromString<CatppuccinFile>(it) }.getOrNull()?.takeIf { f -> f.id == "catppuccin" }
    }

    private val karten: KartenFile? = paletteJson.firstNotNullOfOrNull {
        runCatching { json.decodeFromString<KartenFile>(it) }.getOrNull()?.takeIf { f -> f.id == "farbkarten" }
    }

    fun theme(id: String): Theme? = all.firstOrNull { it.id == id }

    /** Löst „System“ auf und fällt bei unbekannten Kennungen auf Papier/Graphit zurück. */
    fun resolve(id: String, systemIsDark: Boolean): Theme {
        if (id != SYSTEM_ID) theme(id)?.let { return it }
        return theme(if (systemIsDark) DEFAULT_DARK_ID else DEFAULT_LIGHT_ID) ?: all[0]
    }

    val palettes: List<EntryPalette> by lazy {
        buildList {
            catppuccin?.let { c -> add(EntryPalette(c.id, c.name, c.colors.map { EntryColorOption(it.id, it.name, it.latte) })) }
            karten?.let { k -> add(EntryPalette(k.id, k.name, k.colors.map { EntryColorOption(it.id, it.name, it.hex) })) }
        }
    }

    /**
     * Farbe, wie sie im aktuellen Design dargestellt wird. Gespeichert ist immer der Hellwert;
     * in dunklen Designs wird derselbe Farbton in der passenden Variante gezeigt. Unbekannte Werte
     * (z. B. von anderen Clients) bleiben unverändert.
     */
    fun displayHex(stored: String, theme: Theme): String {
        val s = stored.lowercase()
        catppuccin?.colors?.firstOrNull { it.latte.lowercase() == s }?.let { c ->
            if (theme.id.startsWith("catppuccin-")) {
                return when (theme.id) {
                    "catppuccin-latte" -> c.latte
                    "catppuccin-frappe" -> c.frappe
                    "catppuccin-macchiato" -> c.macchiato
                    else -> c.mocha
                }
            }
            return if (theme.isDark) c.mocha else c.latte
        }
        karten?.colors?.firstOrNull { it.hex.lowercase() == s }?.let { k -> return if (theme.isDark) k.hexDark else k.hex }
        return stored
    }

    companion object {
        const val SYSTEM_ID = "system"
        const val DEFAULT_LIGHT_ID = "papier"
        const val DEFAULT_DARK_ID = "graphit"
    }
}

/** Kontrast nach WCAG 2.x. */
object Contrast {
    fun luminance(hex: String): Double {
        val (r, g, b) = CSSColorNames.rgb(hex) ?: return 0.0
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    fun ratio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
