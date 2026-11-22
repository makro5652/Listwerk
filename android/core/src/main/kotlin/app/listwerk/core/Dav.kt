package app.listwerk.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import javax.xml.parsers.SAXParserFactory

// MARK: - Transport

/** Schmale HTTP-Schnittstelle. Die App liefert eine OkHttp-Implementierung, die Tests einen Mock oder den FakeServer. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val bodyText: String? get() = body?.toString(Charsets.UTF_8)
}

class HttpResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0)) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

interface HttpTransport {
    /** Wirft [DavError.Network] bei Verbindungsfehlern; HTTP-Statuscodes (auch 4xx/5xx) kommen als [HttpResponse]. */
    suspend fun send(request: HttpRequest): HttpResponse
}

// MARK: - Typen

data class RemoteList(
    val href: String,
    val name: String,
    val color: String? = null,
    val order: Double? = null,
    val ctag: String? = null,
    val syncToken: String? = null,
    val canWrite: Boolean = true,
    val isShared: Boolean = false,
    val ownerName: String? = null,
)

data class RemoteEntry(val href: String, val etag: String?)

data class RemoteResource(val href: String, val etag: String?, val ics: String)

data class RemoteChanges(
    val changed: List<RemoteEntry> = emptyList(),
    val removed: List<String> = emptyList(),
    val newToken: String? = null,
    /** Der Server kennt das gespeicherte Sync-Token nicht mehr; ein vollständiger Abgleich ist nötig. */
    val tokenInvalid: Boolean = false,
)

sealed interface PutResult {
    /** `etag` fehlt, wenn der Server die Daten verändert gespeichert hat; dann muss neu geladen werden. */
    data class Ok(val etag: String?) : PutResult
    data object PreconditionFailed : PutResult
}

enum class DeleteResult { OK, NOT_FOUND, PRECONDITION_FAILED }

sealed interface MoveResult {
    data object Ok : MoveResult
    data class Failed(val status: Int) : MoveResult
}

sealed class DavError(message: String) : Exception(message) {
    data object Unauthorized : DavError("401") {
        private fun readResolve(): Any = Unauthorized
        override val message: String get() = CoreText.t("Anmeldung abgelehnt (401)", "Sign-in rejected (401)")
    }

    data class Http(val status: Int, val detail: String) : DavError("HTTP $status: $detail")
    data class InvalidResponse(val reason: String) : DavError(reason)
    data class Network(val reason: String) : DavError(reason)
}

data class Discovery(val principal: String, val home: String)

data class CollectionState(val ctag: String?, val syncToken: String?)

/** Alles, was die Sync-Engine vom Server braucht. Pfade sind Serverpfade (`/remote.php/dav/...`). */
interface CalDAVService {
    suspend fun discover(): Discovery
    suspend fun listCollections(home: String): List<RemoteList>

    /** Billige Abfrage von CTag und Sync-Token einer Liste. */
    suspend fun collectionState(list: String): CollectionState
    suspend fun changes(list: String, since: String?): RemoteChanges
    suspend fun listAll(list: String): List<RemoteEntry>
    suspend fun fetch(list: String, hrefs: List<String>): List<RemoteResource>

    /** `etag == null` und `create == true`: `If-None-Match: *`. Sonst `If-Match: etag`. */
    suspend fun put(href: String, ics: String, etag: String?, create: Boolean): PutResult
    suspend fun delete(href: String, etag: String?): DeleteResult
    suspend fun move(from: String, to: String): MoveResult
    suspend fun makeCollection(href: String, name: String, color: String?)
    suspend fun updateCollection(href: String, name: String?, color: String?, order: Double? = null)
    suspend fun deleteCollection(href: String)
}

// MARK: - XML

internal object NS {
    const val DAV = "DAV:"
    const val CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val APPLE = "http://apple.com/ns/ical/"
    const val CS = "http://calendarserver.org/ns/"
}

