package app.listwerk.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.listwerk.android.MainActivity
import app.listwerk.android.QuickAddActivity
import app.listwerk.android.R
import app.listwerk.android.container
import app.listwerk.android.data.AppPrefs
import app.listwerk.android.ui.Format
import app.listwerk.android.ui.parseHex
import app.listwerk.core.TaskRecord

private data class WidgetRow(val rec: TaskRecord, val color: Color?, val due: String?, val overdue: Boolean)
private data class WidgetData(val title: String, val rows: List<WidgetRow>, val listHref: String, val text: Color, val sub: Color, val bg: Color, val accent: Color, val danger: Color)

class ListwerkWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = load(context, appWidgetId)
        provideContent { Content(context, data) }
    }

    private suspend fun load(context: Context, appWidgetId: Int): WidgetData {
        val c = context.container
        val selection = c.prefs.widgetList(appWidgetId)
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val theme = c.themes.resolve(c.prefs.themeId, dark)
        val tc = theme.colors
        val title = if (selection == AppPrefs.ALL) context.getString(R.string.all_open)
        else c.repository.lists().first().firstOrNull { it.href == selection }?.name
            ?: context.getString(R.string.all_open)
        val rows = if (!c.prefs.signedIn) emptyList() else c.repository.openTopLevel(selection).take(40).map { r ->
            WidgetRow(
                r, r.item.color?.let { parseHex(c.themes.displayHex(it, theme)) },
                r.item.due?.let { Format.due(context, it) }, r.item.due?.let { Format.isOverdue(it) } == true,
            )
        }
        return WidgetData(
            title, rows, selection,
            parseHex(tc.textPrimary), parseHex(tc.textSecondary), parseHex(tc.background), parseHex(tc.accent), parseHex(tc.danger),
        )
    }

    @Composable
    private fun Content(context: Context, d: WidgetData) {
        GlanceTheme {
            Column(GlanceModifier.fillMaxSize().background(ColorProvider(d.bg)).cornerRadius(20.dp).padding(8.dp)) {
                Row(GlanceModifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.title,
                        modifier = GlanceModifier.defaultWeight().clickable(
                            actionStartActivity(
                                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_LIST)
                                    .putExtra(MainActivity.EXTRA_LIST, d.listHref),
                            ),
                        ),
                        style = TextStyle(color = ColorProvider(d.text), fontWeight = FontWeight.Bold, fontSize = 16.sp),
                        maxLines = 1,
                    )
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = context.getString(R.string.widget_add),
                        modifier = GlanceModifier.size(32.dp).clickable(
                            actionStartActivity(Intent(context, QuickAddActivity::class.java).putExtra(QuickAddActivity.EXTRA_LIST, d.listHref)),
                        ),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                if (d.rows.isEmpty()) {
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(context.getString(R.string.widget_empty), style = TextStyle(color = ColorProvider(d.sub), fontSize = 13.sp))
                    }
                } else {
                    LazyColumn(GlanceModifier.fillMaxSize()) {
                        items(d.rows, itemId = { it.rec.id.hashCode().toLong() }) { row ->
                            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_widget_circle),
                                    contentDescription = context.getString(R.string.action_complete),
                                    modifier = GlanceModifier.size(28.dp).padding(4.dp).clickable(
                                        actionRunCallback<ToggleDoneAction>(
                                            actionParametersOf(ToggleDoneAction.LIST to row.rec.listHref, ToggleDoneAction.UID to row.rec.item.uid),
                                        ),
                                    ),
                                )
                                if (row.color != null) {
                                    Box(GlanceModifier.width(4.dp).height(26.dp).background(ColorProvider(row.color)).cornerRadius(2.dp)) {}
                                    Spacer(GlanceModifier.width(6.dp))
                                }
                                Column(GlanceModifier.defaultWeight()) {
                                    Text(
                                        row.rec.item.title.ifBlank { context.getString(R.string.untitled) },
                                        style = TextStyle(color = ColorProvider(d.text), fontSize = 14.sp), maxLines = 2,
                                    )
                                    if (row.due != null) {
                                        Text(
                                            row.due,
                                            style = TextStyle(color = ColorProvider(if (row.overdue) d.danger else d.sub), fontSize = 11.sp), maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val list = parameters[LIST] ?: return
        val uid = parameters[UID] ?: return
        context.container.repository.setDone(list, uid, true)
        ListwerkWidget().update(context, glanceId)
    }

    companion object {
        val LIST = ActionParameters.Key<String>("list")
        val UID = ActionParameters.Key<String>("uid")
    }
}

class ListwerkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListwerkWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { context.container.prefs.removeWidget(it) }
    }
}
