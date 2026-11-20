package app.listwerk.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class SyncReport(
    var pushed: Int = 0,
    var pulled: Int = 0,
    var deleted: Int = 0,
    var conflicts: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
) {
    val isClean: Boolean get() = errors.isEmpty()
}

class SyncNotConfigured : Exception("Kein Konto eingerichtet")

/**
 * Gleicht die lokale Datenbank mit Nextcloud ab. Läuft seriell (Mutex), nie zwei Durchläufe gleichzeitig.
 *
 * Reihenfolge je Durchlauf: Listenänderungen → ausstehende Verschiebungen → Listen vom Server →
 * je Liste erst hochladen, dann herunterladen.
 */
class SyncEngine(
    private val store: Store,
    private val service: CalDAVService,
    private val now: () -> Instant = { Instant.now() },
) {
    private val mutex = Mutex()

    private fun describe(e: Throwable): String = e.message ?: e.toString()

    // MARK: Einstieg

    suspend fun syncAll(): SyncReport = mutex.withLock {
        val account = store.account() ?: throw SyncNotConfigured()
        var home = account.homePath
        if (home == null) {
            val found = service.discover()
            home = found.home
            store.saveAccount(account.copy(principalPath = found.principal, homePath = found.home))
        }
        val report = SyncReport()

        store.adoptLocalLists(home) // ohne Konto angelegte Listen (normalerweise schon beim Anmelden übernommen)
        pushListChanges(report)
        pushMoves(report)

        val remote = service.listCollections(home)
        store.applyRemoteLists(remote)

        for (list in store.lists()) {
            try {
                syncList(list, report)
            } catch (e: DavError.Unauthorized) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.errors.add("${list.name}: ${describe(e)}")
            }
        }
        report
    }

    // MARK: Listen

    private suspend fun pushListChanges(report: SyncReport) {
        for (l in store.allListsIncludingPending()) {
            try {
                if (l.pendingDelete) {
                    service.deleteCollection(l.href)
                    store.removeListLocally(l.href)
                } else if (l.pendingCreate) {
                    try {
                        service.makeCollection(l.href, l.name, l.color)
                    } catch (e: DavError.Http) {
                        // Existiert bereits (z. B. Antwort ging verloren): als vorhanden behandeln.
                        if (e.status != 405 && e.status != 409) throw e
                    }
                    store.clearListFlags(l.href)
                } else if (l.pendingProps) {
                    service.updateCollection(l.href, l.name, l.color)
                    store.clearListFlags(l.href)
                }
            } catch (e: DavError.Unauthorized) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.errors.add("Liste ${l.name}: ${describe(e)}")
            }
        }
    }

    // MARK: Verschiebungen

    private suspend fun pushMoves(report: SyncReport) {
        for (m in store.pendingMoves()) {
            val rec = store.record(m.uid, m.toList)
            if (rec == null) {
                store.clearPendingMove(m.uid)
                continue
            }
            val name = m.fromHref.split('/').lastOrNull { it.isNotEmpty() } ?: fileName(m.uid)
            val target = m.toList + name
            try {
                when (service.move(m.fromHref, target)) {
                    is MoveResult.Ok -> store.setHref(m.uid, m.toList, target)
                    is MoveResult.Failed -> {
                        // Ersatzweg: im Ziel anlegen, in der Quelle löschen.
                        val put = service.put(target, rec.ics, etag = null, create = true)
                        if (put !is PutResult.Ok) {
                            report.errors.add("Verschieben von „${rec.item.title}“ fehlgeschlagen")
                            continue
                        }
                        finishUpload(rec, target, put.etag)
                        service.delete(m.fromHref, rec.etag)
                    }
                }
                store.clearPendingMove(m.uid)
            } catch (e: DavError.Unauthorized) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.errors.add("Verschieben von „${rec.item.title}“: ${describe(e)}")
            }
        }
    }

    // MARK: Eine Liste

    suspend fun sync(listHref: String): SyncReport = mutex.withLock {
        val report = SyncReport()
        val list = store.list(listHref) ?: return@withLock report
        syncList(list, report)
        report
    }

    private suspend fun syncList(list: TaskList, report: SyncReport) {
        if (list.canWrite) pushTasks(list, report)
        pullTasks(store.list(list.href) ?: list, report)
    }

    // MARK: Hochladen

    private suspend fun pushTasks(list: TaskList, report: SyncReport) {
        for (rec in store.dirtyRecords(list.href)) {
            try {
                when {
                    rec.isDeleted -> pushDelete(rec, report)
                    rec.href == null -> pushCreate(rec, list, report)
                    else -> pushUpdate(rec, report)
                }
            } catch (e: DavError.Unauthorized) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.errors.add("„${rec.item.title}“: ${describe(e)}")
            }
        }
    }

    private suspend fun pushCreate(rec: TaskRecord, list: TaskList, report: SyncReport) {
        val href = list.href + fileName(rec.item.uid)
        when (val put = service.put(href, rec.ics, etag = null, create = true)) {
            is PutResult.Ok -> {
                finishUpload(rec, href, put.etag)
                report.pushed++
            }
            PutResult.PreconditionFailed -> {
                // Unter diesem Namen liegt schon etwas (z. B. frühere Übertragung ohne Antwort): wie ein Update behandeln.
                val existing = service.fetch(list.href, listOf(href)).firstOrNull() ?: return
                store.setHref(rec.item.uid, rec.listHref, href, existing.etag, existing.ics)
                // Die lokale Fassung wurde nie hochgeladen und hat keine Basis: Felder, die beide Seiten unterscheiden, sind Konflikte.
                resolveAndRetry(rec.copy(href = href, etag = existing.etag), existing, report)
            }
        }
    }

    private suspend fun pushUpdate(rec: TaskRecord, report: SyncReport) {
        val href = rec.href ?: return
        when (val put = service.put(href, rec.ics, rec.etag, create = false)) {
            is PutResult.Ok -> {
                finishUpload(rec, href, put.etag)
                report.pushed++
            }
            PutResult.PreconditionFailed -> {
                val remote = service.fetch(rec.listHref, listOf(href)).firstOrNull()
                if (remote != null) {
                    resolveAndRetry(rec, remote, report)
                } else {
                    // Auf dem Server gelöscht, lokal geändert: neu anlegen.
                    val again = service.put(href, rec.ics, etag = null, create = true)
                    if (again is PutResult.Ok) {
                        finishUpload(rec, href, again.etag)
                        report.pushed++
                    }
                }
            }
        }
    }

    /**
     * Nach dem Hochladen: Server-ETag und Serverstand als Basis für den nächsten Dreiwege-Abgleich merken.
     * Fehlt der ETag, hat der Server die Daten verändert gespeichert; dann wird neu geladen.
     */
    private suspend fun finishUpload(rec: TaskRecord, href: String, etag: String?) {
        if (etag != null) {
            store.markSynced(rec.item.uid, rec.listHref, href, etag, rec.ics)
        } else {
            val fetched = service.fetch(rec.listHref, listOf(href)).firstOrNull()
            if (fetched != null) store.markSynced(rec.item.uid, rec.listHref, href, fetched.etag, fetched.ics)
            else store.markSynced(rec.item.uid, rec.listHref, href, null, rec.ics)
        }
    }

    private suspend fun pushDelete(rec: TaskRecord, report: SyncReport) {
        val href = rec.href
        if (href == null) {
            store.purge(rec.item.uid, rec.listHref)
            return
        }
        when (service.delete(href, rec.etag)) {
            DeleteResult.OK, DeleteResult.NOT_FOUND -> {
                store.purge(rec.item.uid, rec.listHref)
                report.deleted++
            }
            DeleteResult.PRECONDITION_FAILED -> {
                // Lokal gelöscht, auf dem Server geändert: Die Serveränderung gewinnt, der Eintrag erscheint wieder.
                val remote = service.fetch(rec.listHref, listOf(href)).firstOrNull()
                if (remote != null) {
                    store.upsertFromServer(rec.listHref, href, remote.etag, remote.ics)
                    report.pulled++
                } else {
                    store.purge(rec.item.uid, rec.listHref)
                }
            }
        }
    }

    // MARK: Zusammenführen

    /** Führt lokalen und Serverstand zusammen und lädt die Fassung einmal erneut hoch. */
    private suspend fun resolveAndRetry(rec: TaskRecord, remote: RemoteResource, report: SyncReport) {
        if (!merge(rec, remote, report)) return
        val merged = store.record(rec.item.uid, rec.listHref) ?: return
        val href = merged.href ?: return
        when (val put = service.put(href, merged.ics, merged.etag, create = false)) {
            is PutResult.Ok -> {
                finishUpload(merged, href, put.etag)
                report.pushed++
            }
            PutResult.PreconditionFailed ->
                report.errors.add("„${rec.item.title}“: Konflikt, wird beim nächsten Abgleich erneut versucht")
        }
    }

    /** @return `true`, wenn noch lokale Änderungen hochzuladen sind. */
    private fun merge(rec: TaskRecord, remote: RemoteResource, report: SyncReport): Boolean {
        val remoteDoc = ICalDocument.parse(remote.ics) ?: return false
        val base = rec.baseICS?.let { ICalDocument.parse(it) }?.makeItem()
        val result = TodoMerge.merge(base, rec.item, remoteDoc.makeItem())
        remoteDoc.apply(result.merged, now())
        val mergedICS = remoteDoc.serialized()
        for (c in result.conflicts) {
            store.logConflict(rec.item.uid, c.field, c.local, c.remote, now())
            report.conflicts++
        }
        if (mergedICS == remote.ics) {
            store.markSynced(rec.item.uid, rec.listHref, remote.href, remote.etag, remote.ics)
            return false
        }
        store.storeMerged(rec.item.uid, rec.listHref, remote.ics, remote.etag, mergedICS)
        store.setHref(rec.item.uid, rec.listHref, remote.href)
        return true
    }

    // MARK: Herunterladen

    private suspend fun pullTasks(list: TaskList, report: SyncReport) {
        var changed: List<RemoteEntry>
        var removedHrefs: List<String>
        var newToken: String? = null
        var isFullSet = false

        var result: RemoteChanges? = null
        try {
            result = service.changes(list.href, list.syncToken)
            if (result.tokenInvalid) {
                result = service.changes(list.href, null)
                isFullSet = true
            } else if (list.syncToken == null) {
                isFullSet = true
            }
        } catch (e: DavError.Unauthorized) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = null  // sync-collection nicht unterstützt: ETag-Vergleich
        }

        val local = store.recordsWithHref(list.href)
        val localByHref = LinkedHashMap<String, TaskRecord>()
        for (r in local) localByHref.putIfAbsent(r.href!!, r)

        if (result != null && !result.tokenInvalid) {
            changed = result.changed
            removedHrefs = result.removed
            newToken = result.newToken
            if (isFullSet) {
                val present = changed.map { it.href }.toSet()
                removedHrefs = removedHrefs + local.filter { it.href!! !in present }.map { it.href!! }
            }
        } else {
            val entries = service.listAll(list.href)
            changed = entries
            val present = entries.map { it.href }.toSet()
            removedHrefs = local.filter { it.href!! !in present }.map { it.href!! }
        }

        val toFetch = changed.filter { e ->
            val l = localByHref[e.href]
            l?.etag == null || l.etag != e.etag
        }.map { it.href }
        if (toFetch.isNotEmpty()) {
            for (res in service.fetch(list.href, toFetch)) {
                val item = ICalDocument.parse(res.ics)?.makeItem()?.takeIf { it.uid.isNotEmpty() } ?: continue
                val rec = store.record(item.uid, list.href)
                if (rec != null && rec.isDirty && !rec.isDeleted) {
                    merge(rec, res, report)  // wird beim nächsten Durchlauf hochgeladen
                } else if (rec != null && rec.isDeleted) {
                    continue  // lokal gelöscht: das Löschen wird noch hochgeladen
                } else {
                    store.upsertFromServer(list.href, res.href, res.etag, res.ics)
                }
                report.pulled++
            }
        }

        for (href in removedHrefs) {
            val rec = localByHref[href] ?: continue
            if (rec.isDirty && !rec.isDeleted) {
                // Lokal geändert, auf dem Server gelöscht: neu anlegen.
                store.setHref(rec.item.uid, list.href, null, null, null)
            } else {
                store.purge(rec.item.uid, list.href)
                report.deleted++
            }
        }

        var ctag: String? = null
        if (newToken == null) ctag = service.collectionState(list.href).ctag
        store.updateListSyncState(list.href, ctag ?: list.ctag, newToken ?: list.syncToken)
    }

    companion object {
        /** Dateiname einer Ressource: UID, prozentkodiert (ohne `/`), plus `.ics`. */
        fun fileName(uid: String): String {
            val allowed = "-._~!$&'()*+,;=:@"
            val sb = StringBuilder()
            for (b in uid.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in allowed) sb.append(c)
                else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
            return "$sb.ics"
        }
    }
}
