package app.listwerk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.listwerk.android.R
import app.listwerk.core.TaskList
import app.listwerk.core.TaskRecord
import kotlinx.coroutines.launch

/**
 * Eintragsliste der Hauptebene mit Unteraufgaben. Umsortieren per Ziehen am Griff (nur auf der Hauptebene, nur in
 * beschreibbaren Einzellisten). Die Reihenfolge während des Ziehens wird lokal gehalten und erst beim Loslassen
 * in den Speicher geschrieben (`Store.reorder`).
 */
@Composable
fun TaskListContent(
    groups: List<TaskGroup>,
    lists: Map<String, TaskList>,
    showListName: Boolean,
    canReorder: Boolean,
    contentPadding: PaddingValues,
    onOpen: (TaskRecord) -> Unit,
    onToggle: (TaskRecord) -> Unit,
    onDelete: (TaskRecord) -> Unit,
    onReorder: (uid: String, afterUid: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Darstellung wie Microsoft To Do: Karten, Stern, Unteraufgaben als Zähler statt eigener Zeilen. */
    todoStyle: Boolean = false,
    myDay: Set<String> = emptySet(),
    onStar: (TaskRecord) -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf(groups.map { it.key }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var staleWhileDragging by remember { mutableStateOf(false) }

    LaunchedEffect(groups) {
        if (dragging == null) order = groups.map { it.key } else staleWhileDragging = true
    }

    val byUid = remember(groups) { groups.associateBy { it.key } }
    val shown = order.mapNotNull { byUid[it] }

    LazyColumn(modifier = modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        items(shown, key = { it.key }) { g ->
            val isDragged = g.key == dragging
            Box(
                Modifier
                    .then(if (todoStyle) Modifier.padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)) else Modifier)
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                    .then(if (isDragged) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier),
            ) {
                Column {
                    SwipeRow(g.rec, onDelete, todoStyle) {
                        val handle: (@Composable () -> Unit)? = if (canReorder) {
                            {
                                DragHandle(
                                    onStart = { dragging = g.key; dragOffset = 0f; staleWhileDragging = false },
                                    onDrag = { dy ->
                                        dragOffset += dy
                                        val info = listState.layoutInfo.visibleItemsInfo
                                        val cur = info.firstOrNull { it.key == dragging } ?: return@DragHandle
                                        val center = cur.offset + cur.size / 2f + dragOffset
                                        val target = info.firstOrNull { it.key != dragging && it.key is String && center >= it.offset && center <= it.offset + it.size }
                                        if (target != null) {
                                            val from = order.indexOf(dragging)
                                            val to = order.indexOf(target.key as String)
                                            if (from >= 0 && to >= 0) {
                                                val visual = cur.offset + dragOffset
                                                val newBase = if (to > from) target.offset + target.size - cur.size else target.offset
                                                order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                dragOffset = visual - newBase
                                            }
                                        }
                                        // Automatisch scrollen am Rand
                                        val viewportTop = listState.layoutInfo.viewportStartOffset
                                        val viewportBottom = listState.layoutInfo.viewportEndOffset
                                        val vis = cur.offset + dragOffset
                                        val step = when {
                                            vis < viewportTop + 40 -> -24f
                                            vis + cur.size > viewportBottom - 40 -> 24f
                                            else -> 0f
                                        }
                                        if (step != 0f) scope.launch { dragOffset += listState.scrollBy(step) }
                                    },
                                    onEnd = {
                                        val key = dragging
                                        if (key != null) {
                                            val idx = order.indexOf(key)
                                            val original = groups.map { it.key }
                                            // Umsortieren gibt es nur in einer Einzelliste: dort ist die UID eindeutig.
                                            if (order != original && idx >= 0) byUid[key]?.let { moved ->
                                                onReorder(moved.uid, if (idx == 0) null else byUid[order[idx - 1]]?.uid)
                                            }
                                        }
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                )
                            }
                        } else null
                        if (todoStyle) {
                            TodoRowView(g, if (showListName) lists[g.rec.listHref]?.name else null, g.uid in myDay, onOpen, onToggle, onStar, handle)
                        } else TaskRowView(
                            rec = g.rec,
                            listName = if (showListName) lists[g.rec.listHref]?.name else null,
                            indent = false,
                            onOpen = onOpen,
                            onToggle = onToggle,
                            handle = handle,
                        )
                    }
                    if (!todoStyle) for (child in g.children) {
                        SwipeRow(child, onDelete) {
                            TaskRowView(rec = child, listName = null, indent = true, onOpen = onOpen, onToggle = onToggle, handle = null)
                        }
                    }
                }
            }
        }
        footer()
    }
}

@Composable
private fun DragHandle(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = "Ziehen zum Umsortieren" }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount.y) },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(rec: TaskRecord, onDelete: (TaskRecord) -> Unit, todoStyle: Boolean = false, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(rec); true } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(if (todoStyle) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.background)) { content() }
    }
}

@Composable
fun TaskRowView(
    rec: TaskRecord,
    listName: String?,
    indent: Boolean,
    onOpen: (TaskRecord) -> Unit,
    onToggle: (TaskRecord) -> Unit,
    handle: (@Composable () -> Unit)?,
) {
    val colors = MaterialTheme.listwerk
    val item = rec.item
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onOpen(rec) }
            .padding(start = if (indent) 36.dp else 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(item.color?.let { colors.entryColor(it) } ?: androidx.compose.ui.graphics.Color.Transparent),
        )
        IconButton(onClick = { onToggle(rec) }) {
            Icon(
                if (item.isCompleted) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = stringResource(if (item.isCompleted) R.string.action_reopen else R.string.action_complete),
                tint = if (item.isCompleted) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.title.ifEmpty { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                item.due?.let { add(Format.due(context, it) to (!item.isCompleted && Format.isOverdue(it))) }
                listName?.let { add(it to false) }
            }
            if (sub.isNotEmpty()) {
                Row {
                    sub.forEachIndexed { i, (text, overdue) ->
                        if (i > 0) Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text, style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (item.priority in 1..4) Icon(Icons.Default.PriorityHigh, stringResource(R.string.priority_high), Modifier.size(18.dp), tint = colors.warning)
        if (item.reminders.isNotEmpty()) Icon(Icons.Default.Notifications, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.url != null) Icon(Icons.Default.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.recurrence != null) Icon(Icons.Default.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rec.isDirty) Icon(Icons.Default.CloudUpload, stringResource(R.string.not_synced), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        handle?.invoke()
    }
}
