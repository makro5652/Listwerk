package app.listwerk.android.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.listwerk.android.AppContainer
import app.listwerk.android.MainActivity
import app.listwerk.android.R
import app.listwerk.android.container
import app.listwerk.core.PlannedNotification
import app.listwerk.core.ReminderPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Erinnerungen über den AlarmManager. Es wird immer nur der nächste Zeitpunkt vorgemerkt; beim Auslösen werden alle
 * fälligen Mitteilungen gezeigt und der darauffolgende Zeitpunkt geplant. Nach Änderungen, Abgleich und Neustart wird neu geplant.
 */
class ReminderScheduler(private val app: android.app.Application, private val c: AppContainer) {
    private val alarmManager get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 1, Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Blockierend; auf einem Hintergrund-Thread aufrufen. */
    fun reschedule(now: Instant = Instant.now()) {
        if (!c.prefs.signedIn) {
            alarmManager.cancel(pendingIntent())
            return
        }
        if (c.prefs.remindersFiredUntil == 0L) c.prefs.remindersFiredUntil = now.toEpochMilli()
        val next = ReminderPlanner.plan(c.store.openTasks(), now, limit = 1).firstOrNull()
        if (next == null) {
            alarmManager.cancel(pendingIntent())
            return
        }
        val at = next.fireDate.toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
    }

    /** Zeigt alle seit der letzten Auslösung fälligen Erinnerungen (höchstens 24 Stunden zurück) und plant neu. */
    fun fireDue(now: Instant = Instant.now()) {
        val from = maxOf(c.prefs.remindersFiredUntil, now.toEpochMilli() - 24 * 3_600_000L)
        val due = ReminderPlanner.plan(c.store.openTasks(), Instant.ofEpochMilli(from), limit = 200).filter { it.fireDate <= now }
        c.prefs.remindersFiredUntil = now.toEpochMilli()
        for (n in due) show(n)
        reschedule(now)
    }

    private fun ensureChannel() {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = app.getString(R.string.channel_reminders_desc)
                },
            )
        }
    }

    private fun show(n: PlannedNotification) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            app, n.id.hashCode(),
            Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_LIST)
                .putExtra(MainActivity.EXTRA_LIST, n.listHref).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val done = PendingIntent.getBroadcast(
            app, n.id.hashCode(),
            Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DONE)
                .putExtra(MainActivity.EXTRA_LIST, n.listHref).putExtra(ReminderReceiver.EXTRA_UID, n.uid)
                .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, n.id.hashCode()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.body.ifEmpty { null })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, app.getString(R.string.action_mark_done), done)
            .build()
        try {
            if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                NotificationManagerCompat.from(app).notify(n.id.hashCode(), notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS nicht erteilt
        }
    }

    companion object {
        const val CHANNEL = "reminders"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.scope.launch(Dispatchers.IO) {
            try {
                when (intent.action) {
                    ACTION_FIRE -> c.reminders.fireDue()
                    ACTION_DONE -> {
                        val list = intent.getStringExtra(MainActivity.EXTRA_LIST)
                        val uid = intent.getStringExtra(EXTRA_UID)
                        if (list != null && uid != null) c.repository.setDone(list, uid, true)
                        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                    }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED -> if (c.prefs.signedIn) {
                        c.reminders.fireDue()
                        c.sync.ensurePeriodic()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "app.listwerk.android.action.FIRE_REMINDER"
        const val ACTION_DONE = "app.listwerk.android.action.MARK_DONE"
        const val EXTRA_UID = "uid"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
