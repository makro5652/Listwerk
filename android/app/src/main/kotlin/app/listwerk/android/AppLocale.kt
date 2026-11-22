package app.listwerk.android

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import app.listwerk.core.CoreText
import java.util.Locale

/**
 * Sprache der Oberfläche: Englisch (Standard) oder Deutsch, unabhängig von der Systemsprache.
 * Ab Android 13 über die App-Sprache des Systems (erscheint auch in den Systemeinstellungen der App),
 * davor über einen Kontext mit eigener Konfiguration in jeder Activity und in der Application.
 */
object AppLocale {
    const val EN = "en"
    const val DE = "de"
    private const val KEY = "language"
    private const val KEY_APPLIED = "language_applied"

    private fun prefs(ctx: Context) = ctx.applicationContext?.let { it.getSharedPreferences("ui", Context.MODE_PRIVATE) }
        ?: ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun stored(ctx: Context): String = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(KEY, EN) ?: EN

    private fun normalize(lang: String?): String = if (lang?.startsWith(DE) == true) DE else EN

    /** Gerade wirksame Sprache der App. */
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val l = ctx.getSystemService(LocaleManager::class.java).applicationLocales
            normalize(if (!l.isEmpty) l[0].language else Locale.getDefault().language)
        } else {
            stored(ctx)
        }

    /** Vor Android 13: Kontext in der gewählten Sprache (in attachBaseContext). Ab 13 unverändert. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = Locale.forLanguageTag(stored(base))
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }

    /** Beim App-Start: ab Android 13 einmalig Englisch als App-Sprache setzen; Kerntexte anpassen. */
    fun init(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val p = prefs(app)
            if (!p.getBoolean(KEY_APPLIED, false)) {
                app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(stored(app))
                p.edit().putBoolean(KEY_APPLIED, true).apply()
            }
        }
        CoreText.german = current(app) == DE
    }

    /** Aus den Einstellungen: Sprache wechseln und die Oberfläche neu aufbauen. */
    fun set(activity: Activity, lang: String) {
        prefs(activity).edit().putString(KEY, lang).putBoolean(KEY_APPLIED, true).apply()
        CoreText.german = lang == DE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Das System baut die Activities selbst neu auf.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
        } else {
            activity.recreate()
        }
    }
}
