package app.listwerk.android

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import app.listwerk.android.data.AppPrefs
import app.listwerk.android.data.KeystoreSecretStore
import app.listwerk.android.data.ListwerkDatabase
import app.listwerk.android.data.RoomBackend
import app.listwerk.android.data.SecretStore
import app.listwerk.android.net.OkHttpTransport
import app.listwerk.android.reminders.ReminderScheduler
import app.listwerk.android.sync.Repository
import app.listwerk.android.sync.SyncRunner
import app.listwerk.android.widget.WidgetUpdater
import app.listwerk.core.PlanwerkLink
import app.listwerk.core.Store
import app.listwerk.core.ThemeCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Alles Langlebige. Nichts davon wird beim Prozessstart aufgebaut, sondern erst bei erster Verwendung. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs by lazy { AppPrefs(app) }
    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }
    val database by lazy { ListwerkDatabase.open(app) }
    val store by lazy { Store(RoomBackend(database)) }
    val transport by lazy { OkHttpTransport() }
    val themes by lazy {
        fun read(dir: String) = app.assets.list(dir).orEmpty().filter { it.endsWith(".json") }
            .map { app.assets.open("$dir/$it").use { s -> s.readBytes().toString(Charsets.UTF_8) } }
        ThemeCatalog(read("themes"), read("palettes"))
    }
    val syncStatus = MutableStateFlow(SyncStatus())
    val planwerkLinks = MutableStateFlow<Map<String, PlanwerkLink>>(emptyMap())
    val reminders by lazy { ReminderScheduler(app, this) }
    val widgets by lazy { WidgetUpdater(app) }
    val sync by lazy { SyncRunner(this) }
    val repository by lazy { Repository(this) }
}

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastMessage: String? = null,
    val error: String? = null,
    val authRequired: Boolean = false,
)

class ListwerkApp : Application(), Configuration.Provider {
    val container by lazy { AppContainer(this) }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
    }

    // WorkManager wird erst bei Bedarf gestartet (Standard-Initializer ist im Manifest entfernt): schnellerer Kaltstart.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
}

val Context.container: AppContainer get() = (applicationContext as ListwerkApp).container
