package app.listwerk.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.listwerk.android.data.AppPrefs
import app.listwerk.android.ui.ListwerkTheme
import app.listwerk.android.ui.isAqua
import app.listwerk.android.ui.listwerk
import app.listwerk.android.ui.pinstripes
import app.listwerk.android.ui.TodoScreen
import app.listwerk.android.ui.LoginScreen
import app.listwerk.android.ui.MainScreen
import app.listwerk.android.ui.MainViewModel
import app.listwerk.android.ui.Screen
import app.listwerk.android.ui.SettingsScreen
import app.listwerk.android.ui.UiEvent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.listwerk.core.CoreText.german = AppLocale.current(this) == AppLocale.DE
        enableEdgeToEdge()
        handleIntent(intent)

        // Anmelde-Seite im Browser öffnen (Login Flow v2).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiEvents.collect { e ->
                    if (e is UiEvent.OpenUrl) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
                }
            }
        }

        setContent {
            val themeId by vm.themeId.collectAsState()
            val onboarded by vm.onboarded.collectAsState()
            val screen by vm.screen.collectAsState()
            val layout by vm.layout.collectAsState()
            ListwerkTheme(container.themes, themeId) {
                val aqua = MaterialTheme.listwerk.isAqua
                Surface(
                    Modifier.fillMaxSize().pinstripes(aqua, MaterialTheme.colorScheme.background),
                    color = if (aqua) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
                ) {
                    when {
                        !onboarded -> LoginScreen(vm, onBack = null)
                        screen == Screen.LOGIN -> {
                            BackHandler { vm.cancelLogin(); vm.screen.value = Screen.SETTINGS }
                            LoginScreen(vm, onBack = { vm.cancelLogin(); vm.screen.value = Screen.SETTINGS })
                        }
                        screen == Screen.SETTINGS -> {
                            BackHandler { vm.screen.value = Screen.MAIN }
                            SettingsScreen(vm, onBack = { vm.screen.value = Screen.MAIN })
                        }
                        layout == AppPrefs.LAYOUT_TODO -> TodoScreen(vm, onRelogin = { vm.logout() })
                        else -> MainScreen(vm, onRelogin = { vm.logout() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Beim Öffnen im Hintergrund abgleichen; die Oberfläche liest ausschließlich lokal.
        if (container.prefs.signedIn) container.sync.schedule(0)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_LIST) {
            intent.getStringExtra(EXTRA_LIST)?.let { vm.select(it) }
            intent.getStringExtra(EXTRA_TASK)?.let { uid -> intent.getStringExtra(EXTRA_LIST)?.let { vm.openDetail(it, uid) } }
        }
    }

    companion object {
        const val ACTION_OPEN_LIST = "app.listwerk.android.action.OPEN_LIST"
        const val EXTRA_LIST = "list"
        const val EXTRA_TASK = "task"
    }
}