/** Minimaler XML-Baum mit Namensräumen, genug für WebDAV-Antworten. */
internal class XmlNode(val ns: String, val name: String) {
    val attributes = HashMap<String, String>()
    val text = StringBuilder()
    val children = ArrayList<XmlNode>()

    fun child(ns: String, name: String): XmlNode? = children.firstOrNull { it.ns == ns && it.name == name }
    fun children(ns: String, name: String): List<XmlNode> = children.filter { it.ns == ns && it.name == name }
    val trimmedText: String get() = text.toString().trim()

    companion object {
        fun parse(data: ByteArray): XmlNode {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            // Keine DTDs/externen Entitäten: Serverantworten sind nicht vertrauenswürdig (nicht jede Plattform kennt die Schalter).
            for (f in listOf("http://apache.org/xml/features/disallow-doctype-decl")) {
                try { factory.setFeature(f, true) } catch (_: Exception) { }
            }
            val builder = Builder()
            try {
                val parser = factory.newSAXParser()
                parser.parse(InputSource(ByteArrayInputStream(data)), builder)
            } catch (e: Exception) {
                throw DavError.InvalidResponse("XML nicht lesbar: ${e.message ?: "unbekannt"}")
            }
            return builder.root ?: throw DavError.InvalidResponse("XML nicht lesbar: leer")
        }
    }

    private class Builder : DefaultHandler() {
        var root: XmlNode? = null
        val stack = ArrayList<XmlNode>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val local = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: ""
            val node = XmlNode(uri ?: "", local)
            if (attributes != null) {
                for (i in 0 until attributes.length) {
                    val n = attributes.getLocalName(i).takeIf { it.isNotEmpty() } ?: attributes.getQName(i)
                    node.attributes[n] = attributes.getValue(i)
                }
            }
            stack.lastOrNull()?.children?.add(node) ?: run { root = node }
            stack.add(node)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.text?.append(ch, start, length)
        }
    }
}

/** Eine `<response>` aus einem Multistatus. */
internal class DavResponse(
    val href: String,
    /** Status direkt an der Response (z. B. 404 bei gelöschten Ressourcen im sync-collection-Report). */
    val status: Int?,
    /** Properties mit Status 2xx. */
    val props: XmlNode?,
) {
    fun prop(ns: String, name: String): XmlNode? = props?.child(ns, name)
}

internal class Multistatus(val responses: List<DavResponse>, val syncToken: String?) {
    companion object {
        fun parse(data: ByteArray): Multistatus {
            val root = XmlNode.parse(data)
            if (root.ns != NS.DAV || root.name != "multistatus") throw DavError.InvalidResponse("Kein Multistatus: ${root.name}")
            val out = ArrayList<DavResponse>()
            for (r in root.children(NS.DAV, "response")) {
                val hrefNode = r.child(NS.DAV, "href") ?: continue
                val combined = XmlNode(NS.DAV, "prop")
                for (ps in r.children(NS.DAV, "propstat")) {
                    val code = ps.child(NS.DAV, "status")?.let { statusCode(it.trimmedText) } ?: 200
                    val p = ps.child(NS.DAV, "prop")
                    if (code in 200..299 && p != null) combined.children.addAll(p.children)
                }
                out.add(DavResponse(normalizeHref(hrefNode.trimmedText), r.child(NS.DAV, "status")?.let { statusCode(it.trimmedText) }, combined))
            }
            return Multistatus(out, root.child(NS.DAV, "sync-token")?.trimmedText)
        }

        /** `HTTP/1.1 207 Multi-Status` → 207 */
        fun statusCode(line: String): Int? {
            val parts = line.split(' ').filter { it.isNotEmpty() }
            return if (parts.size >= 2) parts[1].toIntOrNull() else null
        }

        /** Absolute URLs werden auf den (prozentkodierten) Pfad gekürzt. */
        fun normalizeHref(href: String): String {
            if (href.startsWith("http://") || href.startsWith("https://")) {
                return try { URI(href).rawPath ?: href } catch (_: Exception) { href }
            }
            return href
        }
    }
}

