package app.listwerk.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.listwerk.android.R
import app.listwerk.android.SyncStatus
import app.listwerk.android.container
import app.listwerk.android.data.AppPrefs
import app.listwerk.android.sync.DeleteUndo
import app.listwerk.android.sync.SyncRunner
import app.listwerk.core.Account
import app.listwerk.core.CalDAVClient
import app.listwerk.core.DavError
import app.listwerk.core.ICalDate
import app.listwerk.core.LoginFlowV2
import app.listwerk.core.PlanwerkClient
import app.listwerk.core.PlanwerkLink
import app.listwerk.core.TaskList
import app.listwerk.core.TaskRecord
import app.listwerk.core.TodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { MAIN, SETTINGS, LOGIN }

/** Sortierung wie in Microsoft To Do; MANUAL = eigene Reihenfolge (Ziehen). */
enum class SortMode { MANUAL, IMPORTANCE, DUE, ALPHA, CREATED }

/** Ein Eintrag der Hauptebene samt seinen Unteraufgaben. */
data class TaskGroup(val rec: TaskRecord, val children: List<TaskRecord>) {
    val uid: String get() = rec.item.uid

    /** Eindeutig auch über Listen hinweg (dieselbe UID kann in zwei Listen liegen). */
    val key: String get() = rec.id
}

sealed interface LoginState {
    data object Idle : LoginState
    data class Waiting(val url: String) : LoginState
    data class Failed(val message: String) : LoginState
}

sealed interface UiEvent {
    data class OpenUrl(val url: String) : UiEvent
    data class Undo(val undo: DeleteUndo, val title: String) : UiEvent
    data class Message(val text: String) : UiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.repository
    private val prefs = c.prefs

    /** Ein Nextcloud-Konto ist eingerichtet. Ohne Konto arbeitet die App rein lokal. */
    val signedIn = MutableStateFlow(prefs.signedIn)
    val onboarded = MutableStateFlow(prefs.onboarded)
    val layout = MutableStateFlow(prefs.layout)
    val sortMode = MutableStateFlow(runCatching { SortMode.valueOf(prefs.sortMode) }.getOrDefault(SortMode.MANUAL))
    val search = MutableStateFlow("")
    val myDay = MutableStateFlow(prefs.myDay)
    val screen = MutableStateFlow(Screen.MAIN)
    val login = MutableStateFlow<LoginState>(LoginState.Idle)
    val syncStatus: StateFlow<SyncStatus> = c.syncStatus
    val planwerkLinks: StateFlow<Map<String, PlanwerkLink>> = c.planwerkLinks
    val themeId = MutableStateFlow(prefs.themeId)
    val focusInput = MutableStateFlow(prefs.focusInputOnStart)
    val showCompleted = MutableStateFlow(prefs.showCompleted)
    val themes get() = c.themes

    /** Gewählte Liste (Href) oder [AppPrefs.ALL]. Beim Start die zuletzt benutzte. */
    val selection = MutableStateFlow(
        prefs.startList.let { if (it == AppPrefs.START_LAST) prefs.lastList ?: AppPrefs.ALL else it },
    )

    /** In den Einstellungen gewählte Startliste ([AppPrefs.START_LAST], [AppPrefs.ALL] oder Href). */
    val startList = MutableStateFlow(prefs.startList)
    val detail = MutableStateFlow<Pair<String, String>?>(null)

    // SharedFlow statt Channel: Activity (Links) und Bildschirm (Snackbars) sammeln beide; ein Channel gäbe jedes Ereignis nur einem.
    private val events = kotlinx.coroutines.flow.MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val uiEvents: Flow<UiEvent> = events

