package app.listwerk.android.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import app.listwerk.android.R
import app.listwerk.android.container
import app.listwerk.android.data.AppPrefs
import app.listwerk.android.ui.ListwerkTheme
import app.listwerk.core.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Auswahl der Liste beim Platzieren eines Widgets: „Alle offenen“ oder eine bestimmte Liste. */
class WidgetConfigActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(app.listwerk.android.AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val c = container
        setContent {
            var lists by remember { mutableStateOf<List<TaskList>>(emptyList()) }
            LaunchedEffect(Unit) { lists = withContext(Dispatchers.IO) { if (c.prefs.signedIn) c.store.lists() else emptyList() } }
            ListwerkTheme(c.themes, c.prefs.themeId) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(stringResource(R.string.widget_choose_list), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                        Option(stringResource(R.string.all_open)) { choose(id, AppPrefs.ALL) }
                        for (l in lists) Option(l.name) { choose(id, l.href) }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Option(text: String, onClick: () -> Unit) {
        Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
    }

    private fun choose(id: Int, href: String) {
        container.prefs.setWidgetList(id, href)
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(id)
            ListwerkWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
    }
}
