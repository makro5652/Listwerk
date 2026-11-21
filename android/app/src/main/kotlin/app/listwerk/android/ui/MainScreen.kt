package app.listwerk.android.ui

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.listwerk.android.R
import app.listwerk.android.data.AppPrefs
import app.listwerk.core.ICalDate
import app.listwerk.core.TaskList
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("LocalContextGetResourceValueCall") // Sprache wird zur Laufzeit nicht gewechselt; Strings werden in einem Ereignis-Collector gebraucht
@Composable
fun MainScreen(vm: MainViewModel, onRelogin: () -> Unit) {
    val colors = MaterialTheme.listwerk
    val lists by vm.lists.collectAsState()
    val groups by vm.groups.collectAsState()
    val selection by vm.selection.collectAsState()
    val currentList by vm.currentList.collectAsState()
    val entryTarget by vm.entryTarget.collectAsState()
    val counts by vm.openCounts.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val detail by vm.detail.collectAsState()
    val focusInput by vm.focusInput.collectAsState()
    val showCompleted by vm.showCompleted.collectAsState()
    val planwerkLinks by vm.planwerkLinks.collectAsState()
    val smartCounts by vm.smartCounts.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val query by vm.search.collectAsState()
    var sortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }

    var listDialog by remember { mutableStateOf<ListDialogMode?>(null) }
    var confirmDelete by remember { mutableStateOf<TaskList?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Ereignisse: Rückgängig-Snackbar, Meldungen
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

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val listMap = remember(lists) { lists.orEmpty().associateBy { it.href } }
    val favorite by vm.startList.collectAsState()
    var arranging by remember { mutableStateOf(false) }
    if (arranging) ListOrderDialog(vm, lists.orEmpty(), favorite) { arranging = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = colors.sidebar) {
                DrawerContent(
                    lists = lists.orEmpty(), counts = counts, smartCounts = smartCounts, selection = selection, favorite = favorite, planwerkLinked = { it.isPlanwerkLinked },
                    onArrange = { scope.launch { drawerState.close() }; arranging = true },
                    onSelect = { vm.select(it); scope.launch { drawerState.close() } },
                    onNewList = { scope.launch { drawerState.close() }; listDialog = ListDialogMode.Create },
                    onSettings = { scope.launch { drawerState.close() }; vm.screen.value = Screen.SETTINGS },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = screenColor(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            currentList?.color?.let { ColorDot(colors.entryColor(it), 12.dp); Spacer(Modifier.width(8.dp)) }
                            if (query.isNotEmpty()) {
                                androidx.compose.material3.TextField(
                                    value = query.trimStart(), onValueChange = { vm.search.value = it.ifEmpty { " " } }, singleLine = true,
                                    placeholder = { Text(stringResource(R.string.search)) },
                                )
                            } else Text(if (selection == AppPrefs.ALL) stringResource(R.string.all_open) else selectionTitle(selection, currentList), maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, stringResource(R.string.open_lists))
                        }
                    },
                    actions = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.search.value = "" }) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
                        else IconButton(onClick = { vm.search.value = " " }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                        if (signedIn) {
                            if (sync.isSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = { vm.syncNow() }) { Icon(Icons.Default.Sync, stringResource(R.string.action_sync)) }
                        }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for ((mode, label) in sortLabels) DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                leadingIcon = { if (mode == sortMode) Icon(Icons.Default.Check, null) },
                                onClick = { sortMenu = false; vm.setSort(mode) },
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (showCompleted) R.string.hide_completed else R.string.show_completed)) },
                                onClick = { menuOpen = false; vm.setShowCompleted(!showCompleted) },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.sort_by)) }, onClick = { menuOpen = false; sortMenu = true })
                            currentList?.let { l ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_list)) },
                                    enabled = !l.isShared || l.canWrite,
                                    onClick = { menuOpen = false; listDialog = ListDialogMode.Edit(l) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_list)) },
                                    onClick = { menuOpen = false; confirmDelete = l },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = { menuOpen = false; vm.screen.value = Screen.SETTINGS },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = screenColor()),
                )
            },
            bottomBar = {
                if (selection != AppPrefs.COMPLETED) EntryBar(
                    targetName = entryTarget?.name,
                    enabled = entryTarget != null,
                    focusOnStart = focusInput,
                    onSubmit = { title, color, due -> vm.addTask(title, color, due) },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (sync.authRequired) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_auth_required), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRelogin) { Text(stringResource(R.string.login_again)) }
                        }
                    }
                } else if (sync.error != null) {
                    Text(
                        sync.error.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.danger,
                    )
                }
                currentList?.let { l ->
                    if (!l.canWrite || l.isShared || l.isPlanwerkLinked) ListBadges(l)
                }
                val g = groups
                when {
                    g == null -> Box(Modifier.fillMaxSize())
                    g.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (lists.orEmpty().isEmpty()) R.string.empty_no_lists else R.string.empty_list),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp),
                        )
                    }
                    else -> TaskListContent(
                        groups = g, lists = listMap, showListName = AppPrefs.isSmart(selection),
                        canReorder = currentList?.canWrite == true && sortMode == SortMode.MANUAL && query.isEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                        onOpen = { vm.openDetail(it) }, onToggle = { vm.toggleDone(it) }, onDelete = { vm.delete(it) },
                        onReorder = { uid, after -> vm.reorder(uid, after) },
                    )
                }
            }
        }
    }

    // Detailblatt
    detail?.let { (listHref, uid) ->
        DetailSheet(
            vm = vm, listHref = listHref, uid = uid, lists = lists.orEmpty(),
            link = planwerkLinks[listHref],
            onNeedNotificationPermission = ::ensureNotificationPermission,
            onDismiss = { vm.closeDetail() },
        )
    }

    listDialog?.let { mode ->
        ListEditDialog(
            mode = mode, onDismiss = { listDialog = null },
            onConfirm = { name, color ->
                when (mode) {
                    ListDialogMode.Create -> vm.createList(name, color)
                    is ListDialogMode.Edit -> vm.editList(mode.list, name, color)
                }
                listDialog = null
            },
        )
    }

    confirmDelete?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_list_title, l.name)) },
            text = { Text(stringResource(R.string.delete_list_message)) },
            confirmButton = { TextButton(onClick = { vm.deleteList(l); confirmDelete = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ListBadges(l: TaskList) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!l.canWrite) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" " + stringResource(R.string.read_only), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        if (l.isShared) {
            Icon(Icons.Default.Group, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                " " + (l.ownerName?.let { stringResource(R.string.shared_by, it) } ?: stringResource(R.string.shared)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
        if (l.isPlanwerkLinked) {
            Icon(Icons.Default.Link, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" " + stringResource(R.string.planwerk_linked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ColorDot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun DrawerContent(
    lists: List<TaskList>,
    counts: Map<String, Int>,
    smartCounts: Map<String, Int>,
    selection: String,
    favorite: String,
    planwerkLinked: (TaskList) -> Boolean,
    onArrange: () -> Unit,
    onSelect: (String) -> Unit,
    onNewList: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.listwerk
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp, 24.dp, 16.dp, 12.dp))
        }
        items(smartLists, key = { it.id }) { s ->
            NavigationDrawerItem(
                icon = { Icon(s.icon, null, tint = s.tint) },
                label = { Text(if (s.id == AppPrefs.ALL) stringResource(R.string.all_open) else stringResource(s.name)) },
                selected = selection == s.id,
                badge = { smartCounts[s.id]?.let { Text(it.toString()) } },
                onClick = { onSelect(s.id) },
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        items(lists, key = { it.href }) { l ->
            NavigationDrawerItem(
                icon = { ColorDot(l.color?.let { colors.entryColor(it) } ?: MaterialTheme.colorScheme.outline, 14.dp) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.weight(1f, fill = false), maxLines = 1)
                        if (l.href == favorite) Icon(Icons.Default.Star, stringResource(R.string.favorite_list), Modifier.padding(start = 4.dp).size(14.dp), tint = FavoriteColor)
                    }
                },
                selected = selection == l.href,
                badge = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!l.canWrite) Icon(Icons.Default.Lock, null, Modifier.size(14.dp))
                        if (l.isShared) Icon(Icons.Default.Group, null, Modifier.size(14.dp))
                        if (planwerkLinked(l)) Icon(Icons.Default.Link, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text((counts[l.href] ?: 0).toString())
                    }
                },
                onClick = { onSelect(l.href) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Add, null) }, label = { Text(stringResource(R.string.new_list)) },
                selected = false, onClick = onNewList,
            )
            if (lists.size > 1) NavigationDrawerItem(
                icon = { Icon(Icons.Default.SwapVert, null) }, label = { Text(stringResource(R.string.arrange_lists)) },
                selected = false, onClick = onArrange,
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.settings)) },
                selected = false, onClick = onSettings,
            )
        }
    }
}

