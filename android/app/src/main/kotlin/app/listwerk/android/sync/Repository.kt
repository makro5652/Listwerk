package app.listwerk.android.sync

import app.listwerk.android.AppContainer
import app.listwerk.android.data.AppPrefs
import app.listwerk.core.Account
import app.listwerk.core.ICalDate
import app.listwerk.core.PlanwerkMeta
import app.listwerk.core.QuickEntry
import app.listwerk.core.Recurrence
import app.listwerk.core.Store
import app.listwerk.core.TaskList
import app.listwerk.core.TaskRecord
import app.listwerk.core.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rückgängig-Marke für „Löschen“: der Eintrag samt Unteraufgaben. */
class DeleteUndo(val listHref: String, val items: List<TodoItem>)

/**
 * Einzige Schreibstelle der Oberfläche. Jede Änderung geht in den lokalen Speicher; danach werden Abgleich (gebündelt),
 * Widget und Erinnerungen angestoßen. Die Oberfläche greift nie selbst aufs Netz zu.
 */
class Repository(private val c: AppContainer) {
    private val store get() = c.store

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // MARK: Lesen (reaktiv über die Revision des Speichers)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(query: () -> T): Flow<T> = store.revision.mapLatest { io(query) }

    fun lists(): Flow<List<TaskList>> = observe { store.lists() }

    fun openCounts(): Flow<Map<String, Int>> = observe { store.lists().associate { it.href to store.openTaskCount(it.href) } }

    /** Einträge einer Liste (Unteraufgaben inklusive) bzw. aller Listen bei [AppPrefs.ALL]. */
    fun tasks(selection: String): Flow<List<TaskRecord>> = observe {
        if (AppPrefs.isSmart(selection)) {
            val visible = store.lists().map { it.href }.toSet()
            store.allTasks().filter { it.listHref in visible }
        } else store.tasks(selection)
    }

    /** Offene Einträge der obersten Ebene für das Widget (einmalige Abfrage). */
    suspend fun openTopLevel(selection: String): List<TaskRecord> = io {
        val all = if (AppPrefs.isSmart(selection)) {
            val visible = store.lists().map { it.href }.toSet()
            store.allTasks().filter { it.listHref in visible }
        } else store.tasks(selection)
        all.filter { it.item.parentUID == null && !it.item.isCompleted && !it.isDeleted }
    }

    fun record(listHref: String, uid: String): Flow<TaskRecord?> = observe { store.record(uid, listHref)?.takeIf { !it.isDeleted } }

    suspend fun account(): Account? = io { store.account() }

    suspend fun writableTarget(preferred: String?): TaskList? = io {
        val lists = store.lists().filter { it.canWrite }
        lists.firstOrNull { it.href == preferred } ?: lists.firstOrNull { it.href == c.prefs.lastList } ?: lists.firstOrNull()
    }

    // MARK: Einträge