internal object XmlText {
    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

// MARK: - CalDAV-Client

/** CalDAV-Client für Nextcloud (sabre/dav). Spricht Basic-Anmeldung mit App-Passwort; der Transport erzwingt HTTPS. */
class CalDAVClient(
    val serverUrl: String,
    private val loginName: String,
    private val appPassword: String,
    private val transport: HttpTransport,
) : CalDAVService {
    private val userAgent = "Listwerk/${ListwerkCoreInfo.VERSION}"
    private val base: URI = URI(serverUrl)

    init {
        // Zugangsdaten gehen nur über verschlüsselte Verbindungen.
        if (!base.scheme.equals("https", ignoreCase = true)) throw DavError.InvalidResponse(CoreText.t("Nur HTTPS-Adressen sind erlaubt", "Only HTTPS addresses are allowed"))
    }

    /** Pfad zur DAV-Wurzel, auch wenn Nextcloud in einem Unterverzeichnis läuft. */
    internal val davRoot: String
        get() {
            var p = base.rawPath ?: ""
            if (!p.endsWith("/")) p += "/"
            return p + "remote.php/dav/"
        }

    private fun url(path: String): String {
        val authority = base.rawAuthority ?: throw DavError.InvalidResponse(CoreText.t("Ungültige Server-Adresse", "Invalid server address"))
        return "${base.scheme}://$authority$path"
    }

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): HttpRequest {
        val token = Base64.getEncoder().encodeToString("$loginName:$appPassword".toByteArray(Charsets.UTF_8))
        val h = LinkedHashMap<String, String>()
        h["Authorization"] = "Basic $token"
        h["User-Agent"] = userAgent
        h.putAll(headers)
        if (body != null && headers["Content-Type"] == null) h["Content-Type"] = "application/xml; charset=utf-8"
        return HttpRequest(method, url(path), h, body?.toByteArray(Charsets.UTF_8))
    }

    private suspend fun send(req: HttpRequest, accept: Set<Int>): HttpResponse {
        val resp = transport.send(req)
        if (resp.status == 401) throw DavError.Unauthorized
        if (resp.status !in accept) throw DavError.Http(resp.status, resp.bodyText.take(300))
        return resp
    }

    private suspend fun multistatus(method: String, path: String, depth: String, body: String, accept: Set<Int> = setOf(207)): Multistatus {
        val resp = send(request(method, path, mapOf("Depth" to depth), body), accept)
        return Multistatus.parse(resp.body)
    }

    // MARK: Entdeckung

    override suspend fun discover(): Discovery {
        val first = multistatus(
            "PROPFIND", davRoot, "0",
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""",
        )
        val principal = first.responses.firstNotNullOfOrNull { it.prop(NS.DAV, "current-user-principal")?.child(NS.DAV, "href")?.trimmedText }
            ?: throw DavError.InvalidResponse("current-user-principal fehlt")
        val principalPath = Multistatus.normalizeHref(principal)

        val second = multistatus(
            "PROPFIND", principalPath, "0",
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>""",
        )
        val home = second.responses.firstNotNullOfOrNull { it.prop(NS.CALDAV, "calendar-home-set")?.child(NS.DAV, "href")?.trimmedText }
            ?: throw DavError.InvalidResponse("calendar-home-set fehlt")
        return Discovery(principalPath, Multistatus.normalizeHref(home))
    }