/**
 * Eingabefeld unten, sofort nutzbar. Farbe und Fälligkeit (Heute / Morgen / Datum) sind mit je einem Tipp wählbar
 * und gelten für den nächsten Eintrag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryBar(
    targetName: String?,
    enabled: Boolean,
    focusOnStart: Boolean,
    onSubmit: (title: String, color: String?, due: ICalDate?) -> Unit,
) {
    val colors = MaterialTheme.listwerk
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<String?>(null) }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var palette by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) { if (focusOnStart && enabled) runCatching { focus.requestFocus() } }

    fun submit() {
        if (text.isBlank() || !enabled) return
        onSubmit(text, color, due?.let { ICalDate.day(it.year, it.monthValue, it.dayOfMonth) })
        text = ""; color = null; due = null; palette = false
    }

    Surface(color = colors.surfaceElevated, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            AnimatedVisibility(palette) {
                LazyRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val all = colors.catalog.palettes.flatMap { it.colors }
                    items(all, key = { it.id }) { c ->
                        ColorSwatch(colors.entryColor(c.storedHex), selected = color.equals(c.storedHex, true), label = c.name) {
                            color = if (color.equals(c.storedHex, true)) null else c.storedHex
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { palette = !palette }, modifier = Modifier.size(40.dp)) {
                    if (color != null) ColorDot(colors.entryColor(color!!), 22.dp)
                    else Icon(Icons.Default.Palette, stringResource(R.string.entry_color))
                }
                val today = LocalDate.now()
                FilterChip(selected = due == today, onClick = { due = if (due == today) null else today }, label = { Text(stringResource(R.string.today)) })
                FilterChip(
                    selected = due == today.plusDays(1), onClick = { due = if (due == today.plusDays(1)) null else today.plusDays(1) },
                    label = { Text(stringResource(R.string.tomorrow)) },
                )
                FilterChip(
                    selected = due != null && due != today && due != today.plusDays(1),
                    onClick = { datePicker = true },
                    label = {
                        Text(due?.takeIf { it != today && it != today.plusDays(1) }?.let { Format.date(it) } ?: stringResource(R.string.date_ellipsis))
                    },
                    leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    enabled = enabled, singleLine = true,
                    placeholder = {
                        Text(if (targetName != null) stringResource(R.string.new_entry_in, targetName) else stringResource(R.string.new_entry_disabled))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                IconButton(onClick = ::submit, enabled = enabled && text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_add), tint = if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (datePicker) {
        DatePickerDialogSimple(
            initial = due ?: LocalDate.now(),
            onDismiss = { datePicker = false },
            onPick = { due = it; datePicker = false },
        )
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = if (color.luminanceIsLow()) Color.White else Color.Black)
    }
}

private fun Color.luminanceIsLow(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.55f

sealed interface ListDialogMode {
    data object Create : ListDialogMode
    data class Edit(val list: TaskList) : ListDialogMode
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListEditDialog(mode: ListDialogMode, onDismiss: () -> Unit, onConfirm: (String, String?) -> Unit) {
    val colors = MaterialTheme.listwerk
    val initial = (mode as? ListDialogMode.Edit)?.list
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.new_list else R.string.edit_list)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.list_name)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Listenfarbe wird als Hellwert an den Server gegeben, damit alle Clients dieselbe Farbe sehen.
                    for (c in colors.catalog.palettes.flatMap { it.colors }) {
                        ColorSwatch(colors.entryColor(c.storedHex), selected = color.equals(c.storedHex, true), label = c.name) {
                            color = if (color.equals(c.storedHex, true)) null else c.storedHex
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, color) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
