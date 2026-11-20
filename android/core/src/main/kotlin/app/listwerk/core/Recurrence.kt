package app.listwerk.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Die einfachen Wiederholungen, die die Oberfläche anbietet (wie Microsoft To Do: täglich, werktags, wöchentlich,
 * monatlich, jährlich, je mit Intervall). Andere RRULEs werden nur durchgereicht.
 */
object Recurrence {
    const val DAILY = "FREQ=DAILY"
    const val WEEKDAYS = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    const val WEEKLY = "FREQ=WEEKLY"
    const val MONTHLY = "FREQ=MONTHLY"
    const val YEARLY = "FREQ=YEARLY"

    private val days = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    private fun parts(rrule: String): Map<String, String> =
        rrule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()

    /** Nächstes Datum nach [from]; null, wenn die Regel nicht unterstützt wird oder abgelaufen ist (COUNT wird nicht mitgezählt). */
    fun next(rrule: String, from: LocalDate): LocalDate? {
        val p = parts(rrule)
        if (p.keys.any { it !in setOf("FREQ", "INTERVAL", "BYDAY", "UNTIL", "WKST") }) return null
        val n = p["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val byDay = p["BYDAY"]?.split(',')?.map { days[it.trim().uppercase()] ?: return null }?.toSet()
        val next = when (p["FREQ"]?.uppercase()) {
            "DAILY" -> from.plusDays(n.toLong())
            "WEEKLY" -> if (byDay.isNullOrEmpty()) from.plusWeeks(n.toLong()) else {
                var d = from.plusDays(1)
                // Innerhalb der Woche den nächsten passenden Tag; nach Wochenende ggf. n-1 Wochen überspringen.
                while (d.dayOfWeek !in byDay) {
                    d = d.plusDays(1)
                    if (d.dayOfWeek == DayOfWeek.MONDAY && n > 1) d = d.plusWeeks((n - 1).toLong())
                }
                d
            }
            "MONTHLY" -> from.plusMonths(n.toLong())
            "YEARLY" -> from.plusYears(n.toLong())
            else -> return null
        }
        val until = p["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        return if (until != null && next.isAfter(until)) null else next
    }

    /** Gleiches Datum/Uhrzeit-Format wie [due], nur der Tag ist verschoben. */
    fun shift(due: ICalDate, to: LocalDate): ICalDate = due.copy(year = to.year, month = to.monthValue, day = to.dayOfMonth)
}
