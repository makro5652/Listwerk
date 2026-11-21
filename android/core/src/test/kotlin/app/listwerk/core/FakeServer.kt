package app.listwerk.core

import java.time.Instant

/**
 * In-Memory-CalDAV-Server mit ETags, Sync-Tokens und Tombstones. Verhält sich wie sabre/dav in den
 * Punkten, auf die sich die Sync-Engine verlässt.
 */
class FakeServer : CalDAVService {
    class Resource(val ics: String, val etag: String, val modRev: Int)
    class Collection(var name: String, var color: String? = null, var canWrite: Boolean = true, var order: Double? = null) {
        val items = sortedMapOf<String, Resource>()
        val tombstones = mutableMapOf<String, Int>()
    }

    val collections = sortedMapOf<String, Collection>()
    private var rev = 1
    private var etagCounter = 0
    private val calls = mutableListOf<String>()

    // Schalter für Sonderverhalten
    var supportsSyncCollection = true
    var invalidateTokens = false
    var omitETagOnPut = false
    var movesSupported = true
    var failAuth = false

    val home = "/remote.php/dav/calendars/nutzer/"
    var clock: Instant = Instant.ofEpochSecond(1_794_398_400)

    @Synchronized fun count(prefix: String) = calls.count { it.startsWith(prefix) }
    @Synchronized fun resetCalls() = calls.clear()

    private fun log(s: String) {
        calls.add(s)
        if (failAuth) throw DavError.Unauthorized
    }

    private fun nextETag(): String { etagCounter++; return "\"e$etagCounter\"" }

    // MARK: Testhilfen (Änderungen „durch ein anderes Gerät“)

    @Synchronized
    fun addCollection(name: String, color: String? = null, canWrite: Boolean = true): String {
        val href = home + Store.slug(name) + "/"
        collections[href] = Collection(name, color, canWrite)
        rev++
        return href
    }

    @Synchronized
    fun seed(list: String, item: TodoItem, file: String? = null): String {
        val d = ICalDocument.create(item.uid)
        d.apply(item, clock)
        val href = list + (file ?: SyncEngine.fileName(item.uid))
        rev++
        collections[list]!!.items[href] = Resource(d.serialized(), nextETag(), rev)
        return href
    }

    /** Ändert einen Eintrag wie ein anderer Client: [clock] bestimmt LAST-MODIFIED. */
    @Synchronized
    fun edit(list: String, uid: String, change: (TodoItem) -> TodoItem) {
        val entry = collections[list]!!.items.entries.first { ICalDocument.parse(it.value.ics)?.makeItem()?.uid == uid }
        val d = ICalDocument.parse(entry.value.ics)!!
        d.apply(change(d.makeItem()), clock)
        rev++
        collections[list]!!.items[entry.key] = Resource(d.serialized(), nextETag(), rev)
    }

    @Synchronized
    fun remove(list: String, uid: String) {
        val href = collections[list]!!.items.entries.firstOrNull { ICalDocument.parse(it.value.ics)?.makeItem()?.uid == uid }?.key ?: return
        rev++
        collections[list]!!.items.remove(href)
        collections[list]!!.tombstones[href] = rev
    }

    @Synchronized
    fun item(list: String, uid: String): TodoItem? =
        collections[list]?.items?.values?.firstNotNullOfOrNull { r -> ICalDocument.parse(r.ics)?.makeItem()?.takeIf { it.uid == uid } }

    @Synchronized
    fun titles(list: String): List<String> =
        (collections[list]?.items?.values?.mapNotNull { ICalDocument.parse(it.ics)?.makeItem()?.title } ?: emptyList()).sorted()

    @Synchronized
    fun hrefs(list: String): List<String> = collections[list]?.items?.keys?.sorted() ?: emptyList()

    // MARK: CalDAVService

    override suspend fun discover(): Discovery {
        log("discover")
        return Discovery("/remote.php/dav/principals/users/nutzer/", home)
    }

    override suspend fun listCollections(home: String): List<RemoteList> {
        log("listCollections")
        return collections.entries.mapIndexed { i, (href, c) ->
            RemoteList(href, c.name, c.color, c.order ?: i.toDouble(), "ctag-$rev", "tok-$rev", c.canWrite)
        }
    }

    override suspend fun collectionState(list: String): CollectionState {
        log("collectionState")
        return CollectionState("ctag-$rev", "tok-$rev")
    }

