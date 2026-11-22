package app.listwerk.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickEntryTest {
    // Freitag, 20.11.2026, 10:00 in Berlin (wie in den Swift-Tests).
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, zone)
    private fun p(s: String) = QuickEntry.parse(s, now)
    private fun at(q: QuickEntry): List<Int> {
        val t = q.due!!.instant(zone).atZone(zone)
        return listOf(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    @Test fun germanExample() {
        val q = p("Elternbrief morgen 8 Uhr !")
        assertEquals("Elternbrief", q.title)
        assertTrue(q.important)
        assertEquals(listOf(2026, 11, 21, 8, 0), at(q))
        assertFalse(q.due!!.isAllDay)
    }

    @Test fun germanDatesAndRules() {
        assertEquals(ICalDate.day(2026, 11, 23), p("Konferenz am Montag").due)
        assertEquals("Konferenz", p("Konferenz am Montag").title)
        assertEquals(ICalDate.day(2026, 11, 23), p("Rückmeldung in 3 Tagen").due)
        assertEquals(ICalDate.day(2026, 11, 24), p("Abgabe 24.11.").due)
        assertEquals(listOf(2026, 11, 23, 8, 30), at(p("Anruf Montag 8.30 Uhr")))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", p("Gießen alle 2 Wochen").recurrence)
        assertEquals("FREQ=WEEKLY;BYDAY=TU", p("Sitzung jeden Dienstag").recurrence)
        assertEquals(Recurrence.WEEKDAYS, p("Bericht werktags").recurrence)
    }

    @Test fun plainTextStays() {
        val q = p("Kapitel 3 lesen, am besten bald")
        assertEquals("Kapitel 3 lesen, am besten bald", q.title)
        assertNull(q.due); assertFalse(q.important); assertNull(q.recurrence)
        assertEquals("heute", p("heute").title)
        assertEquals("Teamsitzung program", p("Teamsitzung program").title)
    }

    @Test fun english() {
        val q = p("Call parents tomorrow 8am !")
        assertEquals("Call parents", q.title)
        assertTrue(q.important)
        assertEquals(listOf(2026, 11, 21, 8, 0), at(q))
        assertEquals(listOf(2026, 11, 20, 15, 30), at(p("Meeting today at 3:30pm")))
        assertEquals(listOf(2026, 11, 21, 20, 0), at(p("Meeting tomorrow 8 pm")))
        assertEquals("Meeting", p("Meeting tomorrow 8 pm").title)
        assertEquals(listOf(2026, 11, 21, 0, 0), at(p("Night 12am tomorrow")))
        assertEquals(ICalDate.day(2026, 11, 23), p("Team call next Monday").due)
        assertEquals("Team call", p("Team call next Monday").title)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", p("Water plants every 2 weeks").recurrence)
        assertEquals("FREQ=WEEKLY;BYDAY=TU", p("Standup every Tuesday").recurrence)
        assertEquals(ICalDate.day(2026, 11, 23), p("Dentist in 3 days").due)
        assertEquals(ICalDate.day(2026, 11, 23), p("Treffen 8 am Montag").due)
    }

    @Test fun timeOnlyInThePastMeansTomorrow() {
        assertEquals(listOf(2026, 11, 21, 9, 0), at(p("Kaffee 9 Uhr")))
        assertEquals(listOf(2026, 11, 20, 14, 0), at(p("Kaffee um 14")))
    }
}
