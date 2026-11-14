package app.listwerk.core

/**
 * Persistenz im Arbeitsspeicher für Tests und Vorschauen. Thread-sicher über eine einzige Sperre.
 * Hinweis: [transaction] rollt bei Fehlern nicht zurück (die Tests prüfen Fachlogik, nicht SQLite-Atomarität).
 */
class InMemoryBackend : StoreBackend {
    private val lock = Any()
    private var account: AccountRow? = null
    private val settings = LinkedHashMap<String, String>()
    private val lists = LinkedHashMap<String, ListRow>()
    private val tasks = LinkedHashMap<Pair<String, String>, TaskRow>()
    private val moves = LinkedHashMap<String, MoveRow>()
    private val conflictLog = ArrayList<ConflictRow>()
    private var nextRowId = 1L

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    override fun getAccount() = synchronized(lock) { account }
    override fun putAccount(row: AccountRow) = synchronized(lock) { account = row }

    override fun clearAll() = synchronized(lock) {
        account = null; settings.clear(); lists.clear(); tasks.clear(); moves.clear(); conflictLog.clear()
    }

    override fun getSetting(key: String) = synchronized(lock) { settings[key] }
    override fun putSetting(key: String, value: String) = synchronized(lock) { settings[key] = value; Unit }
    override fun removeSetting(key: String) = synchronized(lock) { settings.remove(key); Unit }

    override fun allLists() = synchronized(lock) { lists.values.toList() }
    override fun getList(href: String) = synchronized(lock) { lists[href] }
    override fun putList(row: ListRow) = synchronized(lock) { lists[row.href] = row; Unit }

    override fun deleteList(href: String) = synchronized(lock) {
        lists.remove(href)
        tasks.keys.removeAll { it.first == href }
        Unit
    }

    override fun getTask(listHref: String, uid: String) = synchronized(lock) { tasks[listHref to uid] }
    override fun tasksInList(listHref: String) = synchronized(lock) { tasks.values.filter { it.listHref == listHref } }
    override fun allTasks() = synchronized(lock) { tasks.values.toList() }

    override fun putTask(row: TaskRow) = synchronized(lock) {
        val key = row.listHref to row.uid
        val existing = tasks[key]
        tasks[key] = row.copy(rowId = existing?.rowId ?: if (row.rowId != 0L) row.rowId else nextRowId++)
        Unit
    }

    override fun deleteTask(listHref: String, uid: String) = synchronized(lock) { tasks.remove(listHref to uid); Unit }

    override fun moveTask(fromList: String, toList: String, uid: String) = synchronized(lock) {
        val row = tasks.remove(fromList to uid) ?: return@synchronized
        tasks[toList to uid] = row.copy(listHref = toList)
        Unit
    }

    override fun pendingMoves() = synchronized(lock) { moves.values.toList() }
    override fun getPendingMove(uid: String) = synchronized(lock) { moves[uid] }
    override fun putPendingMove(row: MoveRow) = synchronized(lock) { moves[row.uid] = row; Unit }
    override fun deletePendingMove(uid: String) = synchronized(lock) { moves.remove(uid); Unit }

    override fun addConflict(row: ConflictRow) = synchronized(lock) { conflictLog.add(row); Unit }
    override fun conflicts(uid: String) = synchronized(lock) { conflictLog.filter { it.uid == uid }.reversed() }
}
