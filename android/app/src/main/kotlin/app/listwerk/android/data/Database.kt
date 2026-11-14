package app.listwerk.android.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

// Schema wie in der Swift-Referenz (Store.swift): account, task_list, task, pending_move, conflict_log, setting.

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "login_name") val loginName: String,
    val principal: String?,
    @ColumnInfo(name = "home_set") val homeSet: String?,
)

@Entity(tableName = "task_list")
data class ListEntity(
    @PrimaryKey val href: String,
    val name: String,
    val color: String?,
    val position: Double,
    val ctag: String?,
    @ColumnInfo(name = "sync_token") val syncToken: String?,
    @ColumnInfo(name = "can_write") val canWrite: Boolean,
    @ColumnInfo(name = "is_shared") val isShared: Boolean,
    val owner: String?,
    @ColumnInfo(name = "planwerk_linked") val planwerkLinked: Boolean,
    @ColumnInfo(name = "pending_create") val pendingCreate: Boolean,
    @ColumnInfo(name = "pending_delete") val pendingDelete: Boolean,
    @ColumnInfo(name = "pending_props") val pendingProps: Boolean,
)

@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = ListEntity::class, parentColumns = ["href"], childColumns = ["list_href"],
            onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["list_href", "uid"], unique = true),
        Index(value = ["list_href", "sort_order"]),
        Index(value = ["due_key"]),
        Index(value = ["parent_uid"]),
    ],
)
data class TaskEntity(
    /** Entspricht der SQLite-rowid der Referenz: Einfügereihenfolge, bleibt beim Verschieben erhalten. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    @ColumnInfo(name = "list_href") val listHref: String,
    val href: String?,
    val etag: String?,
    val ics: String,
    @ColumnInfo(name = "base_ics") val baseIcs: String?,
    val title: String,
    @ColumnInfo(name = "due_key") val dueKey: String?,
    @ColumnInfo(name = "is_done") val isDone: Boolean,
    val priority: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int?,
    @ColumnInfo(name = "parent_uid") val parentUid: String?,
    val color: String?,
    val dirty: Boolean,
    val deleted: Boolean,
)

@Entity(tableName = "pending_move")
data class MoveEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(name = "from_list") val fromList: String,
    @ColumnInfo(name = "from_href") val fromHref: String,
    @ColumnInfo(name = "to_list") val toList: String,
)

@Entity(tableName = "conflict_log")
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val field: String,
    @ColumnInfo(name = "local_value") val localValue: String,
    @ColumnInfo(name = "remote_value") val remoteValue: String,
    @ColumnInfo(name = "at") val atMillis: Long,
)

@Entity(tableName = "setting")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface StoreDao {
    @Query("SELECT * FROM account WHERE id = 1") fun account(): AccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putAccount(a: AccountEntity)

    @Query("DELETE FROM pending_move") fun clearMoves()
    @Query("DELETE FROM conflict_log") fun clearConflicts()
    @Query("DELETE FROM task") fun clearTasks()
    @Query("DELETE FROM task_list") fun clearLists()
    @Query("DELETE FROM account") fun clearAccount()
    @Query("DELETE FROM setting") fun clearSettings()

    @Query("SELECT value FROM setting WHERE `key` = :key") fun setting(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSetting(s: SettingEntity)
    @Query("DELETE FROM setting WHERE `key` = :key") fun removeSetting(key: String)

    @Query("SELECT * FROM task_list") fun lists(): List<ListEntity>
    @Query("SELECT * FROM task_list WHERE href = :href") fun list(href: String): ListEntity?
    @Insert fun putList(l: ListEntity)
    @Update fun updateListFields(l: ListEntity)
    @Query("DELETE FROM task_list WHERE href = :href") fun deleteList(href: String)

    @Query("SELECT * FROM task WHERE list_href = :list AND uid = :uid") fun task(list: String, uid: String): TaskEntity?
    @Query("SELECT * FROM task WHERE list_href = :list") fun tasksInList(list: String): List<TaskEntity>
    @Query("SELECT * FROM task") fun allTasks(): List<TaskEntity>
    @Insert fun insertTask(t: TaskEntity): Long
    @Update fun updateTask(t: TaskEntity)
    @Query("DELETE FROM task WHERE list_href = :list AND uid = :uid") fun deleteTask(list: String, uid: String)
    @Query("UPDATE task SET list_href = :to WHERE list_href = :from AND uid = :uid") fun moveTask(from: String, to: String, uid: String)

    @Query("SELECT * FROM pending_move") fun moves(): List<MoveEntity>
    @Query("SELECT * FROM pending_move WHERE uid = :uid") fun move(uid: String): MoveEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMove(m: MoveEntity)
    @Query("DELETE FROM pending_move WHERE uid = :uid") fun deleteMove(uid: String)

    @Insert fun addConflict(c: ConflictEntity)
    @Query("SELECT * FROM conflict_log WHERE uid = :uid ORDER BY id DESC") fun conflicts(uid: String): List<ConflictEntity>
}

@Database(
    entities = [AccountEntity::class, ListEntity::class, TaskEntity::class, MoveEntity::class, ConflictEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ListwerkDatabase : RoomDatabase() {
    abstract fun dao(): StoreDao

    companion object {
        fun open(context: Context): ListwerkDatabase =
            Room.databaseBuilder(context.applicationContext, ListwerkDatabase::class.java, "listwerk.db")
                .build()

        /** Datenbank im Arbeitsspeicher für Tests. */
        fun inMemory(context: Context): ListwerkDatabase =
            Room.inMemoryDatabaseBuilder(context, ListwerkDatabase::class.java).allowMainThreadQueries().build()
    }
}
