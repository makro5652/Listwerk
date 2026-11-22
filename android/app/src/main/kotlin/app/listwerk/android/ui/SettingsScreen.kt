package app.listwerk.android.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.listwerk.android.BuildConfig
import app.listwerk.android.AppLocale
import app.listwerk.android.R
import app.listwerk.android.container
import app.listwerk.android.data.AppPrefs
import app.listwerk.core.Theme
import app.listwerk.core.ThemeCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { context.container.themes }
    val themeId by vm.themeId.collectAsState()
    val focus by vm.focusInput.collectAsState()
    val startList by vm.startList.collectAsState()
    val allLists by vm.lists.collectAsState()
    val planwerkUrl by vm.planwerkUrl.collectAsState()
    val tokenSaved by vm.planwerkTokenSaved.collectAsState()
    val account by remember { vm.account() }.collectAsState(initial = null)
    val sync by vm.syncStatus.collectAsState()
    var url by remember(planwerkUrl) { mutableStateOf(planwerkUrl) }
    var token by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = screenColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(stringResource(R.string.settings_design))
            Text(stringResource(R.string.settings_language))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val current = remember { AppLocale.current(context) }
                val langs = listOf(AppLocale.EN to "English", AppLocale.DE to "Deutsch")
                langs.forEachIndexed { i, (code, label) ->
                    SegmentedButton(
                        selected = current == code,
                        onClick = { if (current != code) (context as? android.app.Activity)?.let { AppLocale.set(it, code) } },
                        shape = SegmentedButtonDefaults.itemShape(i, langs.size),
                    ) { Text(label) }
                }
            }
            val layout by vm.layout.collectAsState()
            Text(stringResource(R.string.settings_layout))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(AppPrefs.LAYOUT_LISTWERK to R.string.layout_listwerk, AppPrefs.LAYOUT_TODO to R.string.layout_todo)
                options.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = layout == id, onClick = { vm.setLayout(id) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                        Text(stringResource(label))
                    }
                }
            }
            ThemePicker(catalog, themeId, vm::setTheme)

            Header(stringResource(R.string.settings_behavior))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_focus_input), Modifier.weight(1f))
                Switch(checked = focus, onCheckedChange = vm::setFocusInput)
            }
            StartListPicker(startList, allLists.orEmpty().map { it.href to it.name }, vm::setStartList)

            Header(stringResource(R.string.settings_notifications))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.settings_notification_settings)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text(stringResource(R.string.settings_exact_alarms)) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exact = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                Text(
                    stringResource(if (exact) R.string.settings_exact_on else R.string.settings_exact_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Header(stringResource(R.string.settings_nextcloud))
            if (account == null) {
                Text(stringResource(R.string.settings_local_mode), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.screen.value = Screen.LOGIN }) { Text(stringResource(R.string.settings_login)) }
            }
            account?.let { a ->
                Text("${a.loginName}\n${a.serverUrl}", style = MaterialTheme.typography.bodyMedium)
                sync.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                sync.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::syncNow, enabled = !sync.isSyncing) { Text(stringResource(R.string.action_sync)) }
                    OutlinedButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.logout)) }
                }
            }

            Header(stringResource(R.string.planwerk))
            Text(stringResource(R.string.settings_planwerk_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.planwerk_url)) }, placeholder = { Text("https://planwerk.example.org") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.planwerk_token)) }, placeholder = { Text("pwk_…") },
                supportingText = { if (tokenSaved) Text(stringResource(R.string.planwerk_token_saved)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedButton(onClick = { vm.savePlanwerk(url, token); token = "" }) { Text(stringResource(R.string.planwerk_save_test)) }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_message)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text(stringResource(R.string.logout)) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StartListPicker(selected: String, lists: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (selected) {
        AppPrefs.START_LAST -> stringResource(R.string.start_list_last)
        AppPrefs.ALL -> stringResource(R.string.all_open)
        else -> lists.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.start_list_last)
    }
    Column {
        Text(stringResource(R.string.settings_start_list))
        Box {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.start_list_last)) }, onClick = { open = false; onSelect(AppPrefs.START_LAST) })
                DropdownMenuItem(text = { Text(stringResource(R.string.all_open)) }, onClick = { open = false; onSelect(AppPrefs.ALL) })
                for ((href, name) in lists) DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(href) })
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ThemePicker(catalog: ThemeCatalog, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeRow(stringResource(R.string.theme_system), null, selected == ThemeCatalog.SYSTEM_ID) { onSelect(ThemeCatalog.SYSTEM_ID) }
        for (t in catalog.all) ThemeRow(t.name, t, selected == t.id) { onSelect(t.id) }
    }
}

@Composable
private fun ThemeRow(name: String, theme: Theme?, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(border, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f))
        if (theme != null) {
            val c = theme.colors
            for (hex in listOf(c.background, c.surface, c.accent, c.textPrimary)) {
                Box(Modifier.padding(start = 4.dp).size(18.dp).clip(CircleShape).background(parseHex(hex)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
    }
}
