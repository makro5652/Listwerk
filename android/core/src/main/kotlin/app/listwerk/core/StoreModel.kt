package app.listwerk.core

import java.time.Instant

// MARK: - Modelle der lokalen Datenbank

data class Account(
    val serverUrl: String,
    val loginName: String,
    val principalPath: String? = null,
    val homePath: String? = null,
)

data class TaskList(
    /** Serverpfad der Collection, z. B. `/remote.php/dav/calendars/nutzer/arbeit/`. */
    val href: String,
    val name: String,
    val color: String? = null,
    val position: Double = 0.0,
    val ctag: String? = null,
    val syncToken: String? = null,
    val canWrite: Boolean = true,
    val isShared: Boolean = false,
    val ownerName: String? = null,
    val isPlanwerkLinked: Boolean = false,
    internal val pendingCreate: Boolean = false,
    internal val pendingDelete: Boolean = false,
    internal val pendingProps: Boolean = false,
) {
    val id: String get() = href
}

data class TaskRecord(
    val item: TodoItem,
    val listHref: String,
    /** Pfad der Ressource auf dem Server; null, solange der Eintrag nie hochgeladen wurde. */
    val href: String?,
    val etag: String?,
    val isDirty: Boolean,
    val isDeleted: Boolean,
    internal val ics: String,
    internal val baseICS: String?,
) {
    val id: String get() = "$listHref|${item.uid}"
}

internal data class PendingMove(val uid: String, val fromList: String, val fromHref: String, val toList: String)

data class ConflictEntry(
    val uid: String,
    val field: String,
    val localValue: String,
    val remoteValue: String,
    val at: Instant,
)

// MARK: - Zeilen der Persistenzschicht

/** Zeilentypen der Persistenzschicht. Room (App) und die Testimplementierung speichern genau diese Felder. */
data class AccountRow(val serverUrl: String, val loginName: String, val principal: String?, val homeSet: String?)

data class ListRow(
    val href: String,
    val name: String,
    val color: String?,
    val position: Double,
    val ctag: String?,
    val syncToken: String?,
    val canWrite: Boolean,
    val isShared: Boolean,
    val owner: String?,
    val planwerkLinked: Boolean,
    val pendingCreate: Boolean,
    val pendingDelete: Boolean,
    val pendingProps: Boolean,
)

data class TaskRow(
    /** Einfügereihenfolge (rowid); 0 = noch nicht vergeben, die Persistenz vergibt beim ersten Speichern einen Wert. */
    val rowId: Long,
    val uid: String,
    val listHref: String,
    val href: String?,
    val etag: String?,
    val ics: String,
    val baseIcs: String?,
    val title: String,
    val dueKey: String?,
    val isDone: Boolean,
    val priority: Int,
    val sortOrder: Int?,
    val parentUid: String?,
    val color: String?,
    val dirty: Boolean,
    val deleted: Boolean,
)

data class MoveRow(val uid: String, val fromList: String, val fromHref: String, val toList: String)

data class ConflictRow(val uid: String, val field: String, val localValue: String, val remoteValue: String, val atMillis: Long)

/**
 * Schmale Persistenzschnittstelle unter [Store]. Die Fachlogik (Sortierung, Kaskaden, Verschieben, Abgleich)
 * liegt vollständig in [Store]; Implementierungen speichern und laden nur Zeilen. Alle Methoden blockieren
 * und gehören auf einen Hintergrund-Thread.
 */
interface StoreBackend {
    /** Führt [block] atomar aus; verschachtelte Aufrufe sind erlaubt. Wirft [block], wird zurückgerollt. */
    fun <T> transaction(block: () -> T): T

    fun getAccount(): AccountRow?
    fun putAccount(row: AccountRow)

    /** Löscht alle Tabellen (Abmelden). */
    fun clearAll()

    fun getSetting(key: String): String?
    fun putSetting(key: String, value: String)
    fun removeSetting(key: String)

    fun allLists(): List<ListRow>
    fun getList(href: String): ListRow?

    /** Legt die Liste an oder ersetzt sie (Schlüssel: href). */
    fun putList(row: ListRow)

    /** Entfernt die Liste samt aller ihrer Einträge (Kaskade). */
    fun deleteList(href: String)

    fun getTask(listHref: String, uid: String): TaskRow?

    /** Alle Zeilen der Liste, auch gelöschte, in beliebiger Reihenfolge. */
    fun tasksInList(listHref: String): List<TaskRow>

    /** Alle Zeilen aller Listen in beliebiger Reihenfolge. */
    fun allTasks(): List<TaskRow>

    /** Legt die Zeile an oder ersetzt sie (Schlüssel: listHref + uid). Die rowId einer vorhandenen Zeile bleibt erhalten. */
    fun putTask(row: TaskRow)

    fun deleteTask(listHref: String, uid: String)

    /** Verschiebt die Zeile in eine andere Liste; die rowId bleibt erhalten. */
    fun moveTask(fromList: String, toList: String, uid: String)

    fun pendingMoves(): List<MoveRow>
    fun getPendingMove(uid: String): MoveRow?
    fun putPendingMove(row: MoveRow)
    fun deletePendingMove(uid: String)

    fun addConflict(row: ConflictRow)

    /** Neueste zuerst. */
    fun conflicts(uid: String): List<ConflictRow>
}