    suspend fun addTask(
        listHref: String,
        title: String,
        color: String? = null,
        due: ICalDate? = null,
        notes: String = "",
        parentUid: String? = null,
        priority: Int = 0,
        /** Schnelleingabe auswerten („Elternbrief morgen 8 Uhr !“, „Call mom tomorrow 8am“). */
        quick: Boolean = false,
        /** Termin, falls weder [due] noch die Schnelleingabe einen liefert (z. B. „Geplant“: heute). */
        fallbackDue: ICalDate? = null,
    ): TodoItem? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val q = if (quick) QuickEntry.parse(trimmed) else QuickEntry(trimmed)
        val item = io {
            val list = store.list(listHref) ?: return@io null
            if (!list.canWrite) return@io null
            val base = TodoItem(
                title = q.title, notes = notes, color = color, due = due ?: q.due ?: fallbackDue, parentUID = parentUid,
                priority = if (q.important && priority == 0) 1 else priority, recurrence = q.recurrence,
                planwerk = if (list.isPlanwerkLinked) PlanwerkMeta() else null,
            )
            store.save(base, listHref).item
        }
        if (item != null) changed(delayMs = 800)
        return item
    }

    /** Eintrag aus Schnelleintrag, Teilen-Dialog oder Widget: landet lokal in der Zielliste und wird später abgeglichen. */
    suspend fun quickAdd(text: String, preferredList: String?): TodoItem? {
        val target = writableTarget(preferredList) ?: return null
        val lines = text.trim().lines()
        val title = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val notes = lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
        return addTask(target.href, title, notes = notes, quick = true)
    }

    suspend fun save(item: TodoItem, listHref: String) {
        io { store.save(item, listHref) }
        changed(delayMs = 2500)
    }

    suspend fun setDone(listHref: String, uid: String, done: Boolean) {
        io {
            val rec = store.record(uid, listHref) ?: return@io
            val item = rec.item
            if (item.isCompleted == done) return@io
            // Wiederkehrend (wie To Do): dieser Eintrag wird erledigt, ein neuer mit dem nächsten Termin entsteht.
            val due = item.due
            val nextDay = if (done && due != null) item.recurrence?.let { Recurrence.next(it, due.localDay(java.time.ZoneId.systemDefault())) } else null
            if (nextDay != null && due != null) {
                store.save(
                    item.copy(
                        uid = java.util.UUID.randomUUID().toString().lowercase(), due = Recurrence.shift(due, nextDay),
                        start = null, created = null, lastModified = null, attachments = emptyList(),
                        reminders = item.reminders.filter { it.trigger is app.listwerk.core.ReminderTrigger.Relative }.map { it.copy() },
                    ),
                    listHref,
                )
            }
            store.save(
                item.copy(
                    recurrence = if (nextDay != null) null else item.recurrence,
                    isCompleted = done,
                    completedAt = if (done) java.time.Instant.now() else null,
                    percentComplete = if (done) 100 else 0,
                ),
                listHref,
            )
        }
        changed(delayMs = 1500)
    }

    suspend fun toggleDone(rec: TaskRecord) = setDone(rec.listHref, rec.item.uid, !rec.item.isCompleted)

    suspend fun delete(listHref: String, uid: String): DeleteUndo {
        val undo = io {
            val family = store.family(uid, listHref)
            store.delete(uid, listHref)
            DeleteUndo(listHref, family)
        }
        changed(delayMs = 4000) // Zeit für „Rückgängig“, bevor der Abgleich löscht
        return undo
    }

    suspend fun restore(undo: DeleteUndo) {
        io { store.restore(undo.items, undo.listHref) }
        changed(delayMs = 1500)
    }

    suspend fun move(uid: String, from: String, to: String) {
        io { store.move(uid, from, to) }
        changed(delayMs = 1500)
    }

    suspend fun reorder(uid: String, listHref: String, afterUid: String?) {
        io { store.reorder(uid, listHref, afterUid) }
        changed(delayMs = 2500)
    }

    // MARK: Listen

    suspend fun createList(name: String, color: String?): TaskList? {
        // Ohne Konto entstehen lokale Listen; sie werden beim Anmelden übernommen (Store.adoptLocalLists).
        val home = account()?.homePath ?: Store.LOCAL_HOME
        val created = io { store.createList(name.trim(), color, home) }
        changed(delayMs = 800)
        return created
    }

    suspend fun renameList(href: String, name: String, color: String?) {
        io {
            store.renameList(href, name.trim())
            store.setListColor(href, color)
        }
        changed(delayMs = 800)
    }

    suspend fun reorderLists(hrefs: List<String>) {
        io { store.reorderLists(hrefs) }
        changed(delayMs = 800)
    }

    suspend fun deleteList(href: String) {
        io { store.deleteList(href) }
        changed(delayMs = 800)
    }

    private fun changed(delayMs: Long) {
        c.sync.schedule(delayMs)
        c.widgets.update()
        c.scope.launch(Dispatchers.IO) { c.reminders.reschedule() }
    }
}
