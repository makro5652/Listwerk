package app.listwerk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verhaltensprüfung des [Store] über einer beliebigen [StoreBackend]-Implementierung (Portierung von StoreTests.swift).
 * Die JVM-Tests von :core laufen gegen [InMemoryBackend], die Room-Implementierung der App gegen dieselben Fälle.
 */
abstract class StoreContractTest {
    abstract fun newBackend(): StoreBackend

    /** Öffnet dieselben Daten erneut (nur für dauerhaft speichernde Implementierungen). Null = nicht unterstützt. */
    open fun reopen(previous: StoreBackend): StoreBackend? = null

    private fun makeStore(): Pair<Store, String> {
        val store = Store(newBackend())
        val list = "/remote.php/dav/calendars/nutzer/arbeit/"
        store.applyRemoteLists(listOf(RemoteList(list, "Arbeit")))
        return store to list
    }

    private fun order(store: Store, list: String) =
        store.tasks(list).filter { it.item.parentUID == null }.map { it.item.title }

    @Test
    fun slug() {
        assertEquals("projekt-grosse", Store.slug("Projekt Größe"))
        assertEquals("uber-apfel-birnen", Store.slug("Über Äpfel & Birnen"))
        assertEquals("liste", Store.slug("  ???  "))
    }

    @Test
    fun saveReadAndUnchangedSaveStaysClean() {
        val (store, list) = makeStore()
        var item = todo("Eins") { it.copy(notes = "n", due = ICalDate.day(2026, 12, 1), color = "#8839ef") }
        val rec = store.save(item, list, testNow)
        assertTrue(rec.isDirty)
        store.markSynced("eins", list, "${list}eins.ics", "\"1\"", rec.ics)
        item = assertNotNull(store.record("eins", list)).item
        val again = store.save(item, list, testNow.plusSeconds(60))
        assertFalse(again.isDirty, "unveränderter Eintrag bleibt sauber")
        assertEquals("n", again.item.notes)
        assertEquals("#8839ef", again.item.color)
    }

    @Test
    fun reorderBetweenNeighboursAndRenumbering() {
        val (store, list) = makeStore()
        for (t in listOf("a", "b", "c")) store.save(todo(t), list, testNow)
        assertEquals(listOf("a", "b", "c"), order(store, list))

        store.reorder("c", list, null, testNow)
        assertEquals(listOf("c", "a", "b"), order(store, list))
        assertEquals(listOf<Int?>(1024, 2048, 3072), store.tasks(list).map { it.item.sortOrder })

        store.reorder("a", list, "b", testNow)
        assertEquals(listOf("c", "b", "a"), order(store, list))

        // Mittlerer Wert, ohne die Nachbarn anzufassen
        for (r in store.tasks(list)) store.markSynced(r.item.uid, list, "$list${r.item.uid}.ics", "\"x\"", r.ics)
        store.reorder("a", list, "c", testNow)
        assertEquals(listOf("c", "a", "b"), order(store, list))
        assertEquals(listOf("a"), store.dirtyRecords(list).map { it.item.uid }, "nur der verschobene Eintrag wird geändert")
    }

    @Test
    fun reorderWithoutGapRenumbers() {
        val (store, list) = makeStore()
        store.save(todo("a") { it.copy(sortOrder = 1) }, list, testNow)
        store.save(todo("b") { it.copy(sortOrder = 2) }, list, testNow)
        store.save(todo("x") { it.copy(sortOrder = 10) }, list, testNow)
        store.reorder("x", list, "a", testNow)
        assertEquals(listOf("a", "x", "b"), order(store, list))
        assertEquals(listOf<Int?>(1024, 2048, 3072), store.tasks(list).map { it.item.sortOrder })
    }

    @Test
    fun deleteCascadesAndHardDeletesUnsynced() {
        val (store, list) = makeStore()
        store.save(todo("p"), list, testNow)
        store.save(todo("k1") { it.copy(parentUID = "p") }, list, testNow)
        store.save(todo("k2") { it.copy(parentUID = "k1") }, list, testNow)
        store.delete("p", list)
        assertEquals(0, store.tasks(list).size)
        assertEquals(0, store.rawTaskRowCount(), "nie hochgeladen: keine Spur")
    }

    @Test
    fun deleteMarksSyncedItemsAndUndeleteRestores() {
        val (store, list) = makeStore()
        val rec = store.save(todo("a"), list, testNow)
        store.markSynced("a", list, "${list}a.ics", "\"1\"", rec.ics)
        store.delete("a", list)
        assertEquals(0, store.tasks(list).size)
        assertEquals(true, store.dirtyRecords(list).firstOrNull()?.isDeleted)
        store.undelete("a", list)
        assertEquals(listOf("a"), store.tasks(list).map { it.item.title })
    }

