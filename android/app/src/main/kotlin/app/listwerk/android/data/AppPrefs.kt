package app.listwerk.android.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import app.listwerk.core.PlanwerkLink

/**
 * Kleine Einstellungen, die beim Start synchron und ohne Datenbank verfügbar sein müssen (Design, letzte Liste).
 * Enthält keine Zugangsdaten.
 */
class AppPrefs(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val widgets: SharedPreferences = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    var themeId: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()

    /** Href der zuletzt genutzten Liste, [ALL] für „Alle offenen“, null = noch keine. */
    var lastList: String?
        get() = p.getString("last_list", null)
        set(v) = p.edit().putString("last_list", v).apply()

    /** Beim Start zu öffnende Liste: [START_LAST] (zuletzt benutzte), [ALL] oder der Href einer Liste. */
    var startList: String
        get() = p.getString("start_list", START_LAST) ?: START_LAST
        set(v) = p.edit().putString("start_list", v).apply()

    /** Zuletzt gewählte Liste, in die geschrieben werden darf (Ziel des Eingabefelds unter „Alle offenen“). */
    var lastWritableList: String?
        get() = p.getString("last_writable", null)
        set(v) = p.edit().putString("last_writable", v).apply()

    var showCompleted: Boolean
        get() = p.getBoolean("show_completed", false)
        set(v) = p.edit().putBoolean("show_completed", v).apply()

    var focusInputOnStart: Boolean
        get() = p.getBoolean("focus_input", true)
        set(v) = p.edit().putBoolean("focus_input", v).apply()

    /** Wurde schon einmal ein Konto eingerichtet? Entscheidet beim Start sofort zwischen Anmeldung und Listen. */
    var signedIn: Boolean
        get() = p.getBoolean("signed_in", false)
        set(v) = p.edit().putBoolean("signed_in", v).apply()

    var planwerkUrl: String
        get() = p.getString("planwerk_url", "") ?: ""
        set(v) = p.edit().putString("planwerk_url", v.trim()).apply()

    /** Zeitpunkt (ms), bis zu dem Erinnerungen bereits ausgelöst wurden. */
    var remindersFiredUntil: Long
        get() = p.getLong("reminders_fired_until", 0L)
        set(v) = p.edit().putLong("reminders_fired_until", v).apply()

    var lastSyncAt: Long
        get() = p.getLong("last_sync", 0L)
        set(v) = p.edit().putLong("last_sync", v).apply()

    // Planwerk-Verknüpfungen (Gruppen, Personen) für die Offline-Anzeige zwischenspeichern.
    var planwerkLinksJson: String
        get() = p.getString("planwerk_links", "") ?: ""
        set(v) = p.edit().putString("planwerk_links", v).apply()

    fun savePlanwerkLinks(links: List<PlanwerkLink>) {
        val arr = JSONArray()
        for (l in links) {
            arr.put(
                JSONObject()
                    .put("id", l.projectID).put("name", l.projectName).put("color", l.projectColor)
                    .put("path", l.listPath).put("groups", JSONArray(l.groups)).put("people", JSONArray(l.people)),
            )
        }
        planwerkLinksJson = arr.toString()
    }

    fun loadPlanwerkLinks(): List<PlanwerkLink> = try {
        val arr = JSONArray(planwerkLinksJson.ifEmpty { "[]" })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            PlanwerkLink(
                o.getInt("id"), o.getString("name"), if (o.isNull("color")) null else o.getString("color"),
                o.getString("path"), strings(o.getJSONArray("groups")), strings(o.getJSONArray("people")),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // Widget-Konfiguration: je Widget eine Liste (href) oder [ALL].
    fun widgetList(appWidgetId: Int): String = widgets.getString("list_$appWidgetId", ALL) ?: ALL
    fun setWidgetList(appWidgetId: Int, href: String) = widgets.edit().putString("list_$appWidgetId", href).apply()
    fun removeWidget(appWidgetId: Int) = widgets.edit().remove("list_$appWidgetId").apply()

    fun clearAccountData() {
        p.edit().remove("last_list").remove("start_list").remove("last_writable").remove("last_sync").remove("planwerk_links").remove("reminders_fired_until").putBoolean("signed_in", false).apply()
    }

    companion object {
        const val ALL = "*"

        /** Platzhalter für „zuletzt benutzte Liste“ (kein gültiger Href). */
        const val START_LAST = "@last"
    }
}
