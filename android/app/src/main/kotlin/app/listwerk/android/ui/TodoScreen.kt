package app.listwerk.android.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listwerk.android.R
import app.listwerk.android.data.AppPrefs
import app.listwerk.core.ICalDate
import app.listwerk.core.TaskList
import app.listwerk.core.TaskRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Intelligente Listen in der Reihenfolge und mit den Symbolfarben von Microsoft To Do. */
data class SmartList(val id: String, val name: Int, val icon: ImageVector, val tint: Color)

val smartLists = listOf(
    SmartList(AppPrefs.MY_DAY, R.string.smart_my_day, Icons.Default.WbSunny, Color(0xFF7A7574)),
    SmartList(AppPrefs.IMPORTANT, R.string.smart_important, Icons.Default.Star, Color(0xFFC6427F)),
    SmartList(AppPrefs.PLANNED, R.string.smart_planned, Icons.Default.CalendarMonth, Color(0xFF138A72)),
    SmartList(AppPrefs.ALL, R.string.smart_all, Icons.Default.AllInclusive, Color(0xFFD13438)),
    SmartList(AppPrefs.COMPLETED, R.string.smart_completed, Icons.Outlined.CheckCircle, Color(0xFF2E7D32)),
)

@Composable
fun selectionTitle(selection: String, list: TaskList?): String =
    smartLists.firstOrNull { it.id == selection }?.let { stringResource(it.name) } ?: list?.name ?: ""

