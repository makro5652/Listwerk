package app.listwerk.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.listwerk.core.Contrast
import app.listwerk.core.Theme
import app.listwerk.core.ThemeCatalog

/** Zusätzliche Farbrollen des Designs, die Material 3 nicht kennt. */
@Immutable
class ListwerkColors(
    val theme: Theme,
    val catalog: ThemeCatalog,
    val sidebar: Color,
    val surfaceElevated: Color,
    val separator: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    val isDark: Boolean get() = theme.isDark

    /** Eintragsfarbe, wie sie im aktuellen Design erscheint (gespeichert ist der Hellwert). */
    fun entryColor(stored: String): Color = parseHex(catalog.displayHex(stored, theme))
}

val LocalListwerkColors = staticCompositionLocalOf<ListwerkColors> { error("ListwerkTheme fehlt") }

val MaterialTheme.listwerk: ListwerkColors
    @Composable @ReadOnlyComposable get() = LocalListwerkColors.current

fun parseHex(hex: String): Color {
    val s = hex.trim().removePrefix("#")
    val v = s.toLongOrNull(16) ?: return Color.Gray
    return when (s.length) {
        6 -> Color((0xFF000000 or v).toInt())
        8 -> Color(v.toInt())
        else -> Color.Gray
    }
}

private fun onColor(bg: String): Color = if (Contrast.ratio("#ffffff", bg) >= Contrast.ratio("#000000", bg)) Color.White else Color.Black

fun colorSchemeFor(t: Theme): ColorScheme {
    val c = t.colors
    val bg = parseHex(c.background)
    val accent = parseHex(c.accent)
    val text = parseHex(c.textPrimary)
    val container = lerp(bg, accent, 0.18f)
    val base = if (t.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onColor(c.accent),
        primaryContainer = container, onPrimaryContainer = text,
        secondary = accent, onSecondary = onColor(c.accent),
        secondaryContainer = container, onSecondaryContainer = text,
        tertiary = parseHex(c.success), onTertiary = onColor(c.success),
        background = bg, onBackground = text,
        surface = bg, onSurface = text,
        surfaceVariant = parseHex(c.sidebar), onSurfaceVariant = parseHex(c.textSecondary),
        surfaceTint = accent,
        surfaceContainerLowest = parseHex(c.surface),
        surfaceContainerLow = parseHex(c.surface),
        surfaceContainer = parseHex(c.surfaceElevated),
        surfaceContainerHigh = parseHex(c.surfaceElevated),
        surfaceContainerHighest = parseHex(c.sidebar),
        outline = parseHex(c.textSecondary), outlineVariant = parseHex(c.separator),
        error = parseHex(c.danger), onError = onColor(c.danger),
        errorContainer = lerp(bg, parseHex(c.danger), 0.18f), onErrorContainer = text,
    )
}

@Composable
fun ListwerkTheme(catalog: ThemeCatalog, themeId: String, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val theme = remember(catalog, themeId, dark) { catalog.resolve(themeId, dark) }
    val extra = remember(theme) {
        val c = theme.colors
        ListwerkColors(
            theme, catalog, parseHex(c.sidebar), parseHex(c.surfaceElevated), parseHex(c.separator),
            parseHex(c.success), parseHex(c.warning), parseHex(c.danger),
        )
    }
    val scheme = remember(theme) { colorSchemeFor(theme) }
    CompositionLocalProvider(LocalListwerkColors provides extra) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