    override suspend fun listCollections(home: String): List<RemoteList> {
        val ms = multistatus(
            "PROPFIND", home, "1",
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
  <d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><a:calendar-order/><cs:getctag/><d:sync-token/>
    <c:supported-calendar-component-set/><d:current-user-privilege-set/><d:owner/></d:prop>
</d:propfind>""",
        )
        val lists = ArrayList<RemoteList>()
        for (r in ms.responses) {
            if (r.prop(NS.DAV, "resourcetype")?.child(NS.CALDAV, "calendar") == null) continue
            val comps = r.prop(NS.CALDAV, "supported-calendar-component-set")?.children(NS.CALDAV, "comp")
                ?.mapNotNull { it.attributes["name"]?.uppercase() } ?: emptyList()
            if ("VTODO" !in comps) continue
            val privileges = r.prop(NS.DAV, "current-user-privilege-set")?.children(NS.DAV, "privilege")
                ?.flatMap { p -> p.children.map { it.name } }?.toSet() ?: emptySet()
            val canWrite = privileges.isEmpty() || "all" in privileges || "write" in privileges || "write-content" in privileges
            val owner = r.prop(NS.DAV, "owner")?.child(NS.DAV, "href")?.trimmedText
            val name = r.prop(NS.DAV, "displayname")?.trimmedText
            val href = if (r.href.endsWith("/")) r.href else r.href + "/"
            lists.add(
                RemoteList(
                    href = href,
                    name = if (!name.isNullOrEmpty()) name else href.split('/').lastOrNull { it.isNotEmpty() } ?: "Liste",
                    color = r.prop(NS.APPLE, "calendar-color")?.let { normalizeColor(it.trimmedText) },
                    order = r.prop(NS.APPLE, "calendar-order")?.trimmedText?.toDoubleOrNull(),
                    ctag = r.prop(NS.CS, "getctag")?.trimmedText,
                    syncToken = r.prop(NS.DAV, "sync-token")?.trimmedText,
                    canWrite = canWrite,
                    isShared = owner?.let { !home.startsWith(it) && it.isNotEmpty() && !ownerMatchesHome(it, home) } ?: false,
                    ownerName = owner?.split('/')?.lastOrNull { it.isNotEmpty() },
                ),
            )
        }
        return lists
    }

    override suspend fun collectionState(list: String): CollectionState {
        val ms = multistatus(
            "PROPFIND", list, "0",
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
<d:prop><cs:getctag/><d:sync-token/></d:prop></d:propfind>""",
        )
        val r = ms.responses.firstOrNull()
        return CollectionState(r?.prop(NS.CS, "getctag")?.trimmedText, r?.prop(NS.DAV, "sync-token")?.trimmedText)
    }

    // MARK: Änderungen holen

