package app.listwerk.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ListOrderTest {
    @Test
    fun reorderUploadsCalendarOrderAndSurvivesSync() = runBlocking {
        val env = Env.make()
        val privat = env.server.addCollection("Privat", null)
        env.sync()
        assertEquals(listOf("Arbeit", "Privat"), env.store.lists().map { it.name })

        env.store.reorderLists(listOf(privat, env.list))
        assertEquals(listOf("Privat", "Arbeit"), env.store.lists().map { it.name })
        env.sync()
        assertEquals(2.0, env.server.collections[env.list]!!.order, "nur geänderte Positionen werden hochgeladen")
        env.sync()
        assertEquals(listOf("Privat", "Arbeit"), env.store.lists().map { it.name })
    }
}