/**
 * Darstellung im Stil von Microsoft To Do: Startseite mit intelligenten Listen und eigenen Listen, Listenansicht mit
 * farbigem Hintergrund, Karten, Stern und eingeklapptem Abschnitt „Erledigt“. Alle Funktionen der Listwerk-Darstellung
 * (Farben, Planwerk, Unteraufgaben, Ziehen, Wischen, Erinnerungen) bleiben über dieselben Bausteine erreichbar.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun TodoScreen(vm: MainViewModel, onRelogin: () -> Unit) {
    // Startseite (Listenübersicht) oder geöffnete Liste. Nach dem Start wie in To Do direkt in der letzten Liste.
    var home by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { ev ->
            when (ev) {
                is UiEvent.Undo -> {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.deleted_snackbar, ev.title.ifEmpty { context.getString(R.string.untitled) }),
                        actionLabel = context.getString(R.string.action_undo), duration = SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undo(ev.undo)
                }
                is UiEvent.Message -> snackbar.showSnackbar(ev.text)
                is UiEvent.OpenUrl -> Unit
            }
        }
    }
    if (home) TodoHome(vm, snackbar, onOpen = { vm.select(it); home = false })
    else {
        BackHandler { home = true }
        TodoListView(vm, snackbar, onRelogin, onBack = { home = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoHome(vm: MainViewModel, snackbar: SnackbarHostState, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.listwerk
    val lists by vm.lists.collectAsState()
    val counts by vm.openCounts.collectAsState()
    val smart by vm.smartCounts.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    var newList by remember { mutableStateOf(false) }
    val favorite by vm.startList.collectAsState()
    var arranging by remember { mutableStateOf(false) }
    if (arranging) ListOrderDialog(vm, lists.orEmpty(), favorite) { arranging = false }

    Scaffold(
        containerColor = screenColor(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (signedIn) {
                        if (sync.isSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = vm::syncNow) { Icon(Icons.Default.Sync, stringResource(R.string.action_sync)) }
                    }
                    IconButton(onClick = { vm.select(AppPrefs.ALL); vm.search.value = " "; onOpen(AppPrefs.ALL) }) {
                        Icon(Icons.Default.Search, stringResource(R.string.search))
                    }
                    if (lists.orEmpty().size > 1) IconButton(onClick = { arranging = true }) { Icon(Icons.Default.SwapVert, stringResource(R.string.arrange_lists)) }
                    IconButton(onClick = { vm.screen.value = Screen.SETTINGS }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = screenColor()),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                TextButton(onClick = { newList = true }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_list), Modifier.weight(1f))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(smartLists, key = { it.id }) { s ->
                HomeRow(s.icon, s.tint, stringResource(s.name), smart[s.id]) { onOpen(s.id) }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            items(lists.orEmpty(), key = { it.href }) { l ->
                HomeRow(
                    Icons.AutoMirrored.Filled.List, l.color?.let { colors.entryColor(it) } ?: MaterialTheme.colorScheme.primary,
                    l.name, counts[l.href]?.takeIf { it > 0 }, favorite = l.href == favorite,
                ) { onOpen(l.href) }
            }
        }
    }
    if (newList) {
        ListEditDialog(ListDialogMode.Create, onDismiss = { newList = false }) { name, color ->
            vm.createList(name, color); newList = false; onOpen(AppPrefs.ALL)
        }
    }
}

@Composable
private fun HomeRow(icon: ImageVector, tint: Color, name: String, count: Int?, favorite: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (favorite) Icon(Icons.Default.Star, stringResource(R.string.favorite_list), Modifier.padding(start = 6.dp).size(16.dp), tint = FavoriteColor)
        Spacer(Modifier.weight(1f))
        if (count != null && count > 0) Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListView(vm: MainViewModel, snackbar: SnackbarHostState, onRelogin: () -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.listwerk
    val lists by vm.lists.collectAsState()
    val groups by vm.groups.collectAsState()
    val selection by vm.selection.collectAsState()
    val currentList by vm.currentList.collectAsState()
    val entryTarget by vm.entryTarget.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val detail by vm.detail.collectAsState()
    val showCompleted by vm.showCompleted.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val query by vm.search.collectAsState()
    val myDay by vm.myDay.collectAsState()
    val planwerkLinks by vm.planwerkLinks.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf<ListDialogMode?>(null) }
    var confirmDelete by remember { mutableStateOf<TaskList?>(null) }
    val listMap = remember(lists) { lists.orEmpty().associateBy { it.href } }

    // Hintergrund wie in To Do: Listenfarbe bzw. Farbe der intelligenten Liste, Schrift darauf in Weiß.
    val accent = smartLists.firstOrNull { it.id == selection }?.tint
        ?: currentList?.color?.let { colors.entryColor(it) } ?: MaterialTheme.colorScheme.primary
    val searching = query.isNotEmpty()

    Scaffold(
        containerColor = accent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query.trimStart(), onValueChange = { vm.search.value = it.ifEmpty { " " } }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.7f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                                focusedIndicatorColor = Color.White, unfocusedIndicatorColor = Color.White.copy(alpha = 0.5f),
                            ),
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (searching) IconButton(onClick = { vm.search.value = "" }) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
                    else IconButton(onClick = { vm.search.value = " " }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    if (signedIn) {
                        if (sync.isSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        else IconButton(onClick = vm::syncNow) { Icon(Icons.Default.Sync, stringResource(R.string.action_sync)) }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_by)) }, onClick = { menu = false; sortMenu = true })
                        currentList?.let { l ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_list)) }, enabled = !l.isShared || l.canWrite, onClick = { menu = false; listDialog = ListDialogMode.Edit(l) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_list)) }, onClick = { menu = false; confirmDelete = l })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; vm.screen.value = Screen.SETTINGS })
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        for ((mode, label) in sortLabels) {
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                leadingIcon = { if (mode == sortMode) Icon(Icons.Default.CheckCircle, null) },
                                onClick = { sortMenu = false; vm.setSort(mode) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = accent, navigationIconContentColor = Color.White, actionIconContentColor = Color.White, titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (selection != AppPrefs.COMPLETED) TodoEntryBar(entryTarget?.name, entryTarget != null) { t, c, d -> vm.addTask(t, c, d) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                selectionTitle(selection, currentList), color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp), maxLines = 2,
            )
            if (selection == AppPrefs.MY_DAY) {
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            if (sync.authRequired) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WarningAmber, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_auth_required), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRelogin) { Text(stringResource(R.string.login_again)) }
                    }
                }
            } else if (sync.error != null) {
                Text(sync.error.orEmpty(), Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
            Spacer(Modifier.size(10.dp))
            val all = groups.orEmpty()
            val open = all.filter { !it.rec.item.isCompleted || selection == AppPrefs.COMPLETED }
            val done = if (selection == AppPrefs.COMPLETED) emptyList() else all.filter { it.rec.item.isCompleted }
            TaskListContent(
                groups = open, lists = listMap, showListName = AppPrefs.isSmart(selection),
                canReorder = currentList?.canWrite == true && sortMode == SortMode.MANUAL && !searching,
                contentPadding = PaddingValues(bottom = 12.dp),
                onOpen = { vm.openDetail(it) }, onToggle = { vm.toggleDone(it) }, onDelete = { vm.delete(it) },
                onReorder = { uid, after -> vm.reorder(uid, after) },
                todoStyle = true, myDay = myDay, onStar = { vm.toggleImportant(it) },
                footer = {
                    if (done.isNotEmpty()) {
                        item(key = "@done-header") {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.18f)).clickable { vm.setShowCompleted(!showCompleted) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (showCompleted) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = Color.White)
                                Text(stringResource(R.string.smart_completed) + "  " + done.size, color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (showCompleted) items(done, key = { "@done-" + it.key }) { g ->
                            Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp))) {
                                SwipeRow(g.rec, { vm.delete(it) }, todoStyle = true) {
                                    TodoRowView(g, if (AppPrefs.isSmart(selection)) listMap[g.rec.listHref]?.name else null, g.uid in myDay, { vm.openDetail(it) }, { vm.toggleDone(it) }, { vm.toggleImportant(it) }, null)
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    detail?.let { (listHref, uid) ->
        DetailSheet(vm = vm, listHref = listHref, uid = uid, lists = lists.orEmpty(), link = planwerkLinks[listHref], onNeedNotificationPermission = {}, onDismiss = { vm.closeDetail() })
    }
    listDialog?.let { mode ->
        ListEditDialog(mode, onDismiss = { listDialog = null }) { name, color ->
            if (mode is ListDialogMode.Edit) vm.editList(mode.list, name, color) else vm.createList(name, color)
            listDialog = null
        }
    }
    confirmDelete?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_list_title, l.name)) },
            text = { Text(stringResource(R.string.delete_list_message)) },
            confirmButton = { TextButton(onClick = { vm.deleteList(l); confirmDelete = null; onBack() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

val sortLabels = listOf(
    SortMode.MANUAL to R.string.sort_manual,
    SortMode.IMPORTANCE to R.string.sort_importance,
    SortMode.DUE to R.string.sort_due,
    SortMode.ALPHA to R.string.sort_alpha,
    SortMode.CREATED to R.string.sort_created,
)

/** Eine Aufgabe als To-Do-Karte: Kreis, Titel, Unterzeile (Mein Tag · Liste · Fälligkeit · Schritte · Symbole), Stern. */
@Composable
fun TodoRowView(
    g: TaskGroup,
    listName: String?,
    inMyDay: Boolean,
    onOpen: (TaskRecord) -> Unit,
    onToggle: (TaskRecord) -> Unit,
    onStar: (TaskRecord) -> Unit,
    handle: (@Composable () -> Unit)?,
) {
    val colors = MaterialTheme.listwerk
    val context = LocalContext.current
    val item = g.rec.item
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable { onOpen(g.rec) }.padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onToggle(g.rec) }) {
            CheckMark(
                item.isCompleted,
                item.color?.let { colors.entryColor(it) } ?: if (item.isCompleted) colors.success else muted,
                stringResource(if (item.isCompleted) R.string.action_reopen else R.string.action_complete),
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                item.title.ifEmpty { stringResource(R.string.untitled) }, style = MaterialTheme.typography.bodyLarge,
                color = if (item.isCompleted) muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val parts = buildList<Pair<String, Color>> {
                if (inMyDay) add("☀ " + stringResource(R.string.smart_my_day) to muted)
                listName?.let { add(it to muted) }
                item.due?.let { add(Format.due(context, it) to if (!item.isCompleted && Format.isOverdue(it)) colors.danger else muted) }
                if (g.children.isNotEmpty()) add(stringResource(R.string.steps_count, g.children.count { it.item.isCompleted }, g.children.size) to muted)
                if (item.tags.isNotEmpty()) add(item.tags.joinToString(", ") to muted)
            }
            val icons = listOfNotNull(
                Icons.Default.Notifications.takeIf { item.reminders.isNotEmpty() },
                Icons.Default.Repeat.takeIf { item.recurrence != null },
                Icons.AutoMirrored.Outlined.Notes.takeIf { item.notes.isNotBlank() },
            )
            if (parts.isNotEmpty() || icons.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    parts.forEachIndexed { i, (t, c) ->
                        if (i > 0) Text(" · ", style = MaterialTheme.typography.bodySmall, color = muted)
                        Text(t, style = MaterialTheme.typography.bodySmall, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    for (ic in icons) { Spacer(Modifier.width(6.dp)); Icon(ic, null, Modifier.size(13.dp), tint = muted) }
                }
            }
        }
        IconButton(onClick = { onStar(g.rec) }) {
            val on = item.priority in 1..4
            Icon(if (on) Icons.Default.Star else Icons.Outlined.StarOutline, stringResource(R.string.smart_important), tint = if (on) MaterialTheme.colorScheme.primary else muted)
        }
        handle?.invoke()
    }
}