    override suspend fun changes(list: String, since: String?): RemoteChanges {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:sync-collection xmlns:d="DAV:">
<d:sync-token>${XmlText.escape(since ?: "")}</d:sync-token><d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>"""
        val resp = transport.send(request("REPORT", list, mapOf("Depth" to "0"), body))
        if (resp.status == 401) throw DavError.Unauthorized
        if (resp.status in listOf(403, 409, 412) && resp.bodyText.contains("valid-sync-token")) return RemoteChanges(tokenInvalid = true)
        if (resp.status != 207) throw DavError.Http(resp.status, resp.bodyText.take(300))
        val ms = Multistatus.parse(resp.body)
        val changed = ArrayList<RemoteEntry>()
        val removed = ArrayList<String>()
        for (r in ms.responses) {
            if (!r.href.lowercase().endsWith(".ics")) continue
            if (r.status == 404) removed.add(r.href) else changed.add(RemoteEntry(r.href, r.prop(NS.DAV, "getetag")?.trimmedText))
        }
        return RemoteChanges(changed, removed, ms.syncToken)
    }

    override suspend fun listAll(list: String): List<RemoteEntry> {
        val ms = multistatus(
            "PROPFIND", list, "1",
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>""",
        )
        return ms.responses.filter { it.href.lowercase().endsWith(".ics") }
            .map { RemoteEntry(it.href, it.prop(NS.DAV, "getetag")?.trimmedText) }
    }

    override suspend fun fetch(list: String, hrefs: List<String>): List<RemoteResource> {
        val out = ArrayList<RemoteResource>()
        for (chunk in hrefs.chunked(50)) {
            val items = chunk.joinToString("") { "<d:href>${XmlText.escape(it)}</d:href>" }
            val ms = multistatus(
                "REPORT", list, "1",
                """<?xml version="1.0" encoding="utf-8"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:prop><d:getetag/><c:calendar-data/></d:prop>$items</c:calendar-multiget>""",
            )
            for (r in ms.responses) {
                val ics = r.prop(NS.CALDAV, "calendar-data")?.text?.toString()
                if (ics.isNullOrEmpty()) continue
                out.add(RemoteResource(r.href, r.prop(NS.DAV, "getetag")?.trimmedText, ics))
            }
        }
        return out
    }

    // MARK: Schreiben

    override suspend fun put(href: String, ics: String, etag: String?, create: Boolean): PutResult {
        val headers = linkedMapOf("Content-Type" to "text/calendar; charset=utf-8")
        if (create) headers["If-None-Match"] = "*" else if (etag != null) headers["If-Match"] = etag
        val resp = transport.send(request("PUT", href, headers, ics))
        return when (resp.status) {
            200, 201, 204 -> PutResult.Ok(resp.header("ETag"))
            401 -> throw DavError.Unauthorized
            412 -> PutResult.PreconditionFailed
            else -> throw DavError.Http(resp.status, resp.bodyText.take(300))
        }
    }

    override suspend fun delete(href: String, etag: String?): DeleteResult {
        val resp = transport.send(request("DELETE", href, etag?.let { mapOf("If-Match" to it) } ?: emptyMap()))
        return when (resp.status) {
            200, 202, 204 -> DeleteResult.OK
            401 -> throw DavError.Unauthorized
            404, 410 -> DeleteResult.NOT_FOUND
            412 -> DeleteResult.PRECONDITION_FAILED
            else -> throw DavError.Http(resp.status, resp.bodyText.take(300))
        }
    }

    override suspend fun move(from: String, to: String): MoveResult {
        val resp = transport.send(request("MOVE", from, mapOf("Destination" to url(to), "Overwrite" to "F")))
        if (resp.status == 401) throw DavError.Unauthorized
        return if (resp.status in listOf(200, 201, 204)) MoveResult.Ok else MoveResult.Failed(resp.status)
    }

    override suspend fun makeCollection(href: String, name: String, color: String?) {
        val colorXml = color?.let { "<a:calendar-color>${XmlText.escape(it)}</a:calendar-color>" } ?: ""
        val req = request(
            "MKCALENDAR", href,
            body = """<?xml version="1.0" encoding="utf-8"?><c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
<d:set><d:prop><d:displayname>${XmlText.escape(name)}</d:displayname>$colorXml
<c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>""",
        )
        send(req, setOf(200, 201))
    }

    override suspend fun updateCollection(href: String, name: String?, color: String?, order: Double?) {
        var props = ""
        if (name != null) props += "<d:displayname>${XmlText.escape(name)}</d:displayname>"
        if (color != null) props += "<a:calendar-color>${XmlText.escape(color)}</a:calendar-color>"
        if (order != null) props += "<a:calendar-order>${order.toInt()}</a:calendar-order>"
        if (props.isEmpty()) return
        val req = request(
            "PROPPATCH", href,
            body = """<?xml version="1.0" encoding="utf-8"?><d:propertyupdate xmlns:d="DAV:" xmlns:a="http://apple.com/ns/ical/">
<d:set><d:prop>$props</d:prop></d:set></d:propertyupdate>""",
        )
        send(req, setOf(200, 207))
    }

    override suspend fun deleteCollection(href: String) {
        val resp = transport.send(request("DELETE", href))
        if (resp.status == 401) throw DavError.Unauthorized
        if (resp.status !in listOf(200, 204, 404)) throw DavError.Http(resp.status, CoreText.t("Liste konnte nicht gelöscht werden", "List could not be deleted"))
    }

    companion object {
        /** Eigene Listen: Der Principal-Name (`/principals/users/nutzer/`) steckt auch im Home-Pfad (`/calendars/nutzer/`). */
        private fun ownerMatchesHome(owner: String, home: String): Boolean {
            val name = owner.split('/').lastOrNull { it.isNotEmpty() } ?: return true
            return name in home.split('/').filter { it.isNotEmpty() }
        }

        /** `#1E66F5FF` → `#1e66f5`; unbekannte Formen werden verworfen. */
        internal fun normalizeColor(s: String): String? {
            var v = s.trim()
            if (!v.startsWith("#")) return null
            v = v.substring(1)
            if (v.length == 8) v = v.substring(0, 6)
            if (v.length != 6 || v.toIntOrNull(16) == null) return null
            return "#" + v.lowercase()
        }
    }
}

