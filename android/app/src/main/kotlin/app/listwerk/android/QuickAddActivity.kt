package app.listwerk.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.listwerk.android.ui.ListwerkTheme
import kotlinx.coroutines.launch

/**
 * Schlanker Schnelleintrag: ein Feld, speichert lokal und schließt sich. Erreichbar über Widget-„+“, statisches Shortcut,
 * Schnelleinstellungs-Kachel und das Teilen von Text aus anderen Apps. Kein Netzwerkzugriff; der Abgleich läuft danach im Hintergrund.
 */
class QuickAddActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.listwerk.android.AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        val shared = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
        } else ""
        val preferred = intent?.getStringExtra(EXTRA_LIST)

        fun save(text: String) {
            if (text.isBlank()) { finish(); return }
            lifecycleScope.launch {
                val added = c.repository.quickAdd(text, preferred)
                if (added == null) android.widget.Toast.makeText(this@QuickAddActivity, R.string.quick_add_failed, android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }

        setContent {
            ListwerkTheme(c.themes, c.prefs.themeId) {
                var text by remember { mutableStateOf(shared) }
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.quick_add_title), style = MaterialTheme.typography.titleMedium)
                    if (!c.prefs.signedIn) {
                        Text(stringResource(R.string.error_not_connected), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.new_entry_hint)) }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save(text) }),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { save(text) }, enabled = text.isNotBlank() && c.prefs.signedIn) { Text(stringResource(R.string.action_add)) }
                    }
                }
                }
            }
        }
    }

    companion object {
        const val EXTRA_LIST = "list"
    }
}
