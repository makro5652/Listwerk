package app.listwerk.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ICalDateRobustTest {
    @Test
    fun invalidDayDoesNotThrow() {
        assertEquals(LocalDate.EPOCH, ICalDate(2026, 2, 30, kind = ICalDate.Kind.Day).localDay())
    }
}