// MARK: - Login Flow v2

data class NextcloudCredentials(val serverUrl: String, val loginName: String, val appPassword: String)

/**
 * Nextcloud Login Flow v2: Der Nutzer meldet sich im Browser an, die App erhält ein App-Passwort.
 * Das Hauptpasswort sieht die App nie.
 */
class LoginFlowV2(private val transport: HttpTransport) {
    class Session internal constructor(val loginUrl: String, internal val pollEndpoint: String, internal val token: String)

    private val userAgent = "Listwerk/${ListwerkCoreInfo.VERSION}"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Poll(val token: String, val endpoint: String)

    @Serializable
    private class Start(val poll: Poll, val login: String)

    @Serializable
    private class Done(val server: String, val loginName: String, val appPassword: String)

    suspend fun start(server: String): Session {
        val base = server.trimEnd('/')
        val resp = transport.send(HttpRequest("POST", "$base/index.php/login/v2", mapOf("User-Agent" to userAgent)))
        if (resp.status != 200) throw DavError.Http(resp.status, CoreText.t("Login Flow v2 nicht verfügbar", "Login Flow v2 not available"))
        val s = try { json.decodeFromString<Start>(resp.bodyText) } catch (e: Exception) {
            throw DavError.InvalidResponse(CoreText.t("Ungültige Antwort im Login Flow", "Invalid response in Login Flow"))
        }
        if (s.login.isBlank() || s.poll.endpoint.isBlank()) throw DavError.InvalidResponse(CoreText.t("Ungültige Adressen im Login Flow", "Invalid addresses in Login Flow"))
        return Session(s.login, s.poll.endpoint, s.poll.token)
    }

    /** Null, solange der Nutzer die Anmeldung im Browser noch nicht bestätigt hat. */
    suspend fun poll(session: Session): NextcloudCredentials? {
        val resp = transport.send(
            HttpRequest(
                "POST", session.pollEndpoint,
                mapOf("User-Agent" to userAgent, "Content-Type" to "application/x-www-form-urlencoded"),
                "token=${session.token}".toByteArray(Charsets.UTF_8),
            ),
        )
        if (resp.status == 404) return null
        if (resp.status != 200) throw DavError.Http(resp.status, CoreText.t("Abfrage fehlgeschlagen", "Request failed"))
        val d = try { json.decodeFromString<Done>(resp.bodyText) } catch (e: Exception) {
            throw DavError.InvalidResponse(CoreText.t("Ungültige Antwort im Login Flow", "Invalid response in Login Flow"))
        }
        return NextcloudCredentials(d.server, d.loginName, d.appPassword)
    }

    /** Fragt alle [intervalMs] Millisekunden nach, höchstens [timeoutMs] lang (Nextcloud lässt den Flow 20 Minuten gelten). */
    suspend fun waitForCredentials(session: Session, intervalMs: Long = 2000, timeoutMs: Long = 1_200_000): NextcloudCredentials {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            poll(session)?.let { return it }
            delay(intervalMs)
        }
        throw DavError.Network(CoreText.t("Zeitüberschreitung bei der Anmeldung", "Sign-in timed out"))
    }
}
