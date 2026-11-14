package app.listwerk.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.listwerk.android.R
import app.listwerk.core.ICalDate
import app.listwerk.core.PlanwerkKind
import app.listwerk.core.PlanwerkLink
import app.listwerk.core.PlanwerkMeta
import app.listwerk.core.Reminder
import app.listwerk.core.ReminderTrigger
import app.listwerk.core.TaskList
import app.listwerk.core.TaskRecord
import app.listwerk.core.TodoItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    vm: MainViewModel,
    listHref: String,
    uid: String,
    lists: List<TaskList>,
    link: PlanwerkLink?,
    onNeedNotificationPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val record by remember(listHref, uid) { vm.recordFlow(listHref, uid) }.collectAsState(initial = null)
    val rec = record
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        if (rec != null) {
            DetailContent(vm, rec, lists, link, onNeedNotificationPermission, onDismiss)
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    vm: MainViewModel,
    rec: TaskRecord,
    lists: List<TaskList>,
    link: PlanwerkLink?,
    onNeedNotificationPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.listwerk
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val list = lists.firstOrNull { it.href == rec.listHref }
    val editable = list?.canWrite ?: false
    val planwerkList = list?.isPlanwerkLinked == true
    val children by remember(rec.listHref, rec.item.uid) { vm.childrenFlow(rec.listHref, rec.item.uid) }.collectAsState(initial = emptyList())

    // Entwurf: Änderungen werden nach kurzer Pause gespeichert (wie in der Referenz), spätestens beim Schließen.
    var draft by remember(rec.item.uid) { mutableStateOf(rec.item) }
    var baseline by remember(rec.item.uid) { mutableStateOf(rec.item) }
    val currentDraft by rememberUpdatedState(draft)
    val currentBaseline by rememberUpdatedState(baseline)
    LaunchedEffect(draft) {
        if (draft != baseline && editable) {
            delay(500)
            vm.save(draft, rec.listHref)
            baseline = draft
        }
    }
    DisposableEffect(rec.item.uid) {
        onDispose { if (editable && currentDraft != currentBaseline) vm.save(currentDraft, rec.listHref) }
    }

    var tagsText by remember(rec.item.uid) { mutableStateOf(rec.item.tags.joinToString(", ")) }
    var newSubtask by remember { mutableStateOf("") }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    fun setDue(day: LocalDate?, time: LocalTime?) {
        draft = draft.copy(
            due = when {
                day == null -> null
                time == null || planwerkList -> ICalDate.day(day.year, day.monthValue, day.dayOfMonth)
                else -> ICalDate.of(day.atTime(time).atZone(zone).toInstant(), allDay = false)
            },
        )
    }

    val dueDay = draft.due?.localDay(zone)
    val dueTime = draft.due?.takeIf { !it.isAllDay }?.instant(zone)?.atZone(zone)?.toLocalTime()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!editable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp))
                Text(" " + stringResource(R.string.read_only_list), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.isCompleted, enabled = editable, onCheckedChange = { done ->
                draft = draft.copy(isCompleted = done, completedAt = if (done) Instant.now() else null, percentComplete = if (done) 100 else 0)
            })
            OutlinedTextField(
                value = draft.title, onValueChange = { draft = draft.copy(title = it) }, enabled = editable,
                modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.title)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }

        OutlinedTextField(
            value = draft.notes, onValueChange = { draft = draft.copy(notes = it) }, enabled = editable,
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notes)) }, minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        // Fälligkeit
        SectionTitle(stringResource(R.string.due))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val today = LocalDate.now()
            FilterChip(selected = dueDay == today, enabled = editable, onClick = { setDue(if (dueDay == today) null else today, dueTime) }, label = { Text(stringResource(R.string.today)) })
            FilterChip(selected = dueDay == today.plusDays(1), enabled = editable, onClick = { setDue(if (dueDay == today.plusDays(1)) null else today.plusDays(1), dueTime) }, label = { Text(stringResource(R.string.tomorrow)) })
            FilterChip(
                selected = dueDay != null && dueDay != today && dueDay != today.plusDays(1), enabled = editable,
                onClick = { datePicker = true },
                label = { Text(dueDay?.takeIf { it != today && it != today.plusDays(1) }?.let { Format.date(it) } ?: stringResource(R.string.date_ellipsis)) },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
            )
            if (dueDay != null) IconButton(onClick = { setDue(null, null) }, enabled = editable) { Icon(Icons.Default.Close, stringResource(R.string.remove_due)) }
        }
        if (dueDay != null && !planwerkList) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = dueTime != null, enabled = editable, onCheckedChange = { on -> setDue(dueDay, if (on) LocalTime.of(9, 0) else null) })
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.with_time))
                if (dueTime != null) {
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { timePicker = true }, enabled = editable) { Icon(Icons.Default.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(Format.time(dueTime)) }
                }
            }
        }
        if (planwerkList) Text(stringResource(R.string.planwerk_day_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        draft.recurrence?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Repeat, null, Modifier.size(16.dp))
                Text(" " + stringResource(R.string.recurring_readonly, it), style = MaterialTheme.typography.bodySmall)
            }
        }

        // Erinnerungen
        SectionTitle(stringResource(R.string.reminders))
        for ((idx, r) in draft.reminders.withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, Modifier.size(18.dp))
                Text(" " + describeReminder(r, zone), Modifier.weight(1f))
                if (!r.firesLocally) Text(stringResource(R.string.reminder_email_only), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { draft = draft.copy(reminders = draft.reminders.toMutableList().also { it.removeAt(idx) }) }, enabled = editable) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                }
            }
        }
        Box {
            AssistChip(onClick = { reminderMenu = true }, enabled = editable, label = { Text(stringResource(R.string.add_reminder)) })
            DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                val hasDue = draft.due != null
                for ((label, secs) in listOf(R.string.reminder_at_due to 0.0, R.string.reminder_15min to -900.0, R.string.reminder_1h to -3600.0, R.string.reminder_1d to -86_400.0)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) }, enabled = hasDue,
                        onClick = { reminderMenu = false; draft = draft.copy(reminders = draft.reminders + Reminder(ReminderTrigger.Relative(secs, true))); onNeedNotificationPermission() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reminder_in_1h)) },
                    onClick = { reminderMenu = false; draft = draft.copy(reminders = draft.reminders + Reminder(ReminderTrigger.Absolute(Instant.now().plusSeconds(3600)))); onNeedNotificationPermission() },
                )
            }
        }

        // Eigenschaften
        SectionTitle(stringResource(R.string.priority))
        val prios = listOf(R.string.priority_none to 0, R.string.priority_high to 1, R.string.priority_medium to 5, R.string.priority_low to 9)
        val currentPrio = when (draft.priority) { in 1..4 -> 1; 5 -> 5; in 6..9 -> 9; else -> 0 }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            prios.forEachIndexed { i, (label, value) ->
                SegmentedButton(
                    selected = currentPrio == value, enabled = editable,
                    onClick = { draft = draft.copy(priority = value) },
                    shape = SegmentedButtonDefaults.itemShape(i, prios.size),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }

        // Liste verschieben
        var listMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = listMenu, onExpandedChange = { if (editable) listMenu = it }) {
            OutlinedTextField(
                value = list?.name ?: "", onValueChange = {}, readOnly = true, enabled = editable,
                label = { Text(stringResource(R.string.list)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(listMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                for (l in lists.filter { it.canWrite || it.href == rec.listHref }) {
                    DropdownMenuItem(text = { Text(l.name) }, onClick = { listMenu = false; if (l.href != rec.listHref) vm.move(rec, l.href) })
                }
            }
        }

        if (planwerkList) {
            PlanwerkFields(draft.planwerk ?: PlanwerkMeta(), link, editable) { draft = draft.copy(planwerk = it) }
        } else {
            OutlinedTextField(
                value = tagsText, onValueChange = { tagsText = it; draft = draft.copy(tags = it.split(',').map(String::trim).filter(String::isNotEmpty)) },
                enabled = editable, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tags_hint)) }, singleLine = true,
            )
        }

        OutlinedTextField(
            value = draft.url ?: "", onValueChange = { draft = draft.copy(url = it.trim().ifEmpty { null }) },
            enabled = editable, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.link)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                val url = draft.url
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    IconButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.open_link)) }
                }
            },
        )

        // Farbe
        SectionTitle(stringResource(R.string.color))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in colors.catalog.palettes) for (c in p.colors) {
                ColorSwatch(colors.entryColor(c.storedHex), selected = draft.color.equals(c.storedHex, true), label = c.name) {
                    if (editable) draft = draft.copy(color = if (draft.color.equals(c.storedHex, true)) null else c.storedHex)
                }
            }
        }
        if (draft.color != null) TextButton(onClick = { draft = draft.copy(color = null) }, enabled = editable) { Text(stringResource(R.string.remove_color)) }

        // Unteraufgaben
        if (rec.item.parentUID == null) {
            SectionTitle(stringResource(R.string.subtasks))
            for (child in children) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = child.item.isCompleted, enabled = editable, onCheckedChange = { vm.toggleDone(child) })
                    Text(child.item.title, Modifier.weight(1f).padding(vertical = 4.dp))
                    IconButton(onClick = { vm.delete(child) }, enabled = editable) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
                }
            }
            OutlinedTextField(
                value = newSubtask, onValueChange = { newSubtask = it }, enabled = editable, singleLine = true,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.add_subtask)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newSubtask.isNotBlank()) { vm.addSubtask(rec, newSubtask); newSubtask = "" } }),
            )
        }

        // Anhänge (nur Anzeige)
        if (draft.attachments.isNotEmpty()) {
            SectionTitle(stringResource(R.string.attachments))
            for (a in draft.attachments) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                    Text(
                        " " + (a.filename ?: a.uri.ifEmpty { stringResource(R.string.attachment_inline) }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!a.isInline && a.uri.startsWith("https://")) {
                        IconButton(onClick = { runCatching { uriHandler.openUri(a.uri) } }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.open_link)) }
                    }
                }
            }
        }

        HorizontalDivider()
        if (editable) {
            TextButton(onClick = { vm.delete(rec); onDismiss() }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Delete, null, tint = colors.danger)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete), color = colors.danger)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (datePicker) {
        DatePickerDialogSimple(dueDay ?: LocalDate.now(), onDismiss = { datePicker = false }, onPick = { datePicker = false; setDue(it, dueTime) })
    }
    if (timePicker && dueDay != null) {
        TimePickerDialogSimple(dueTime ?: LocalTime.of(9, 0), onDismiss = { timePicker = false }, onPick = { timePicker = false; setDue(dueDay, it) })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun describeReminder(r: Reminder, zone: ZoneId): String = when (val t = r.trigger) {
    is ReminderTrigger.Absolute -> {
        val z = t.at.atZone(zone)
        Format.date(z.toLocalDate()) + ", " + Format.time(z.toLocalTime())
    }
    is ReminderTrigger.Relative -> {
        if (t.seconds == 0.0) stringResource(R.string.reminder_at_due)
        else {
            val minutes = (kotlin.math.abs(t.seconds) / 60).toInt()
            val text = when {
                minutes % 1440 == 0 -> stringResource(R.string.duration_days, minutes / 1440)
                minutes % 60 == 0 -> stringResource(R.string.duration_hours, minutes / 60)
                else -> stringResource(R.string.duration_minutes, minutes)
            }
            stringResource(if (t.seconds < 0) R.string.reminder_before else R.string.reminder_after, text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanwerkFields(meta: PlanwerkMeta, link: PlanwerkLink?, editable: Boolean, onChange: (PlanwerkMeta) -> Unit) {
    val kinds = listOf(PlanwerkKind.TASK to R.string.kind_task, PlanwerkKind.MILESTONE to R.string.kind_milestone, PlanwerkKind.EXPERIMENT to R.string.kind_experiment)
    SectionTitle(stringResource(R.string.planwerk))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { i, (k, label) ->
            SegmentedButton(selected = meta.kind == k, enabled = editable, onClick = { onChange(meta.copy(kind = k)) }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
    ChoiceField(stringResource(R.string.group), meta.group, link?.groups.orEmpty(), stringResource(R.string.none), editable) { onChange(meta.copy(group = it)) }
    ChoiceField(stringResource(R.string.assignee), meta.assignee, link?.people.orEmpty(), stringResource(R.string.nobody), editable) { onChange(meta.copy(assignee = it)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String?, options: List<String>, noneLabel: String, enabled: Boolean, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Ein vom Server gesetzter Wert, der nicht (mehr) in der Auswahl steht, bleibt sichtbar.
    val all = if (value != null && value !in options) listOf(value) + options else options
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = value ?: noneLabel, onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(noneLabel) }, onClick = { open = false; onChange(null) })
            for (o in all) DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onChange(o) })
        }
    }
}