    /** Null, solange die Datenbank noch nicht gelesen wurde. */
    val lists: StateFlow<List<TaskList>?> = repo.lists().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val openCounts: StateFlow<Map<String, Int>> = repo.openCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Alle Einträge aller Listen (für die Zähler der intelligenten Listen). */
    private val everything: StateFlow<List<TaskRecord>> = repo.tasks(AppPrefs.ALL).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Offene Einträge je intelligenter Liste. */
    val smartCounts: StateFlow<Map<String, Int>> = combine(everything, myDay) { all, md ->
        val open = all.filter { it.item.parentUID == null && !it.item.isCompleted }
        mapOf(
            AppPrefs.MY_DAY to open.count { matches(AppPrefs.MY_DAY, it, md) },
            AppPrefs.IMPORTANT to open.count { matches(AppPrefs.IMPORTANT, it, md) },
            AppPrefs.PLANNED to open.count { matches(AppPrefs.PLANNED, it, md) },
            AppPrefs.ALL to open.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun matches(selection: String, rec: TaskRecord, md: Set<String>): Boolean = when (selection) {
        AppPrefs.MY_DAY -> rec.item.uid in md || rec.item.due?.localDay()?.let { !it.isAfter(java.time.LocalDate.now()) } == true
        AppPrefs.IMPORTANT -> rec.item.priority in 1..4
        AppPrefs.PLANNED -> rec.item.due != null
        AppPrefs.COMPLETED -> rec.item.isCompleted
        else -> true
    }

    private val rawTasks: Flow<List<TaskRecord>> = selection.flatMapLatest { repo.tasks(it) }

    val groups: StateFlow<List<TaskGroup>?> = combine(
        combine(rawTasks, selection, myDay, ::Triple),
        combine(showCompleted, layout, sortMode, search) { a, b, c, d -> listOf(a, b, c, d) },
    ) { (all, sel, md), opts ->
        val completed = opts[0] as Boolean || opts[1] == AppPrefs.LAYOUT_TODO || sel == AppPrefs.COMPLETED
        val sort = opts[2] as SortMode
        val query = (opts[3] as String).trim()
        val byParent = all.filter { it.item.parentUID != null }.groupBy { it.item.parentUID }
        var tops = all.filter { it.item.parentUID == null }
            .filter { matches(sel, it, md) }
            .filter { completed || !it.item.isCompleted }
        if (query.isNotEmpty()) {
            tops = tops.filter { r ->
                listOf(r.item.title, r.item.notes).any { it.contains(query, ignoreCase = true) } ||
                    byParent[r.item.uid].orEmpty().any { it.item.title.contains(query, ignoreCase = true) }
            }
        }
        tops = when (sort) {
            SortMode.MANUAL -> if (sel == AppPrefs.PLANNED) tops.sortedBy { it.item.due?.valueString } else tops
            SortMode.IMPORTANCE -> tops.sortedBy { if (it.item.priority == 0) 10 else it.item.priority }
            SortMode.DUE -> tops.sortedWith(compareBy(nullsLast()) { it.item.due?.valueString })
            SortMode.ALPHA -> tops.sortedBy { it.item.title.lowercase() }
            SortMode.CREATED -> tops.sortedByDescending { it.item.created }
        }
        tops.map { TaskGroup(it, byParent[it.item.uid].orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentList: StateFlow<TaskList?> = combine(lists, selection) { l, s -> l?.firstOrNull { it.href == s } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Liste, in die das Eingabefeld schreibt: die gewählte, sonst die zuletzt beschreibbare. */
    val entryTarget: StateFlow<TaskList?> = combine(lists, selection) { l, s ->
        val writable = l.orEmpty().filter { it.canWrite }
        writable.firstOrNull { it.href == s } ?: if (AppPrefs.isSmart(s)) writable.firstOrNull { it.href == prefs.lastWritableList } ?: writable.firstOrNull() else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Auswahl prüfen, sobald die Listen da sind (zuletzt benutzte Liste kann inzwischen gelöscht sein).
        viewModelScope.launch {
            val l = lists.filterNotNull().first()
            // Gewählte Startliste existiert nicht mehr (gelöscht, Konto gewechselt): zurück auf „zuletzt benutzte“.
            val start = startList.value
            if (start != AppPrefs.START_LAST && !AppPrefs.isSmart(start) && l.none { it.href == start }) setStartList(AppPrefs.START_LAST)
            if (!AppPrefs.isSmart(selection.value) && l.none { it.href == selection.value }) {
                select(l.firstOrNull()?.href ?: AppPrefs.ALL)
            }
        }
        if (signedIn.value) viewModelScope.launch(Dispatchers.Default) {
            c.planwerkLinks.value = prefs.loadPlanwerkLinks().associateBy { it.listPath }
            c.sync.ensurePeriodic()
        }
    }

    // MARK: Auswahl und Anzeige

    fun select(href: String) {
        selection.value = href
        prefs.lastList = href
        search.value = ""
        if (!AppPrefs.isSmart(href)) {
            val l = lists.value?.firstOrNull { it.href == href }
            if (l?.canWrite == true) prefs.lastWritableList = href
        }
    }

    fun setTheme(id: String) { themeId.value = id; prefs.themeId = id; c.widgets.update() }

    fun setStartList(value: String) { startList.value = value; prefs.startList = value }

    /** Favorit = feste Startliste; nochmal tippen hebt ihn auf. */
    fun toggleFavorite(href: String) = setStartList(if (startList.value == href) AppPrefs.START_LAST else href)

    fun moveList(list: TaskList, by: Int) {
        val order = lists.value.orEmpty().map { it.href }.toMutableList()
        val i = order.indexOf(list.href)
        if (i < 0 || i + by !in order.indices) return
        order.add(i + by, order.removeAt(i))
        viewModelScope.launch { repo.reorderLists(order) }
    }

    fun setFocusInput(on: Boolean) { focusInput.value = on; prefs.focusInputOnStart = on }

    fun setShowCompleted(on: Boolean) { showCompleted.value = on; prefs.showCompleted = on }

    fun setLayout(value: String) { layout.value = value; prefs.layout = value }

    fun setSort(mode: SortMode) { sortMode.value = mode; prefs.sortMode = mode.name }

    fun toggleMyDay(uid: String) {
        val now = prefs.myDay
        val next = if (uid in now) now - uid else now + uid
        prefs.myDay = next
        myDay.value = next
    }

    /** Stern wie in To Do: hohe Priorität an/aus. */
    fun toggleImportant(rec: TaskRecord) = save(rec.item.copy(priority = if (rec.item.priority in 1..4) 0 else 1), rec.listHref)

    fun openDetail(rec: TaskRecord) { detail.value = rec.listHref to rec.item.uid }
    fun openDetail(listHref: String, uid: String) { detail.value = listHref to uid }
    fun closeDetail() { detail.value = null }
    fun recordFlow(listHref: String, uid: String): Flow<TaskRecord?> = repo.record(listHref, uid)

    fun childrenFlow(listHref: String, uid: String): Flow<List<TaskRecord>> =
        repo.observe { c.store.tasks(listHref).filter { it.item.parentUID == uid } }

    // MARK: Einträge

    fun addTask(title: String, color: String?, due: ICalDate?) {
        val target = entryTarget.value ?: return
        val sel = selection.value
        // Wie in To Do: in „Wichtig“ mit Stern, in „Geplant“ für heute, in „Mein Tag“ direkt im Tag.
        val today = java.time.LocalDate.now().let { ICalDate.day(it.year, it.monthValue, it.dayOfMonth) }
        viewModelScope.launch {
            val item = repo.addTask(
                target.href, title, color, due,
                priority = if (sel == AppPrefs.IMPORTANT) 1 else 0,
                quick = true,
                fallbackDue = today.takeIf { sel == AppPrefs.PLANNED },
            )
            if (item != null && sel == AppPrefs.MY_DAY) toggleMyDay(item.uid)
        }
    }

    fun addSubtask(parent: TaskRecord, title: String) {
        viewModelScope.launch { repo.addTask(parent.listHref, title, parentUid = parent.item.uid) }
    }

    fun save(item: TodoItem, listHref: String) { viewModelScope.launch { repo.save(item, listHref) } }

    fun toggleDone(rec: TaskRecord) { viewModelScope.launch { repo.toggleDone(rec) } }

    fun delete(rec: TaskRecord) {
        viewModelScope.launch {
            val undo = repo.delete(rec.listHref, rec.item.uid)
            events.emit(UiEvent.Undo(undo, rec.item.title))
        }
    }

    fun undo(undo: DeleteUndo) { viewModelScope.launch { repo.restore(undo) } }

    fun move(rec: TaskRecord, to: String) {
        viewModelScope.launch {
            repo.move(rec.item.uid, rec.listHref, to)
            if (detail.value?.second == rec.item.uid) detail.value = to to rec.item.uid
        }
    }

    fun reorder(uid: String, afterUid: String?) {
        val list = currentList.value ?: return
        if (!list.canWrite || sortMode.value != SortMode.MANUAL || search.value.isNotBlank()) return
        viewModelScope.launch { repo.reorder(uid, list.href, afterUid) }
    }

    // MARK: Listen

    fun createList(name: String, color: String?) {
        viewModelScope.launch {
            val l = repo.createList(name, color)
            if (l != null) select(l.href)
        }
    }

    fun editList(list: TaskList, name: String, color: String?) { viewModelScope.launch { repo.renameList(list.href, name, color) } }

    fun deleteList(list: TaskList) {
        viewModelScope.launch {
            repo.deleteList(list.href)
            if (selection.value == list.href) select(AppPrefs.ALL)
            if (prefs.startList == list.href) setStartList(AppPrefs.START_LAST)
        }
    }

    // MARK: Abgleich

    fun syncNow() { viewModelScope.launch { c.sync.syncNow() } }

    // MARK: Anmeldung (Login Flow v2)

    private var loginJob: Job? = null

    fun startLogin(serverInput: String) {
        loginJob?.cancel()
        val app = c.app
        var text = serverInput.trim().trimEnd('/')
        if (text.isEmpty()) return
        if (!text.contains("://")) text = "https://$text"
        if (!text.startsWith("https://", ignoreCase = true)) {
            login.value = LoginState.Failed(app.getString(R.string.login_https_only))
            return
        }
        loginJob = viewModelScope.launch {
            try {
                val flow = LoginFlowV2(c.transport)
                val session = flow.start(text)
                login.value = LoginState.Waiting(session.loginUrl)
                events.emit(UiEvent.OpenUrl(session.loginUrl))
                val creds = flow.waitForCredentials(session)
                val client = CalDAVClient(creds.serverUrl, creds.loginName, creds.appPassword, c.transport)
                val found = client.discover()
                val account = Account(creds.serverUrl, creds.loginName, found.principal, found.home)
                val moved = withContext(Dispatchers.IO) {
                    c.secrets.put(c.sync.accountKey(account), creds.appPassword)
                    c.store.saveAccount(account)
                    // Ohne Anmeldung angelegte Listen gehen ins Konto und werden beim folgenden Abgleich hochgeladen.
                    c.store.adoptLocalLists(found.home)
                }
                prefs.remapLists(moved)
                moved[selection.value]?.let { selection.value = it }
                moved[startList.value]?.let { startList.value = it }
                prefs.signedIn = true
                prefs.onboarded = true
                signedIn.value = true
                onboarded.value = true
                login.value = LoginState.Idle
                screen.value = Screen.MAIN
                c.sync.ensurePeriodic()
                c.sync.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError) {
                login.value = LoginState.Failed(app.getString(R.string.login_failed, e.message ?: ""))
            } catch (e: Exception) {
                login.value = LoginState.Failed(app.getString(R.string.login_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** „Ohne Anmeldung nutzen“: lokal arbeiten, eine erste Liste anlegen. */
    fun skipLogin() {
        prefs.onboarded = true
        onboarded.value = true
        screen.value = Screen.MAIN
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { c.store.lists().isEmpty() }) {
                repo.createList(c.app.getString(R.string.default_list_name), null)?.let { select(it.href) }
            }
        }
    }

    fun cancelLogin() { loginJob?.cancel(); login.value = LoginState.Idle }

    fun logout() {
        viewModelScope.launch {
            c.sync.cancelAll()
            withContext(Dispatchers.IO) {
                c.store.account()?.let { c.secrets.remove(c.sync.accountKey(it)) }
                c.store.wipe()
                c.reminders.reschedule()
            }
            prefs.clearAccountData()
            c.planwerkLinks.value = emptyMap()
            c.syncStatus.value = SyncStatus()
            c.widgets.update()
            signedIn.value = false
            onboarded.value = false
            selection.value = AppPrefs.ALL
            screen.value = Screen.MAIN
        }
    }

    // MARK: Planwerk

    val planwerkUrl = MutableStateFlow(prefs.planwerkUrl)
    val planwerkTokenSaved = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.IO) { planwerkTokenSaved.value = !c.secrets.get(SyncRunner.PLANWERK_TOKEN).isNullOrBlank() }
    }

    fun savePlanwerk(url: String, token: String) {
        viewModelScope.launch {
            val app = c.app
            val cleanUrl = url.trim().trimEnd('/')
            if (cleanUrl.isEmpty()) {
                prefs.planwerkUrl = ""
                withContext(Dispatchers.IO) { c.secrets.remove(SyncRunner.PLANWERK_TOKEN) }
                planwerkUrl.value = ""; planwerkTokenSaved.value = false
                c.planwerkLinks.value = emptyMap(); prefs.planwerkLinksJson = ""
                events.emit(UiEvent.Message(app.getString(R.string.planwerk_removed)))
                return@launch
            }
            val finalUrl = if (cleanUrl.contains("://")) cleanUrl else "https://$cleanUrl"
            val effectiveToken = token.trim().ifEmpty { withContext(Dispatchers.IO) { c.secrets.get(SyncRunner.PLANWERK_TOKEN) }.orEmpty() }
            try {
                val rev = PlanwerkClient(finalUrl, effectiveToken, c.transport).currentRevision()
                prefs.planwerkUrl = finalUrl
                planwerkUrl.value = finalUrl
                withContext(Dispatchers.IO) { c.secrets.put(SyncRunner.PLANWERK_TOKEN, effectiveToken) }
                planwerkTokenSaved.value = true
                events.emit(UiEvent.Message(app.getString(R.string.planwerk_connected, rev)))
                c.sync.schedule(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError.Unauthorized) {
                events.emit(UiEvent.Message(app.getString(R.string.planwerk_token_rejected)))
            } catch (e: Exception) {
                events.emit(UiEvent.Message(app.getString(R.string.planwerk_failed, e.message ?: "")))
            }
        }
    }

    val lastSyncAt: Long get() = prefs.lastSyncAt
    fun account(): Flow<Account?> = signedIn.map { if (it) repo.account() else null }
}