/** „+ Aufgabe hinzufügen“ wie in To Do; darunter beim Tippen Fälligkeit und Farbe. */
@Composable
private fun TodoEntryBar(targetName: String?, enabled: Boolean, onSubmit: (String, String?, ICalDate?) -> Unit) {
    val colors = MaterialTheme.listwerk
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var color by remember { mutableStateOf<String?>(null) }
    var palette by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    fun submit() {
        if (text.isBlank() || !enabled) return
        onSubmit(text, color, due?.let { ICalDate.day(it.year, it.monthValue, it.dayOfMonth) })
        text = ""; due = null; color = null; palette = false
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, Modifier.padding(start = 14.dp), tint = MaterialTheme.colorScheme.primary)
                    TextField(
                        value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(if (targetName != null) stringResource(R.string.add_task) else stringResource(R.string.new_entry_disabled)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                }
                if (text.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val today = LocalDate.now()
                        FilterChip(selected = due == today, onClick = { due = if (due == today) null else today }, label = { Text(stringResource(R.string.today)) })
                        FilterChip(selected = due == today.plusDays(1), onClick = { due = if (due == today.plusDays(1)) null else today.plusDays(1) }, label = { Text(stringResource(R.string.tomorrow)) })
                        FilterChip(
                            selected = due != null && due != today && due != today.plusDays(1), onClick = { datePicker = true },
                            label = { Text(due?.takeIf { it != today && it != today.plusDays(1) }?.let { Format.date(it) } ?: stringResource(R.string.date_ellipsis)) },
                        )
                        IconButton(onClick = { palette = !palette }) {
                            if (color != null) ColorDot(colors.entryColor(color!!), 20.dp) else Icon(Icons.Default.Palette, stringResource(R.string.entry_color))
                        }
                    }
                    if (palette) {
                        androidx.compose.foundation.lazy.LazyRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(colors.catalog.palettes.flatMap { it.colors }, key = { it.id }) { c ->
                                ColorSwatch(colors.entryColor(c.storedHex), selected = color.equals(c.storedHex, true), label = c.name) {
                                    color = if (color.equals(c.storedHex, true)) null else c.storedHex
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (datePicker) DatePickerDialogSimple(due ?: LocalDate.now(), onDismiss = { datePicker = false }, onPick = { due = it; datePicker = false })
}