    @Test
    fun moveCarriesSubtasksAndTracksOriginalLocation() {
        val (store, a) = makeStore()
        val b = "/remote.php/dav/calendars/nutzer/privat/"
        store.applyRemoteLists(listOf(RemoteList(a, "Arbeit"), RemoteList(b, "Privat")))
        for ((uid, parent) in listOf("p" to null, "k" to "p")) {
            val r = store.save(todo(uid) { it.copy(parentUID = parent) }, a, testNow)
            store.markSynced(uid, a, "$a$uid.ics", "\"1\"", r.ics)
        }
        store.move("p", a, b)
        assertEquals(0, store.tasks(a).size)
        assertEquals(listOf("k", "p"), store.tasks(b).map { it.item.uid }.sorted())
        assertEquals(2, store.pendingMoves().size)
        assertTrue(store.tasks(b).all { !it.isDirty })

        store.move("p", b, a) // zurück: Verschiebung hebt sich auf
        assertEquals(0, store.pendingMoves().size)
        assertEquals(2, store.tasks(a).size)
    }

    @Test
    fun persistenceAcrossReopen() {
        val first = newBackend()
        val s = Store(first)
        s.applyRemoteLists(listOf(RemoteList("/l/", "L")))
        s.save(todo("bleibt"), "/l/", testNow)
        s.saveAccount(Account("https://x.example", "nutzer"))
        val second = reopen(first) ?: return // nicht dauerhaft speichernde Implementierung
        val s2 = Store(second)
        assertEquals(listOf("bleibt"), s2.tasks("/l/").map { it.item.title })
        assertEquals("nutzer", s2.account()?.loginName)
    }

    @Test
    fun wipeRemovesEverything() {
        val (store, list) = makeStore()
        store.save(todo("a"), list, testNow)
        store.saveAccount(Account("https://x.example", "nutzer"))
        store.wipe()
        assertEquals(0, store.lists().size)
        assertNull(store.account())
    }

    @Test
    fun allTasksOrderedByDueDate() {
        val (store, list) = makeStore()
        store.save(todo("spät") { it.copy(due = ICalDate.day(2026, 12, 24)) }, list, testNow)
        store.save(todo("ohne"), list, testNow)
        store.save(todo("früh") { it.copy(due = ICalDate.day(2026, 11, 12)) }, list, testNow)
        assertEquals(listOf("früh", "spät", "ohne"), store.allTasks().map { it.item.title })
    }

    @Test
    fun settingsAndRevisionCounter() {
        val (store, list) = makeStore()
        assertNull(store.setting("theme"))
        store.setSetting("theme", "graphit")
        assertEquals("graphit", store.setting("theme"))
        store.setSetting("theme", null)
        assertNull(store.setting("theme"))
        val before = store.revision.value
        store.save(todo("a"), list, testNow)
        assertTrue(store.revision.value > before, "jede Änderung zählt die Revision hoch")
    }

    @Test
    fun deleteListCascadesToItems() {
        val (store, list) = makeStore()
        store.save(todo("a"), list, testNow)
        val created = store.createList("Neu", null, "/remote.php/dav/calendars/nutzer/")
        store.save(todo("b"), created.href, testNow)
        store.deleteList(created.href) // nie hochgeladen: verschwindet sofort samt Einträgen
        assertEquals(listOf(list), store.lists().map { it.href })
        assertEquals(1, store.rawTaskRowCount())
    }

    @Test
    fun openTasksSkipsDoneAndDeleted() {
        val (store, list) = makeStore()
        store.save(todo("offen"), list, testNow)
        store.save(todo("fertig") { it.copy(isCompleted = true) }, list, testNow)
        assertEquals(listOf("offen"), store.openTasks().map { it.item.title })
        assertEquals(1, store.openTaskCount(list))
    }

    /** Richtwerte: 5.000 Einträge in 10 Listen; Messwerte werden ausgegeben. */
    @Test
    fun fiveThousandItemsLoadTime() {
        val store = Store(newBackend())
        store.applyRemoteLists((0 until 10).map { RemoteList("/l$it/", "L$it") })
        for (i in 0 until 5000) {
            val t = todo("Eintrag $i") {
                it.copy(
                    notes = "Notiz zu Eintrag $i mit etwas Text, damit die Größe realistisch wird.",
                    due = ICalDate.day(2026, 11, 1 + i % 28),
                    priority = if (i % 3 == 0) 1 else 0,
                    reminders = listOf(Reminder(ReminderTrigger.Relative(-3600.0, true))),
                )
            }
            store.save(t, "/l${i % 10}/", testNow)
        }
        val start = System.nanoTime()
        val one = store.tasks("/l3/")
        val oneMs = (System.nanoTime() - start) / 1_000_000
        val allStart = System.nanoTime()
        val all = store.allTasks()
        val allMs = (System.nanoTime() - allStart) / 1_000_000
        println("MESSUNG 5000 Einträge (${this::class.simpleName}): Liste (500) $oneMs ms, alle $allMs ms")
        assertEquals(500, one.size)
        assertEquals(5000, all.size)
        assertTrue(allMs < 5000)
    }
}
