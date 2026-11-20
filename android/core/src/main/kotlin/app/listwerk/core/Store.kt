package app.listwerk.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * Lokaler Speicher: alle Fachregeln (Sortierung, Kaskade beim Löschen, Verschieben mit Merkliste, Umsortieren
 * in 1024er-Schritten, Abgleichszustand) über einer schmalen [StoreBackend]-Schicht.
 *
 * Die Oberfläche liest ausschließlich von hier. [revision] zählt bei jeder Änderung hoch, damit Ansichten und
 * das Widget neu laden können, ohne das Netz zu berühren.
 */
class Store(private val backend: StoreBackend, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> get() = _revision

    private fun bump() { _revision.value = _revision.value + 1 }

    private fun <T> write(block: () -> T): T = backend.transaction(block).also { bump() }

    // MARK: Konto

    fun saveAccount(a: Account) = write { backend.putAccount(AccountRow(a.serverUrl, a.loginName, a.principalPath, a.homePath)) }

    fun account(): Account? = backend.getAccount()?.let { Account(it.serverUrl, it.loginName, it.principal, it.homeSet) }

    /** Abmelden: löscht alle lokalen Daten. */
    fun wipe() = write { backend.clearAll() }

    fun setting(key: String): String? = backend.getSetting(key)

    fun setSetting(key: String, value: String?) {
        // Einstellungen lösen kein bump() aus: sie ändern keine Listen oder Einträge.
        if (value != null) backend.putSetting(key, value) else backend.removeSetting(key)
    }

    // MARK: Listen

    private fun ListRow.toList() = TaskList(
        href = href, name = name, color = color, position = position, ctag = ctag, syncToken = syncToken,
        canWrite = canWrite, isShared = isShared, ownerName = owner, isPlanwerkLinked = planwerkLinked,
        pendingCreate = pendingCreate, pendingDelete = pendingDelete, pendingProps = pendingProps,
    )

    private fun TaskList.toRow() = ListRow(
        href, name, color, position, ctag, syncToken, canWrite, isShared, ownerName, isPlanwerkLinked,
        pendingCreate, pendingDelete, pendingProps,
    )

    private val listOrder = compareBy<TaskList>({ it.position }, { it.name.lowercase() })

    fun lists(): List<TaskList> = backend.allLists().map { it.toList() }.filter { !it.pendingDelete }.sortedWith(listOrder)

    internal fun allListsIncludingPending(): List<TaskList> = backend.allLists().map { it.toList() }.sortedWith(listOrder)

    fun list(href: String): TaskList? = backend.getList(href)?.toList()

    /**
     * Übernimmt die Listen vom Server. Lokal angelegte, noch nicht hochgeladene Listen bleiben bestehen;
     * vom Server verschwundene Listen werden samt Einträgen entfernt.
     */
    internal fun applyRemoteLists(remote: List<RemoteList>) = write {
        backend.transaction {
            val known = remote.map { it.href }.toSet()
            for (l in allListsIncludingPending()) if (l.href !in known && !l.pendingCreate) backend.deleteList(l.href)
            for ((i, r) in remote.withIndex()) {
                val existing = list(r.href)
                if (existing != null && existing.pendingProps) {
                    // Lokale Umbenennung/Färbung hat Vorrang, bis sie hochgeladen ist.
                    backend.putList(existing.copy(canWrite = r.canWrite, isShared = r.isShared, ownerName = r.ownerName).toRow())
                    continue
                }
                backend.putList(
                    (existing ?: TaskList(r.href, r.name)).copy(
                        name = r.name, color = r.color, position = r.order ?: i.toDouble(),
                        canWrite = r.canWrite, isShared = r.isShared, ownerName = r.ownerName,
                        pendingCreate = false,
                    ).toRow(),
                )
            }
        }
    }

    /** Legt eine Liste lokal an; `MKCALENDAR` folgt beim nächsten Abgleich. */
    fun createList(name: String, color: String?, homePath: String): TaskList = write {
        var slug = slug(name)
        var href = "$homePath$slug/"
        var n = 2
        while (list(href) != null) { slug = slug(name) + "-$n"; href = "$homePath$slug/"; n++ }
        val pos = (backend.allLists().maxOfOrNull { it.position } ?: 0.0) + 1
        backend.putList(TaskList(href, name, color, pos, pendingCreate = true).toRow())
        list(href)!!
    }

    /**
     * Übernimmt die ohne Konto angelegten Listen ([LOCAL_HOME]) in das Konto unter [homePath]: neue Href, `MKCALENDAR`
     * und Hochladen der Einträge folgen beim nächsten Abgleich. Gibt alte → neue Href zurück (für gemerkte Auswahlen).
     */
    fun adoptLocalLists(homePath: String): Map<String, String> = write {
        val moved = LinkedHashMap<String, String>()
        for (row in backend.allLists().filter { it.href.startsWith(LOCAL_HOME) }) {
            val slug = row.href.removePrefix(LOCAL_HOME).trimEnd('/')
            var href = "$homePath$slug/"
            var n = 2
            while (backend.getList(href) != null) { href = "$homePath$slug-$n/"; n++ }
            backend.putList(row.copy(href = href, pendingCreate = true, pendingDelete = false, pendingProps = false))
            for (t in backend.tasksInList(row.href)) backend.moveTask(row.href, href, t.uid)
            backend.deleteList(row.href)
            moved[row.href] = href
        }
        moved
    }

    fun renameList(href: String, name: String) = write {
        list(href)?.let { backend.putList(it.copy(name = name, pendingProps = true).toRow()) }
        Unit
    }

    fun setListColor(href: String, color: String?) = write {
        list(href)?.let { backend.putList(it.copy(color = color, pendingProps = true).toRow()) }
        Unit
    }

    fun setListPosition(href: String, position: Double) = write {
        list(href)?.let { backend.putList(it.copy(position = position).toRow()) }
        Unit
    }

    fun setPlanwerkLinked(href: String, linked: Boolean) = write {
        list(href)?.let { backend.putList(it.copy(isPlanwerkLinked = linked).toRow()) }
        Unit
    }

    /** Markiert die Liste zum Löschen (erst beim Abgleich auf dem Server). Nie hochgeladene Listen verschwinden sofort. */
    fun deleteList(href: String) = write {
        val l = list(href) ?: return@write
        if (l.pendingCreate) backend.deleteList(href) else backend.putList(l.copy(pendingDelete = true).toRow())
    }

    internal fun updateListSyncState(href: String, ctag: String?, syncToken: String?) = write {
        list(href)?.let { backend.putList(it.copy(ctag = ctag, syncToken = syncToken).toRow()) }
        Unit
    }

    internal fun clearListFlags(href: String) = write {
        list(href)?.let { backend.putList(it.copy(pendingCreate = false, pendingProps = false).toRow()) }
        Unit
    }

    internal fun removeListLocally(href: String) = write { backend.deleteList(href) }

    // MARK: Einträge lesen

    private fun TaskRow.toRecord(): TaskRecord? {
        val doc = ICalDocument.parse(ics) ?: return null
        var item = doc.makeItem()
        if (item.uid.isEmpty()) item = item.copy(uid = uid)
        return TaskRecord(item, listHref, href, etag, dirty, deleted, ics, baseIcs)
    }

    private val bySortOrder = compareBy<TaskRow>({ it.sortOrder == null }, { it.sortOrder ?: 0 }, { it.rowId })
    private val byDue = compareBy<TaskRow>({ it.dueKey == null }, { it.dueKey ?: "" }, { it.rowId })

    fun tasks(inList: String): List<TaskRecord> =
        backend.tasksInList(inList).filter { !it.deleted }.sortedWith(bySortOrder).mapNotNull { it.toRecord() }

    /** Alle Einträge aller Listen, nach Fälligkeit sortiert (ohne Fälligkeit zuletzt). */
    fun allTasks(): List<TaskRecord> =
        backend.allTasks().filter { !it.deleted }.sortedWith(byDue).mapNotNull { it.toRecord() }

    /** Alle offenen Einträge aller Listen, nach Fälligkeit sortiert; liest nur offene Zeilen (für das Widget). */
    fun openTasks(): List<TaskRecord> =
        backend.allTasks().filter { !it.deleted && !it.isDone }.sortedWith(byDue).mapNotNull { it.toRecord() }

    fun record(uid: String, inList: String): TaskRecord? = backend.getTask(inList, uid)?.toRecord()

    internal fun dirtyRecords(inList: String): List<TaskRecord> =
        backend.tasksInList(inList).filter { it.dirty }.sortedWith(bySortOrder).mapNotNull { it.toRecord() }

    internal fun recordsWithHref(inList: String): List<TaskRecord> =
        backend.tasksInList(inList).filter { it.href != null }.sortedWith(bySortOrder).mapNotNull { it.toRecord() }

    fun openTaskCount(inList: String): Int = backend.tasksInList(inList).count { !it.deleted && !it.isDone }

    // MARK: Einträge schreiben

    private fun rowFor(rec: TaskRow?, item: TodoItem, listHref: String, ics: String): TaskRow = TaskRow(
        rowId = rec?.rowId ?: 0, uid = item.uid, listHref = listHref, href = rec?.href, etag = rec?.etag,
        ics = ics, baseIcs = rec?.baseIcs, title = item.title, dueKey = item.due?.sortKey(zone()),
        isDone = item.isCompleted, priority = item.priority, sortOrder = item.sortOrder, parentUid = item.parentUID,
        color = item.color, dirty = true, deleted = false,
    )

    /** Legt einen Eintrag an oder aktualisiert ihn. Ändert sich nichts, bleibt der Datensatz unberührt. */
    fun save(item: TodoItem, inList: String, now: Instant = Instant.now()): TaskRecord = write {
        val existing = backend.getTask(inList, item.uid)
        val existingRecord = existing?.toRecord()
        val doc = existing?.let { ICalDocument.parse(it.ics) } ?: ICalDocument.create(item.uid)
        doc.apply(item, now)
        val ics = doc.serialized()
        if (existing != null && existingRecord != null && ics == existing.ics && !existing.deleted) return@write existingRecord
        val saved = doc.makeItem()
        backend.putTask(rowFor(existing, saved, inList, ics))
        record(item.uid, inList)!!
    }

    /**
     * Löscht den Eintrag samt Unteraufgaben. Nie hochgeladene Einträge verschwinden sofort,
     * alle anderen werden beim nächsten Abgleich auf dem Server gelöscht.
     */
    fun delete(uid: String, inList: String) = write {
        for (u in descendantUIDs(uid, inList) + uid) {
            val row = backend.getTask(inList, u)
            if (row != null) {
                if (row.href == null) backend.deleteTask(inList, u)
                else backend.putTask(row.copy(deleted = true, dirty = true))
            }
            backend.deletePendingMove(u)
        }
    }

    /** „Rückgängig“ für einen eben gelöschten, schon hochgeladenen Eintrag, solange noch nicht synchronisiert wurde. */
    fun undelete(uid: String, inList: String) = write {
        backend.getTask(inList, uid)?.let { backend.putTask(it.copy(deleted = false, dirty = true)) }
        Unit
    }

    /** Der Eintrag samt allen Unteraufgaben (Vorlage für [restore], bevor [delete] aufgerufen wird). */
    fun family(uid: String, inList: String): List<TodoItem> =
        (listOf(uid) + descendantUIDs(uid, inList)).mapNotNull { record(it, inList)?.takeIf { r -> !r.isDeleted }?.item }

    /**
     * Stellt Einträge aus [family] nach [delete] wieder her: Noch auf dem Server liegende Einträge werden wieder
     * eingeblendet, nie hochgeladene (beim Löschen sofort entfernte) neu angelegt. Nichts davon erreicht den Server,
     * solange nicht zwischenzeitlich abgeglichen wurde; sonst entsteht der Eintrag erneut.
     */
    fun restore(items: List<TodoItem>, inList: String, now: Instant = Instant.now()) = write {
        for (item in items) {
            val row = backend.getTask(inList, item.uid)
            if (row != null && row.deleted) {
                backend.putTask(row.copy(deleted = false, dirty = true))
                save(item, inList, now)
            } else if (row == null) {
                save(item, inList, now)
            }
        }
    }

    internal fun descendantUIDs(uid: String, inList: String): List<String> {
        val rows = backend.tasksInList(inList).filter { !it.deleted }
        val result = ArrayList<String>()
        val frontier = ArrayDeque(listOf(uid))
        while (frontier.isNotEmpty()) {
            val next = frontier.removeLast()
            for (k in rows.filter { it.parentUid == next }.map { it.uid }) {
                if (k !in result && k != uid) { result.add(k); frontier.add(k) }
            }
        }
        return result
    }

    /** Verschiebt einen Eintrag samt Unteraufgaben in eine andere Liste. */
    fun move(uid: String, fromList: String, toList: String) {
        if (fromList == toList) return
        write {
            for (u in listOf(uid) + descendantUIDs(uid, fromList)) {
                val rec = backend.getTask(fromList, u) ?: continue
                // Eine gleichnamige UID im Ziel würde den Primärschlüssel verletzen.
                if (backend.getTask(toList, u) != null) continue
                val pending = backend.getPendingMove(u)
                // Hochgeladene Einträge verschiebt der Server per MOVE (pending_move); dirty bleibt unberührt.
                backend.moveTask(fromList, toList, u)
                val href = rec.href ?: continue  // nie hochgeladen: wird im Ziel neu angelegt
                if (pending != null) {
                    if (pending.fromList == toList) backend.deletePendingMove(u)
                    else backend.putPendingMove(pending.copy(toList = toList))
                } else {
                    backend.putPendingMove(MoveRow(u, fromList, href, toList))
                }
            }
        }
    }

    internal fun pendingMoves(): List<PendingMove> =
        backend.pendingMoves().map { PendingMove(it.uid, it.fromList, it.fromHref, it.toList) }

    internal fun clearPendingMove(uid: String) = write { backend.deletePendingMove(uid) }

    /**
     * Setzt den Eintrag hinter [after] (null = an den Anfang). Berechnet den Sortierwert zwischen den Nachbarn;
     * ist kein Abstand mehr frei, wird die Liste in 1024er-Schritten neu nummeriert.
     */
    fun reorder(uid: String, inList: String, after: String?, now: Instant = Instant.now()) = write {
        val ordered = tasks(inList).filter { it.item.parentUID == null }.toMutableList()
        val movedIndex = ordered.indexOfFirst { it.item.uid == uid }
        if (movedIndex < 0) return@write
        val moved = ordered.removeAt(movedIndex)
        val insertAt = after?.let { id -> ordered.indexOfFirst { it.item.uid == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        val hasPrev = insertAt > 0
        val hasNext = insertAt < ordered.size
        val prev = if (hasPrev) ordered[insertAt - 1].item.sortOrder else null
        val next = if (hasNext) ordered[insertAt].item.sortOrder else null

        val value: Int? = when {
            !hasPrev && !hasNext -> 1024
            !hasPrev && next != null -> next - 1024
            hasPrev && !hasNext && prev != null -> prev + 1024
            hasPrev && hasNext && prev != null && next != null && next - prev >= 2 -> prev + (next - prev) / 2
            else -> null
        }

        if (value != null) {
            save(moved.item.copy(sortOrder = value), inList, now)
        } else {
            ordered.add(insertAt, moved)
            for ((i, rec) in ordered.withIndex()) save(rec.item.copy(sortOrder = (i + 1) * 1024), inList, now)
        }
    }

    // MARK: Abgleich (intern)

    internal fun markSynced(uid: String, listHref: String, href: String, etag: String?, ics: String) = write {
        val item = ICalDocument.parse(ics)?.makeItem()
        val key = item?.uid ?: uid
        val row = backend.getTask(listHref, key) ?: return@write
        backend.putTask(
            if (item != null) {
                row.copy(
                    href = href, etag = etag, ics = ics, baseIcs = ics, dirty = false, title = item.title,
                    dueKey = item.due?.sortKey(zone()), isDone = item.isCompleted, priority = item.priority,
                    sortOrder = item.sortOrder, parentUid = item.parentUID, color = item.color,
                )
            } else row.copy(href = href, etag = etag, ics = ics, baseIcs = ics, dirty = false),
        )
    }

    /** Setzt den Pfad einer Ressource. `null` löscht ihn. */
    internal fun setHref(uid: String, listHref: String, href: String?) = write {
        backend.getTask(listHref, uid)?.let { backend.putTask(it.copy(href = href)) }
        Unit
    }

    internal fun setHref(uid: String, listHref: String, href: String?, etag: String?, baseICS: String?) = write {
        backend.getTask(listHref, uid)?.let { backend.putTask(it.copy(href = href, etag = etag, baseIcs = baseICS)) }
        Unit
    }

    /** Entfernt einen Eintrag endgültig aus der lokalen Datenbank (nach erfolgreichem DELETE oder Serverlöschung). */
    internal fun purge(uid: String, listHref: String) = write {
        backend.deleteTask(listHref, uid)
        backend.deletePendingMove(uid)
    }

    /** Schreibt den Serverstand in einen lokal nicht geänderten Eintrag (oder legt ihn an). */
    internal fun upsertFromServer(listHref: String, href: String, etag: String?, ics: String) {
        val item = ICalDocument.parse(ics)?.makeItem()?.takeIf { it.uid.isNotEmpty() } ?: return
        write {
            val existing = backend.getTask(listHref, item.uid)
            backend.putTask(
                TaskRow(
                    rowId = existing?.rowId ?: 0, uid = item.uid, listHref = listHref, href = href, etag = etag,
                    ics = ics, baseIcs = ics, title = item.title, dueKey = item.due?.sortKey(zone()),
                    isDone = item.isCompleted, priority = item.priority, sortOrder = item.sortOrder,
                    parentUid = item.parentUID, color = item.color, dirty = false, deleted = false,
                ),
            )
        }
    }

    /** Schreibt eine zusammengeführte Fassung: [mergedICS] ist der Serverstand plus lokale Änderungen und wird erneut hochgeladen. */
    internal fun storeMerged(uid: String, listHref: String, serverICS: String, serverETag: String?, mergedICS: String) = write {
        val item = ICalDocument.parse(mergedICS)?.makeItem() ?: return@write
        val row = backend.getTask(listHref, uid) ?: return@write
        backend.putTask(
            row.copy(
                ics = mergedICS, baseIcs = serverICS, etag = serverETag, dirty = true, title = item.title,
                dueKey = item.due?.sortKey(zone()), isDone = item.isCompleted, priority = item.priority,
                sortOrder = item.sortOrder, parentUid = item.parentUID, color = item.color,
            ),
        )
    }

    // MARK: Konfliktprotokoll

    internal fun logConflict(uid: String, field: String, local: String, remote: String, at: Instant) = write {
        backend.addConflict(ConflictRow(uid, field, local, remote, at.toEpochMilli()))
    }

    fun conflicts(forUID: String): List<ConflictEntry> =
        backend.conflicts(forUID).map { ConflictEntry(it.uid, it.field, it.localValue, it.remoteValue, Instant.ofEpochMilli(it.atMillis)) }

    /** Anzahl aller Zeilen in der Eintragstabelle, auch gelöschter (für Tests und Diagnose). */
    fun rawTaskRowCount(): Int = backend.allTasks().size

    companion object {
        /** Pseudo-Heimatpfad für Listen ohne Konto; wird beim Anmelden durch den echten ersetzt ([adoptLocalLists]). */
        const val LOCAL_HOME = "local:/"

        fun slug(name: String): String {
            val folded = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace("ß", "ss")
            val out = StringBuilder()
            var lastDash = true
            for (ch in folded) {
                if (ch in 'a'..'z' || ch in '0'..'9') { out.append(ch); lastDash = false }
                else if (!lastDash) { out.append('-'); lastDash = true }
            }
            while (out.endsWith("-")) out.setLength(out.length - 1)
            return if (out.isEmpty()) "liste" else out.toString()
        }
    }
}
