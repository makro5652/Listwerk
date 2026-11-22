package app.listwerk.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Ein Planwerk-Projekt, wie `GET /api/state` es liefert (Spalten der Tabelle `projects`). */
data class PlanwerkProject(
    val id: Int,
    val name: String,
    val color: String?,
    val archived: Boolean?,
    /** Volle URL der verknüpften Nextcloud-Aufgabenliste, z. B. `https://cloud.example.org/remote.php/dav/calendars/nutzer/projekt-alpha/`. */
    val ncCalendar: String?,
    val ncCalendarName: String?,
)

data class PlanwerkGroup(val id: Int, val projectId: Int, val name: String)

data class PlanwerkMember(val id: Int, val name: String)

data class PlanwerkState(
    val rev: Int,
    val projects: List<PlanwerkProject>,
    val groups: List<PlanwerkGroup>,
    val members: List<PlanwerkMember>,
)

/** Verknüpfung einer Nextcloud-Liste mit einem Planwerk-Projekt. */
data class PlanwerkLink(
    val projectID: Int,
    val projectName: String,
    val projectColor: String?,
    /** Serverpfad der Liste (prozentkodiert), vergleichbar mit [TaskList.href]. */
    val listPath: String,
    val groups: List<String>,
    val people: List<String>,
)

/** Schnittstelle zu Planwerk. Ohne konfigurierten Server ist die App vollständig nutzbar. */
interface PlanwerkConnector {
    suspend fun fetchState(): PlanwerkState
    suspend fun currentRevision(): Int
    suspend fun triggerNextcloudSync()
}

/** Projekte mit Nextcloud-Liste, samt den Gruppen- und Personennamen als Auswahlwerte. */
suspend fun PlanwerkConnector.linkedLists(): List<PlanwerkLink> {
    val state = fetchState()
    val people = state.members.map { it.name }.sorted()
    return state.projects.mapNotNull { p ->
        val url = p.ncCalendar
        if (p.archived == true || url == null) return@mapNotNull null
        val raw = runCatching { URI(url).rawPath }.getOrNull() ?: return@mapNotNull null
        val path = if (raw.endsWith("/")) raw else "$raw/"
        PlanwerkLink(
            projectID = p.id, projectName = p.name, projectColor = p.color, listPath = path,
            groups = state.groups.filter { it.projectId == p.id }.map { it.name },
            people = people,
        )
    }
}

internal object PlanwerkJson {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    fun parseState(text: String): PlanwerkState {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("kein Objekt")
        return PlanwerkState(
            rev = root["rev"].int() ?: throw IllegalArgumentException("rev fehlt"),
            projects = root["projects"].arr().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val archived = o["archived"].let { a ->
                    // SQLite liefert 0/1; ältere Stände könnten true/false senden.
                    (a as? JsonPrimitive)?.booleanOrNull ?: a.int()?.let { it != 0 }
                }
                PlanwerkProject(
                    id = o["id"].int() ?: return@mapNotNull null,
                    name = o["name"].str() ?: return@mapNotNull null,
                    color = o["color"].str(), archived = archived,
                    ncCalendar = o["nc_calendar"].str(), ncCalendarName = o["nc_calendar_name"].str(),
                )
            },
            groups = root["groups"].arr().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                PlanwerkGroup(o["id"].int() ?: return@mapNotNull null, o["project_id"].int() ?: return@mapNotNull null, o["name"].str() ?: return@mapNotNull null)
            },
            members = root["members"].arr().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                PlanwerkMember(o["id"].int() ?: return@mapNotNull null, o["name"].str() ?: return@mapNotNull null)
            },
        )
    }

    fun parseRev(text: String): Int =
        ((json.parseToJsonElement(text) as? JsonObject)?.get("rev") as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("rev fehlt")
}

/** Planwerk über die REST-API, angemeldet mit einem Sync-Schlüssel (`Authorization: Bearer pwk_…`). */
class PlanwerkClient(
    baseUrl: String,
    private val token: String,
    private val transport: HttpTransport,
) : PlanwerkConnector {
    private val base = baseUrl.trimEnd('/')

    init {
        if (!base.startsWith("https://", ignoreCase = true)) throw DavError.InvalidResponse(CoreText.t("Nur HTTPS-Adressen sind erlaubt", "Only HTTPS addresses are allowed"))
    }

    private fun request(method: String, path: String) = HttpRequest(
        method, "$base/$path",
        mapOf("Authorization" to "Bearer $token", "Accept" to "application/json", "User-Agent" to "Listwerk/${ListwerkCoreInfo.VERSION}"),
    )

    private suspend fun perform(req: HttpRequest): String {
        val resp = transport.send(req)
        if (resp.status == 401) throw DavError.Unauthorized
        if (resp.status !in 200..299) throw DavError.Http(resp.status, resp.bodyText.take(200))
        return resp.bodyText
    }

    override suspend fun fetchState(): PlanwerkState {
        val text = perform(request("GET", "api/state"))
        return try { PlanwerkJson.parseState(text) } catch (e: Exception) {
            throw DavError.InvalidResponse("Planwerk-Antwort nicht lesbar: ${e.message}")
        }
    }

    override suspend fun currentRevision(): Int {
        val text = perform(request("GET", "api/rev"))
        return try { PlanwerkJson.parseRev(text) } catch (e: Exception) {
            throw DavError.InvalidResponse("Planwerk-Antwort nicht lesbar: ${e.message}")
        }
    }

    override suspend fun triggerNextcloudSync() {
        perform(request("POST", "api/nextcloud/sync"))
    }
}

/** Merkt sich die zuletzt gesehene Revision und lädt `/api/state` nur bei Änderungen (höchstens einmal pro [minInterval]). */
class PlanwerkLinkCache(
    private val connector: PlanwerkConnector,
    private val minInterval: Duration = Duration.ofSeconds(60),
) {
    private val mutex = Mutex()
    private var lastRev: Int? = null
    private var links: List<PlanwerkLink> = emptyList()
    private var lastCheck: Instant = Instant.MIN

    suspend fun current(now: Instant = Instant.now()): List<PlanwerkLink> = mutex.withLock {
        if (lastRev != null && Duration.between(lastCheck, now) < minInterval) return@withLock links
        lastCheck = now
        val rev = connector.currentRevision()
        if (rev != lastRev) {
            links = connector.linkedLists()
            lastRev = rev
        }
        links
    }

    /** Markiert Listen als Planwerk-verknüpft (Pfadvergleich mit den lokalen Listen). */
    suspend fun apply(store: Store, now: Instant = Instant.now()) {
        val paths = current(now).map { it.listPath }.toSet()
        for (l in store.lists()) {
            val linked = l.href in paths
            if (linked != l.isPlanwerkLinked) store.setPlanwerkLinked(l.href, linked)
        }
    }

    /** Verknüpfung einer Liste, falls vorhanden (für Auswahlwerte Gruppe/Zugewiesen). */
    suspend fun link(listHref: String, now: Instant = Instant.now()): PlanwerkLink? = current(now).firstOrNull { it.listPath == listHref }
}
