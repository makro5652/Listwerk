package app.listwerk.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.listwerk.android.AppContainer
import app.listwerk.android.R
import app.listwerk.android.SyncStatus
import app.listwerk.android.container
import app.listwerk.core.Account
import app.listwerk.core.CalDAVClient
import app.listwerk.core.DavError
import app.listwerk.core.PlanwerkClient
import app.listwerk.core.PlanwerkLinkCache
import app.listwerk.core.SyncEngine
import app.listwerk.core.SyncNotConfigured
import app.listwerk.core.SyncReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed interface SyncOutcome {
    data class Done(val report: SyncReport) : SyncOutcome
    data object NotConfigured : SyncOutcome
    data object AuthRequired : SyncOutcome

    /** Vorübergehend (Netz): später erneut versuchen. */
    data object Retry : SyncOutcome
    data object Failed : SyncOutcome
}

/** Führt den Abgleich aus und plant ihn über WorkManager (periodisch und gebündelt nach Änderungen). */
class SyncRunner(private val c: AppContainer) {
    private val app = c.app
    private val mutex = Mutex()
    private var planwerkCache: Pair<String, PlanwerkLinkCache>? = null

    fun accountKey(a: Account) = "nc:${a.loginName}@${a.serverUrl}"

    // MARK: Planung

    /** Gebündelt: viele schnelle Änderungen ergeben einen Abgleich ([delayMs] nach der letzten). */
    fun schedule(delayMs: Long = 1500) {
        if (!c.prefs.signedIn) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(WORK_NOW)
        wm.cancelUniqueWork(WORK_PERIODIC)
    }

    // MARK: Ausführen

    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        val store = c.store
        val account = withContext(Dispatchers.IO) { store.account() } ?: return SyncOutcome.NotConfigured
        val password = withContext(Dispatchers.IO) { c.secrets.get(accountKey(account)) }
        if (password == null) {
            c.syncStatus.value = SyncStatus(authRequired = true, error = app.getString(R.string.sync_auth_required))
            return SyncOutcome.AuthRequired
        }
        c.syncStatus.value = c.syncStatus.value.copy(isSyncing = true, error = null, authRequired = false)
        try {
            val engine = SyncEngine(store, CalDAVClient(account.serverUrl, account.loginName, password, c.transport))
            val report = withContext(Dispatchers.IO) { engine.syncAll() }
            c.prefs.lastSyncAt = System.currentTimeMillis()
            val planwerkNote = refreshPlanwerk(report)
            val message = when {
                report.errors.isNotEmpty() -> report.errors.joinToString("\n")
                report.conflicts > 0 -> app.getString(R.string.sync_conflicts_resolved, report.conflicts)
                else -> planwerkNote
            }
            c.syncStatus.value = SyncStatus(lastMessage = message.takeIf { report.errors.isEmpty() }, error = message.takeIf { report.errors.isNotEmpty() })
            return SyncOutcome.Done(report)
        } catch (e: DavError.Unauthorized) {
            c.syncStatus.value = SyncStatus(authRequired = true, error = app.getString(R.string.sync_auth_required))
            return SyncOutcome.AuthRequired
        } catch (e: DavError.Network) {
            c.syncStatus.value = SyncStatus(error = app.getString(R.string.sync_offline))
            return SyncOutcome.Retry
        } catch (e: SyncNotConfigured) {
            c.syncStatus.value = SyncStatus()
            return SyncOutcome.NotConfigured
        } catch (e: CancellationException) {
            c.syncStatus.value = c.syncStatus.value.copy(isSyncing = false)
            throw e
        } catch (e: Exception) {
            c.syncStatus.value = SyncStatus(error = app.getString(R.string.sync_failed, e.message ?: e.javaClass.simpleName))
            return SyncOutcome.Failed
        } finally {
            if (c.syncStatus.value.isSyncing) c.syncStatus.value = c.syncStatus.value.copy(isSyncing = false)
            c.widgets.update()
            withContext(Dispatchers.IO) { c.reminders.reschedule() }
        }
    }

    /** Planwerk ist optional: Fehler blockieren nichts. Gibt eine Hinweiszeile zurück oder null. */
    private suspend fun refreshPlanwerk(report: SyncReport): String? {
        val url = c.prefs.planwerkUrl
        val token = withContext(Dispatchers.IO) { c.secrets.get(PLANWERK_TOKEN) }
        if (url.isBlank() || token.isNullOrBlank()) return null
        return try {
            val key = "$url|$token"
            val cached = planwerkCache?.takeIf { it.first == key }?.second
            val connector = PlanwerkClient(url, token, c.transport)
            val cache = cached ?: PlanwerkLinkCache(connector).also { planwerkCache = key to it }
            val links = withContext(Dispatchers.IO) {
                cache.apply(c.store)
                cache.current()
            }
            c.planwerkLinks.value = links.associateBy { it.listPath }
            c.prefs.savePlanwerkLinks(links)
            // Planwerk soll Änderungen aus Nextcloud zeitnah übernehmen, wenn wir in eine verknüpfte Liste geschrieben haben.
            if (report.pushed > 0 && links.isNotEmpty()) connector.triggerNextcloudSync()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.getString(R.string.planwerk_unreachable)
        }
    }

    companion object {
        const val WORK_NOW = "sync-now"
        const val WORK_PERIODIC = "sync-periodic"
        const val PLANWERK_TOKEN = "planwerk-token"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (applicationContext.container.sync.syncNow()) {
        is SyncOutcome.Retry -> Result.retry()
        else -> Result.success()
    }
}