    override suspend fun changes(list: String, since: String?): RemoteChanges {
        log("changes ${since ?: "nil"}")
        if (!supportsSyncCollection) throw DavError.Http(501, "nicht unterstützt")
        val col = collections[list] ?: throw DavError.Http(404, "")
        var sinceRev = 0
        if (since != null) {
            val n = if (!invalidateTokens && since.startsWith("tok-")) since.drop(4).toIntOrNull() else null
            if (n == null) return RemoteChanges(tokenInvalid = true)
            sinceRev = n
        }
        val changed = col.items.filter { it.value.modRev > sinceRev }.map { RemoteEntry(it.key, it.value.etag) }
        val removed = col.tombstones.filter { it.value > sinceRev }.keys.sorted()
        return RemoteChanges(changed, removed, "tok-$rev")
    }

    override suspend fun listAll(list: String): List<RemoteEntry> {
        log("listAll")
        return (collections[list]?.items ?: emptyMap<String, Resource>()).map { RemoteEntry(it.key, it.value.etag) }
    }

    override suspend fun fetch(list: String, hrefs: List<String>): List<RemoteResource> {
        log("fetch ${hrefs.size}")
        return hrefs.mapNotNull { h -> collections[list]?.items?.get(h)?.let { RemoteResource(h, it.etag, it.ics) } }
    }

    override suspend fun put(href: String, ics: String, etag: String?, create: Boolean): PutResult {
        log("put ${if (create) "create" else "update"} $href")
        val listHref = collections.keys.firstOrNull { href.startsWith(it) } ?: throw DavError.Http(404, "")
        val col = collections[listHref]!!
        if (!col.canWrite) throw DavError.Http(403, "schreibgeschützt")
        val existing = col.items[href]
        if (create && existing != null) return PutResult.PreconditionFailed
        if (!create && (existing == null || existing.etag != etag)) return PutResult.PreconditionFailed
        rev++
        val newTag = nextETag()
        col.items[href] = Resource(ics, newTag, rev)
        col.tombstones.remove(href)
        return PutResult.Ok(if (omitETagOnPut) null else newTag)
    }

    override suspend fun delete(href: String, etag: String?): DeleteResult {
        log("delete $href")
        val listHref = collections.keys.firstOrNull { href.startsWith(it) } ?: return DeleteResult.NOT_FOUND
        val existing = collections[listHref]!!.items[href] ?: return DeleteResult.NOT_FOUND
        if (etag != null && etag != existing.etag) return DeleteResult.PRECONDITION_FAILED
        rev++
        collections[listHref]!!.items.remove(href)
        collections[listHref]!!.tombstones[href] = rev
        return DeleteResult.OK
    }

    override suspend fun move(from: String, to: String): MoveResult {
        log("move $from -> $to")
        if (!movesSupported) return MoveResult.Failed(501)
        val src = collections.keys.firstOrNull { from.startsWith(it) }
        val dst = collections.keys.firstOrNull { to.startsWith(it) }
        val res = src?.let { collections[it]!!.items[from] }
        if (src == null || dst == null || res == null) return MoveResult.Failed(404)
        rev++
        collections[src]!!.items.remove(from)
        collections[src]!!.tombstones[from] = rev
        collections[dst]!!.items[to] = Resource(res.ics, res.etag, rev)
        return MoveResult.Ok
    }

    override suspend fun makeCollection(href: String, name: String, color: String?) {
        log("mkcalendar $href")
        if (collections[href] != null) throw DavError.Http(405, "existiert")
        collections[href] = Collection(name, color)
        rev++
    }

    override suspend fun updateCollection(href: String, name: String?, color: String?, order: Double?) {
        log("proppatch $href")
        if (order != null) collections[href]?.order = order
        if (name != null) collections[href]?.name = name
        if (color != null) collections[href]?.color = color
        rev++
    }

    override suspend fun deleteCollection(href: String) {
        log("deleteCollection $href")
        collections.remove(href)
        rev++
    }
}

/** Testumgebung: Store + Fake-Server + Engine mit festem Takt. */
class Env private constructor(val store: Store, val server: FakeServer, val engine: SyncEngine, val list: String) {
    suspend fun sync(): SyncReport = engine.syncAll()

    companion object {
        fun make(items: List<TodoItem> = emptyList()): Env {
            val store = Store(InMemoryBackend())
            val server = FakeServer()
            val list = server.addCollection("Arbeit", "#1e66f5")
            for (i in items) server.seed(list, i)
            store.saveAccount(
                Account(
                    "https://cloud.example.org", "nutzer",
                    principalPath = "/remote.php/dav/principals/users/nutzer/", homePath = server.home,
                ),
            )
            val engine = SyncEngine(store, server) { Instant.ofEpochSecond(1_794_398_400 + 600) }
            return Env(store, server, engine, list)
        }
    }
}
