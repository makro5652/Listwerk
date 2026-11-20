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

    /** Start-Bildschirm erledigt: angemeldet oder „Ohne Anmeldung nutzen“ gewählt. Bestehende Installationen gelten als erledigt. */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", signedIn)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Darstellung: [LAYOUT_LISTWERK] oder [LAYOUT_TODO] (wie Microsoft To Do). */
    var layout: String
        get() = p.getString("layout", LAYOUT_LISTWERK) ?: LAYOUT_LISTWERK
        set(v) = p.edit().putString("layout", v).apply()

    /** Sortierung der Einträge, siehe [app.listwerk.android.ui.SortMode]. */
    var sortMode: String
        get() = p.getString("sort", "MANUAL") ?: "MANUAL"
        set(v) = p.edit().putString("sort", v).apply()

    /** UIDs in „Mein Tag“; gilt nur für den Tag [myDayDate] (wie in To Do beginnt jeder Tag leer). Nur auf diesem Gerät. */
    var myDay: Set<String>
        get() = if (p.getString("my_day_date", null) == java.time.LocalDate.now().toString()) p.getStringSet("my_day", emptySet()).orEmpty() else emptySet()
        set(v) = p.edit().putString("my_day_date", java.time.LocalDate.now().toString()).putStringSet("my_day", v).apply()

    /** Nach dem Übernehmen lokaler Listen ins Konto: gemerkte Hrefs umschreiben. */
    fun remapLists(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        val e = p.edit()
        for (key in listOf("last_list", "last_writable", "start_list")) p.getString(key, null)?.let { old -> moved[old]?.let { e.putString(key, it) } }
        e.apply()
        val w = widgets.edit()
        for ((k, v) in widgets.all) if (v is String) moved[v]?.let { w.putString(k, it) }
        w.apply()
    }

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
        p.edit().remove("last_list").remove("start_list").remove("last_writable").remove("last_sync").remove("planwerk_links").remove("reminders_fired_until").putBoolean("signed_in", false).putBoolean("onboarded", false).apply()
    }

    companion object {
        const val ALL = "*"

        // Intelligente Listen wie in Microsoft To Do. Beginnen wie [ALL] mit „*“: nie ein gültiger Href.
        const val MY_DAY = "*myday"
        const val IMPORTANT = "*important"
        const val PLANNED = "*planned"
        const val COMPLETED = "*completed"
        fun isSmart(selection: String) = selection.startsWith("*")

        const val LAYOUT_LISTWERK = "listwerk"
        const val LAYOUT_TODO = "todo"

        /** Platzhalter für „zuletzt benutzte Liste“ (kein gültiger Href). */
        const val START_LAST = "@last"
    }
}
