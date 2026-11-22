package app.listwerk.android.ui

import android.content.Context
import app.listwerk.android.R
import app.listwerk.core.ICalDate
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private fun dayLabel(context: Context, day: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, day)
        return when {
            diff == 0L -> context.getString(R.string.today)
            diff == 1L -> context.getString(R.string.tomorrow)
            diff == -1L -> context.getString(R.string.yesterday)
            diff in 2..6 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
            day.year == today.year -> DateTimeFormatter.ofPattern(if (Locale.getDefault().language == "de") "d. MMM" else "MMM d", Locale.getDefault()).format(day)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(day)
        }
    }

    fun due(context: Context, d: ICalDate, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val day = d.localDay(zone)
        val label = dayLabel(context, day, today)
        if (d.isAllDay) return label
        return label + ", " + time.withLocale(Locale.getDefault()).format(d.instant(zone).atZone(zone).toLocalTime())
    }

    fun isOverdue(d: ICalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        if (d.isAllDay) d.localDay(zone).isBefore(LocalDate.now(zone)) else d.instant(zone).isBefore(java.time.Instant.now())

    fun time(t: LocalTime): String = time.withLocale(Locale.getDefault()).format(t)

    fun date(d: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(d)
}
