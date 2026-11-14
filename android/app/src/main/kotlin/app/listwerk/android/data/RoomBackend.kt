package app.listwerk.android.data

import app.listwerk.core.AccountRow
import app.listwerk.core.ConflictRow
import app.listwerk.core.ListRow
import app.listwerk.core.MoveRow
import app.listwerk.core.StoreBackend
import app.listwerk.core.TaskRow
import java.util.concurrent.Callable

/** Dünne Room-Schicht unter dem Store aus :core: speichert und lädt nur Zeilen, die Fachlogik liegt im Store. */
class RoomBackend(private val db: ListwerkDatabase) : StoreBackend {
    private val dao = db.dao()

    override fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    override fun getAccount(): AccountRow? = dao.account()?.let { AccountRow(it.serverUrl, it.loginName, it.principal, it.homeSet) }

    override fun putAccount(row: AccountRow) = dao.putAccount(AccountEntity(1, row.serverUrl, row.loginName, row.principal, row.homeSet))

    override fun clearAll() = transaction {
        dao.clearMoves(); dao.clearConflicts(); dao.clearTasks(); dao.clearLists(); dao.clearAccount(); dao.clearSettings()
    }

    override fun getSetting(key: String): String? = dao.setting(key)
    override fun putSetting(key: String, value: String) = dao.putSetting(SettingEntity(key, value))
    override fun removeSetting(key: String) = dao.removeSetting(key)

    override fun allLists(): List<ListRow> = dao.lists().map { it.toRow() }
    override fun getList(href: String): ListRow? = dao.list(href)?.toRow()

    // REPLACE würde per Fremdschlüssel-Kaskade die Einträge der Liste löschen; daher gezieltes Einfügen/Aktualisieren.
    override fun putList(row: ListRow) = transaction {
        val entity = ListEntity(
            row.href, row.name, row.color, row.position, row.ctag, row.syncToken, row.canWrite, row.isShared, row.owner,
            row.planwerkLinked, row.pendingCreate, row.pendingDelete, row.pendingProps,
        )
        if (dao.list(row.href) == null) dao.putList(entity) else dao.updateListFields(entity)
    }

    override fun deleteList(href: String) = dao.deleteList(href)

    override fun getTask(listHref: String, uid: String): TaskRow? = dao.task(listHref, uid)?.toRow()
    override fun tasksInList(listHref: String): List<TaskRow> = dao.tasksInList(listHref).map { it.toRow() }
    override fun allTasks(): List<TaskRow> = dao.allTasks().map { it.toRow() }

    override fun putTask(row: TaskRow) {
        transaction {
            val existing = dao.task(row.listHref, row.uid)
            if (existing == null) dao.insertTask(row.toEntity(0)) else dao.updateTask(row.toEntity(existing.id))
        }
    }

    override fun deleteTask(listHref: String, uid: String) = dao.deleteTask(listHref, uid)
    override fun moveTask(fromList: String, toList: String, uid: String) = dao.moveTask(fromList, toList, uid)

    override fun pendingMoves(): List<MoveRow> = dao.moves().map { MoveRow(it.uid, it.fromList, it.fromHref, it.toList) }
    override fun getPendingMove(uid: String): MoveRow? = dao.move(uid)?.let { MoveRow(it.uid, it.fromList, it.fromHref, it.toList) }
    override fun putPendingMove(row: MoveRow) = dao.putMove(MoveEntity(row.uid, row.fromList, row.fromHref, row.toList))
    override fun deletePendingMove(uid: String) = dao.deleteMove(uid)

    override fun addConflict(row: ConflictRow) = dao.addConflict(ConflictEntity(0, row.uid, row.field, row.localValue, row.remoteValue, row.atMillis))
    override fun conflicts(uid: String): List<ConflictRow> =
        dao.conflicts(uid).map { ConflictRow(it.uid, it.field, it.localValue, it.remoteValue, it.atMillis) }

    private fun ListEntity.toRow() = ListRow(href, name, color, position, ctag, syncToken, canWrite, isShared, owner, planwerkLinked, pendingCreate, pendingDelete, pendingProps)

    private fun TaskEntity.toRow() = TaskRow(id, uid, listHref, href, etag, ics, baseIcs, title, dueKey, isDone, priority, sortOrder, parentUid, color, dirty, deleted)

    private fun TaskRow.toEntity(id: Long) = TaskEntity(id, uid, listHref, href, etag, ics, baseIcs, title, dueKey, isDone, priority, sortOrder, parentUid, color, dirty, deleted)
}
