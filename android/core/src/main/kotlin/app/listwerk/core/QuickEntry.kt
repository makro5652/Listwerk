package app.listwerk.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Schnelleingabe wie in der Mac-App: „Elternbrief morgen 8 Uhr !“ → Titel, Fälligkeit, Wichtigkeit, Wiederholung.
 * Erkannte Wörter werden aus dem Titel entfernt. Deutsch und Englisch gelten immer:
 * heute/today, morgen/tomorrow, übermorgen, (am/nächsten/on/next) Montag … Sonntag bzw. Monday … Sunday,
 * 24.11., 24.11.2026, in 3 Tagen/days, 8 Uhr, 8:30, 8.30 Uhr, um/at 14, 8am, 3:30pm, 8 pm, „!“ (wichtig),
 * täglich/daily, werktags/weekdays, wöchentlich/weekly, monatlich/monthly, jährlich/yearly,
 * jeden/every Tag/day/Montag/Monday, alle/every 2 Tage/Wochen/days/weeks.
 */
data class QuickEntry(
    val title: String,
    val due: ICalDate? = null,
    val important: Boolean = false,
    val recurrence: String? = null,
) {
    private enum class Unit(val rrule: String) { DAY("DAILY"), WEEK("WEEKLY"), MONTH("MONTHLY"), YEAR("YEARLY") }

    companion object {
        private val weekdays = mapOf(
            "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
            "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
            "sonntag" to DayOfWeek.SUNDAY,
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
        )
        private val rruleDays = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private val rules = mapOf(
            "täglich" to Recurrence.DAILY, "werktags" to Recurrence.WEEKDAYS, "wöchentlich" to Recurrence.WEEKLY,
            "monatlich" to Recurrence.MONTHLY, "jährlich" to Recurrence.YEARLY,
            "daily" to Recurrence.DAILY, "weekdays" to Recurrence.WEEKDAYS, "weekly" to Recurrence.WEEKLY,
            "monthly" to Recurrence.MONTHLY, "yearly" to Recurrence.YEARLY,
        )

        private fun unit(w: String): Unit? = when {
            w.startsWith("tag") || w == "day" || w == "days" -> Unit.DAY
            w.startsWith("woche") || w == "week" || w == "weeks" -> Unit.WEEK
            w.startsWith("monat") || w == "month" || w == "months" -> Unit.MONTH
            w.startsWith("jahr") || w == "year" || w == "years" -> Unit.YEAR
            else -> null
        }

        /** „8“, „8:30“, „8.30“, „14h“, „8uhr“, „8am“, „8:30pm“ → Uhrzeit, sonst null. */
        private fun clock(word: String): LocalTime? {
            var s = word
            var meridiem: String? = null
            if (s.endsWith("am") || s.endsWith("pm")) { meridiem = s.takeLast(2); s = s.dropLast(2) }
            if (meridiem == null) { if (s.endsWith("uhr")) s = s.dropLast(3) else if (s.endsWith("h")) s = s.dropLast(1) }
            val parts = s.split(if (':' in s) ':' else '.')
            if (parts.size !in 1..2 || parts[0].length !in 1..2) return null
            val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            if (parts.size == 2 && parts[1].length != 2) return null
            val m = (if (parts.size == 2) parts[1].toIntOrNull() else 0)?.takeIf { it in 0..59 } ?: return null
            if (meridiem != null) {
                if (h !in 1..12) return null
                return LocalTime.of(h % 12 + if (meridiem == "pm") 12 else 0, m)
            }
            return LocalTime.of(h, m)
        }

        /** „24.11.“, „24.11.26“, „24.11.2026“. Ohne Jahr: das nächste solche Datum ab heute. */
        private fun date(w: String, today: LocalDate): LocalDate? {
            val p = w.split('.')
            if (p.size != 3) return null   // „24.11“ ohne Schlusspunkt ist eher eine Zahl oder Uhrzeit
            val d = p[0].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
            val m = p[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val explicitYear = p[2].isNotEmpty()
            val y = if (explicitYear) (p[2].toIntOrNull() ?: return null).let { if (it < 100) 2000 + it else it } else today.year
            var r = runCatching { LocalDate.of(y, m, d) }.getOrNull() ?: return null
            if (!explicitYear && r.isBefore(today)) r = r.plusYears(1)
            return r
        }

        fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): QuickEntry {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val keep = BooleanArray(words.size) { true }
            val today = now.toLocalDate()
            var day: LocalDate? = null
            var time: LocalTime? = null
            var important = false
            var rule: String? = null

            fun low(i: Int) = if (i < words.size) words[i].lowercase().trim(',') else ""
            fun drop(from: Int, to: Int = from) { for (k in from..to) if (k < keep.size) keep[k] = false }
            fun upcoming(d: DayOfWeek): LocalDate = today.plusDays(((d.value - today.dayOfWeek.value + 7) % 7).toLong())
            fun nextIsDate(k: Int) = low(k).let { weekdays[it] != null || date(it, today) != null }

            var i = 0
            while (i < words.size) {
                val w = low(i)
                val n1 = low(i + 1)
                when {
                    w.isNotEmpty() && w.all { it == '!' } -> { important = true; drop(i) }
                    w == "heute" || w == "today" -> { day = today; drop(i) }
                    w == "morgen" || w == "tomorrow" -> { day = today.plusDays(1); drop(i) }
                    w == "übermorgen" -> { day = today.plusDays(2); drop(i) }
                    weekdays[w] != null -> { day = upcoming(weekdays.getValue(w)); drop(i) }
                    rules[w] != null -> { rule = rules[w]; drop(i) }
                    (w == "alle" || w == "every") && (n1.toIntOrNull() ?: 0) > 0 && unit(low(i + 2)) != null -> {
                        val n = n1.toInt(); val f = unit(low(i + 2))!!.rrule
                        rule = if (n == 1) "FREQ=$f" else "FREQ=$f;INTERVAL=$n"; drop(i, i + 2)
                    }
                    w == "jeden" || w == "jede" || w == "jedes" || w == "every" -> {
                        val wd = weekdays[n1]
                        val u = unit(n1)
                        if (wd != null) { rule = "FREQ=WEEKLY;BYDAY=${rruleDays.getValue(wd)}"; day = day ?: upcoming(wd); drop(i, i + 1) }
                        else if (u != null) { rule = "FREQ=${u.rrule}"; drop(i, i + 1) }
                    }
                    w == "in" && (n1.toIntOrNull() ?: -1) >= 0 && unit(low(i + 2)) != null -> {
                        val n = n1.toLong()
                        day = when (unit(low(i + 2))!!) {
                            Unit.DAY -> today.plusDays(n); Unit.WEEK -> today.plusWeeks(n)
                            Unit.MONTH -> today.plusMonths(n); Unit.YEAR -> today.plusYears(n)
                        }
                        drop(i, i + 2)
                    }
                    date(w, today) != null -> { day = date(w, today); drop(i) }
                    (w == "um" || w == "at") && clock(n1) != null -> {
                        time = clock(n1); drop(i, i + 1)
                        if (low(i + 2) == "uhr") drop(i + 2)
                    }
                    n1 == "uhr" && clock(w) != null -> { time = clock(w); drop(i, i + 1) }
                    (n1 == "am" || n1 == "pm") && w.toIntOrNull() != null && !nextIsDate(i + 2) && clock(w + n1) != null -> {
                        time = clock(w + n1); drop(i, i + 1)   // „8 pm“; „8 am Montag“ bleibt deutsch
                    }
                    (':' in w || w.endsWith("uhr") || w.endsWith("am") || w.endsWith("pm")) && clock(w) != null -> { time = clock(w); drop(i) }
                }
                // „am“, „nächsten“, „on“, „next“ nur vor einem erkannten Datum entfernen.
                if ((w == "am" || w.startsWith("nächste") || w == "on" || w == "next") && i + 1 < words.size && nextIsDate(i + 1)) keep[i] = false
                i++
            }

            if (rule != null && day == null) day = today
            var due: ICalDate? = null
            val d = day ?: if (time != null) today else null
            if (d != null) {
                val t = time
                due = if (t != null) {
                    var at = d.atTime(t).atZone(now.zone)
                    // Nur eine Uhrzeit, die heute schon vorbei ist: morgen.
                    if (day == null && !at.isAfter(now)) at = at.plusDays(1)
                    ICalDate.of(at.toInstant(), allDay = false, zone = now.zone)
                } else {
                    ICalDate.day(d.year, d.monthValue, d.dayOfMonth)
                }
            }
            val title = words.filterIndexed { k, _ -> keep[k] }.joinToString(" ")
            return QuickEntry(title.ifEmpty { text.trim() }, due, important, rule)
        }
    }
}
