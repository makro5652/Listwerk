package app.listwerk.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Eine anstehende lokale Benachrichtigung. */
data class PlannedNotification(
    /** Stabil: `<liste>|<uid>|<index>` */
    val id: String,
    val fireDate: Instant,
    val title: String,
    val body: String,
    val listHref: String,
    val uid: String,
)

/**
 * Berechnet aus den Einträgen, was als Nächstes ausgelöst werden soll. Rein rechnerisch, ohne Systemzugriff;
 * die Android-Schicht übergibt das Ergebnis an den AlarmManager.
 */
object ReminderPlanner {
    /** Es werden nur die zeitlich nächsten Erinnerungen geplant; nach jeder Änderung und jedem Auslösen wird neu geplant. */
    const val SYSTEM_LIMIT = 64

    fun plan(records: List<TaskRecord>, now: Instant, limit: Int = SYSTEM_LIMIT, zone: ZoneId = ZoneId.systemDefault()): List<PlannedNotification> {
        val out = ArrayList<PlannedNotification>()
        for (rec in records) {
            if (rec.isDeleted || rec.item.isCompleted) continue
            for ((i, r) in rec.item.reminders.withIndex()) {
                if (!r.firesLocally) continue
                val fire = fireDate(r, rec.item, zone) ?: continue
                if (fire <= now) continue
                out.add(
                    PlannedNotification(
                        id = "${rec.listHref}|${rec.item.uid}|$i",
                        fireDate = fire,
                        title = rec.item.title.ifEmpty { CoreText.t("Erinnerung", "Reminder") },
                        body = rec.item.notes.split('\n').firstOrNull { it.isNotEmpty() } ?: "",
                        listHref = rec.listHref,
                        uid = rec.item.uid,
                    ),
                )
            }
        }
        return out.sortedBy { it.fireDate }.take(limit)
    }

    fun fireDate(r: Reminder, item: TodoItem, zone: ZoneId = ZoneId.systemDefault()): Instant? = when (val t = r.trigger) {
        is ReminderTrigger.Absolute -> t.at
        is ReminderTrigger.Relative -> {
            // Ganztägige Fälligkeit ohne Uhrzeit: Bezugspunkt ist 09:00 Ortszeit des Tages.
            val anchor = (if (t.toEnd) item.due else item.start) ?: (if (t.toEnd) item.start else item.due)
            anchor?.let { base(it, zone).plusMillis((t.seconds * 1000).toLong()) }
        }
    }

    private fun base(d: ICalDate, zone: ZoneId): Instant =
        if (!d.isAllDay) d.instant(zone)
        else try {
            LocalDate.of(d.year, d.month, d.day).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
        } catch (_: java.time.DateTimeException) {
            d.instant(zone)
        }
}
