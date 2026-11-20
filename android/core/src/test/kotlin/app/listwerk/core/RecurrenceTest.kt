package app.listwerk.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {
    private val fri = LocalDate.of(2026, 11, 20)

    @Test
    fun simpleRules() {
        assertEquals(fri.plusDays(1), Recurrence.next(Recurrence.DAILY, fri))
        assertEquals(fri.plusDays(3), Recurrence.next(Recurrence.WEEKDAYS, fri), "Freitag → Montag")
        assertEquals(fri, Recurrence.next(Recurrence.WEEKDAYS, fri.minusDays(1)), "Donnerstag → Freitag")
        assertEquals(fri.plusWeeks(2), Recurrence.next("FREQ=WEEKLY;INTERVAL=2", fri))
        assertEquals(LocalDate.of(2027, 2, 28), Recurrence.next(Recurrence.MONTHLY, LocalDate.of(2027, 1, 31)))
        assertEquals(fri.plusYears(1), Recurrence.next(Recurrence.YEARLY, fri))
    }

    @Test
    fun unsupportedOrExpired() {
        assertNull(Recurrence.next("FREQ=MONTHLY;BYMONTHDAY=-1", fri))
        assertNull(Recurrence.next("FREQ=DAILY;UNTIL=20261120T000000Z", fri))
        assertNull(Recurrence.next("FREQ=HOURLY", fri))
    }
}
