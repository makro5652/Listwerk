package app.listwerk.android.widget

import android.app.Application
import androidx.glance.appwidget.updateAll
import app.listwerk.android.container
import kotlinx.coroutines.launch

/** Stößt die Aktualisierung aller Widgets an (nach lokalen Änderungen und nach dem Abgleich). */
class WidgetUpdater(private val app: Application) {
    fun update() {
        app.container.scope.launch {
            runCatching { ListwerkWidget().updateAll(app) }
        }
    }
}
