package app.listwerk.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val later = testNow.plusSeconds(100)

    private fun record(env: Env, uid: String, list: String? = null): TaskRecord =
        assertNotNull(env.store.record(uid, list ?: env.list), "Eintrag $uid fehlt lokal")

    // MARK: Herunterladen

    @Test
    fun initialPullAndIdleSecondSync() = runBlocking {
        val env = Env.make(listOf(todo("Eins"), todo("Zwei") { it.copy(priority = 1) }))
        val r1 = env.sync()
        assertTrue(r1.isClean, "${r1.errors}")
        assertEquals(2, r1.pulled)
        assertEquals(listOf("Arbeit"), env.store.lists().map { it.name })
        assertEquals(listOf("Eins", "Zwei"), env.store.tasks(env.list).map { it.item.title }.sorted())
        assertTrue(env.store.tasks(env.list).all { !it.isDirty && it.etag != null && it.href != null })

        env.server.resetCalls()
        val r2 = env.sync()
        assertEquals(0, r2.pulled)
        assertEquals(0, env.server.count("fetch"), "ohne Änderungen wird nichts geladen")
        assertEquals(0, env.server.count("put"))
    }

    @Test
    fun pullEditFromServer() = runBlocking {
        val env = Env.make(listOf(todo("Alt")))
        env.sync()
        env.server.clock = later
        env.server.edit(env.list, "alt") { it.copy(title = "Neu", notes = "Notiz") }
        env.sync()
        val r = record(env, "alt")
        assertEquals("Neu", r.item.title)
        assertEquals("Notiz", r.item.notes)
        assertFalse(r.isDirty)
    }

    @Test
    fun serverDeleteRemovesLocal() = runBlocking {
        val env = Env.make(listOf(todo("Weg"), todo("Bleibt")))
        env.sync()
        env.server.remove(env.list, "weg")
        val r = env.sync()
        assertEquals(1, r.deleted)
        assertEquals(listOf("Bleibt"), env.store.tasks(env.list).map { it.item.title })
    }

    // MARK: Hochladen

    @Test
    fun pushNewItem() = runBlocking {
        val env = Env.make()
        env.sync()
        env.store.save(todo("Neu") { it.copy(due = ICalDate.day(2026, 12, 1)) }, env.list, later)
        assertTrue(record(env, "neu").isDirty)
        val r = env.sync()
        assertEquals(1, r.pushed)
        assertEquals(listOf("Neu"), env.server.titles(env.list))
        val rec = record(env, "neu")
        assertFalse(rec.isDirty)
        assertNotNull(rec.etag)
        assertEquals(rec.ics, rec.baseICS)
        assertEquals(ICalDate.day(2026, 12, 1), env.server.item(env.list, "neu")?.due)
    }

    @Test
    fun pushEditThenIdle() = runBlocking {
        val env = Env.make(listOf(todo("Alt")))
        env.sync()
        env.store.save(record(env, "alt").item.copy(title = "Geändert"), env.list, later)
        env.sync()
        assertEquals(listOf("Geändert"), env.server.titles(env.list))
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("put"))
        assertEquals(0, env.server.count("fetch"), "eigene Änderungen werden nicht zurückgeladen")
    }

    @Test
    fun serverWithoutETagOnPutRefetches() = runBlocking {
        val env = Env.make()
        env.sync()
        env.server.omitETagOnPut = true
        env.store.save(todo("Neu"), env.list, later)
        env.sync()
        val serverETag = env.server.collections[env.list]!!.items.values.first().etag
        assertEquals(serverETag, record(env, "neu").etag)
    }

    @Test
    fun readOnlyListIsNotWritten() = runBlocking {
        val env = Env.make()
        val ro = env.server.addCollection("Lesen", canWrite = false)
        env.server.seed(ro, todo("Fremd"))
        env.sync()
        env.store.save(record(env, "fremd", ro).item.copy(title = "Manipuliert"), ro, later)
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("put"))
    }

    // MARK: Konflikte

    @Test
    fun differentFieldsMergeWithoutConflict() = runBlocking {
        val env = Env.make(listOf(todo("Aufgabe")))
        env.sync()
        env.store.save(record(env, "aufgabe").item.copy(priority = 1), env.list, later)
        env.server.clock = testNow.plusSeconds(50)
        env.server.edit(env.list, "aufgabe") { it.copy(title = "Aufgabe neu") }

        val r = env.sync()
        assertTrue(r.isClean, "${r.errors}")
        assertEquals(0, r.conflicts)
        val serverItem = assertNotNull(env.server.item(env.list, "aufgabe"))
        assertEquals("Aufgabe neu", serverItem.title)
        assertEquals(1, serverItem.priority)
        val rec = record(env, "aufgabe")
        assertEquals("Aufgabe neu", rec.item.title)
        assertEquals(1, rec.item.priority)
        assertFalse(rec.isDirty)
    }

    @Test
    fun sameFieldNewerLocalWinsAndIsLogged() = runBlocking {
        val env = Env.make(listOf(todo("Original")))
        env.sync()
        env.store.save(record(env, "original").item.copy(title = "Lokal"), env.list, later) // +100 s
        env.server.clock = testNow.plusSeconds(50)
        env.server.edit(env.list, "original") { it.copy(title = "Server") } // +50 s

        val r = env.sync()
        assertEquals(1, r.conflicts)
        assertEquals("Lokal", env.server.item(env.list, "original")?.title)
        val log = env.store.conflicts("original")
        assertEquals(1, log.size)
        assertEquals("title", log[0].field)
        assertEquals("Server", log[0].remoteValue)
    }

    @Test
    fun sameFieldNewerServerWins() = runBlocking {
        val env = Env.make(listOf(todo("Original")))
        env.sync()
        env.store.save(record(env, "original").item.copy(title = "Lokal"), env.list, testNow.plusSeconds(50))
        env.server.clock = later
        env.server.edit(env.list, "original") { it.copy(title = "Server") }

        env.sync()
        assertEquals("Server", record(env, "original").item.title)
        assertEquals("Server", env.server.item(env.list, "original")?.title)
        assertFalse(record(env, "original").isDirty)
    }

    @Test
    fun localEditAfterServerDeleteRecreates() = runBlocking {
        val env = Env.make(listOf(todo("Zurück")))
        env.sync()
        env.server.remove(env.list, "zurück")
        env.store.save(record(env, "zurück").item.copy(notes = "lokal weitergearbeitet"), env.list, later)
        env.sync()
        assertEquals("lokal weitergearbeitet", env.server.item(env.list, "zurück")?.notes)
        assertFalse(record(env, "zurück").isDirty)
    }

    @Test
    fun localDeleteAfterServerEditServerWins() = runBlocking {
        val env = Env.make(listOf(todo("Doch da")))
        env.sync()
        env.store.delete("doch-da", env.list)
        env.server.clock = later
        env.server.edit(env.list, "doch-da") { it.copy(title = "Doch da (bearbeitet)") }
        env.sync()
        assertEquals("Doch da (bearbeitet)", record(env, "doch-da").item.title)
        assertEquals(listOf("Doch da (bearbeitet)"), env.server.titles(env.list))
    }

    // MARK: Löschen

    @Test
    fun localDeleteDeletesOnServerIncludingSubtasks() = runBlocking {
        val env = Env.make(listOf(todo("Eltern"), todo("Kind") { it.copy(parentUID = "eltern") }, todo("Daneben")))
        env.sync()
        env.store.delete("eltern", env.list)
        assertEquals(listOf("Daneben"), env.store.tasks(env.list).map { it.item.title })
        val r = env.sync()
        assertEquals(2, r.deleted)
        assertEquals(listOf("Daneben"), env.server.titles(env.list))
        assertNull(env.store.record("eltern", env.list))
    }

    @Test
    fun deletingNeverUploadedItemLeavesNoTrace() = runBlocking {
        val env = Env.make()
        env.sync()
        env.store.save(todo("Flüchtig"), env.list)
        env.store.delete("flüchtig", env.list)
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("put"))
        assertEquals(0, env.server.count("delete"))
    }

    // MARK: Verschieben

    @Test
    fun moveBetweenListsUsesMove() = runBlocking {
        val env = Env.make(listOf(todo("Wandert")))
        val other = env.server.addCollection("Privat")
        env.sync()
        val oldHref = record(env, "wandert").href!!
        env.store.move("wandert", env.list, other)
        env.sync()

        assertEquals(emptyList(), env.server.hrefs(env.list))
        assertEquals(listOf(other + oldHref.split('/').last()), env.server.hrefs(other))
        assertEquals(1, env.server.count("move"))
        val rec = record(env, "wandert", other)
        assertFalse(rec.isDirty)
        assertEquals(env.server.hrefs(other).first(), rec.href)
        assertEquals(0, env.store.tasks(env.list).size)
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("fetch"))
    }

    @Test
    fun moveFallsBackToPutAndDelete() = runBlocking {
        val env = Env.make(listOf(todo("Wandert")))
        val other = env.server.addCollection("Privat")
        env.server.movesSupported = false
        env.sync()
        env.store.move("wandert", env.list, other)
        val r = env.sync()
        assertTrue(r.isClean, "${r.errors}")
        assertEquals(emptyList(), env.server.hrefs(env.list))
        assertEquals(listOf("Wandert"), env.server.titles(other))
        assertFalse(record(env, "wandert", other).isDirty)
    }

    @Test
    fun moveOfflineCreatedItemCreatesItInTarget() = runBlocking {
        val env = Env.make()
        val other = env.server.addCollection("Privat")
        env.sync()
        env.store.save(todo("Neu"), env.list, later)
        env.store.move("neu", env.list, other)
        env.sync()
        assertEquals(emptyList(), env.server.titles(env.list))
        assertEquals(listOf("Neu"), env.server.titles(other))
        assertEquals(0, env.server.count("move"))
    }

    // MARK: Listen

    @Test
    fun offlineCreatedListIsUploadedWithItsItems() = runBlocking {
        val env = Env.make()
        env.sync()
        val l = env.store.createList("Projekt Größe", "#ff0000", env.server.home)
        assertEquals(env.server.home + "projekt-grosse/", l.href)
        env.store.save(todo("Erster Eintrag"), l.href, later)
        val r = env.sync()
        assertTrue(r.isClean, "${r.errors}")
        assertEquals("Projekt Größe", env.server.collections[l.href]?.name)
        assertEquals(listOf("Erster Eintrag"), env.server.titles(l.href))
        assertEquals(2, env.store.lists().size)
        assertFalse(env.store.list(l.href)!!.pendingCreate)
    }

    @Test
    fun renameAndDeleteList() = runBlocking {
        val env = Env.make(listOf(todo("X")))
        env.sync()
        env.store.renameList(env.list, "Arbeit 2027")
        env.sync()
        assertEquals("Arbeit 2027", env.server.collections[env.list]?.name)
        assertEquals("Arbeit 2027", env.store.list(env.list)?.name)

        env.store.deleteList(env.list)
        assertEquals(0, env.store.lists().size)
        env.sync()
        assertNull(env.server.collections[env.list])
        assertEquals(0, env.store.allListsIncludingPending().size)
    }

    @Test
    fun listDeletedOnServerDisappearsLocally() = runBlocking {
        val env = Env.make(listOf(todo("X")))
        env.sync()
        env.server.deleteCollection(env.list)
        env.sync()
        assertEquals(0, env.store.lists().size)
        assertEquals(0, env.store.allTasks().size)
    }

    // MARK: Ausweichverhalten

    @Test
    fun invalidSyncTokenFallsBackToFullComparison() = runBlocking {
        val env = Env.make(listOf(todo("A"), todo("B")))
        env.sync()
        env.server.invalidateTokens = true
        env.server.remove(env.list, "a")
        env.server.clock = later
        env.server.edit(env.list, "b") { it.copy(title = "B2") }
        env.sync()
        assertEquals(listOf("B2"), env.store.tasks(env.list).map { it.item.title })
    }

    @Test
    fun serverWithoutSyncCollectionUsesETagComparison() = runBlocking {
        val env = Env.make(listOf(todo("A"), todo("B")))
        env.server.supportsSyncCollection = false
        env.sync()
        assertEquals(2, env.store.tasks(env.list).size)
        env.server.remove(env.list, "a")
        env.server.clock = later
        env.server.edit(env.list, "b") { it.copy(title = "B2") }
        env.sync()
        assertEquals(listOf("B2"), env.store.tasks(env.list).map { it.item.title })
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("fetch"))
    }

    @Test
    fun unauthorizedStopsSync() = runBlocking {
        val env = Env.make(listOf(todo("A")))
        env.sync()
        env.server.failAuth = true
        assertFailsWith<DavError.Unauthorized>("401 muss den Abgleich abbrechen") { env.sync() }
        assertEquals(1, env.store.tasks(env.list).size, "lokale Daten bleiben erhalten")
    }

    @Test
    fun discoveryRunsOnceAndPersists() = runBlocking {
        val env = Env.make()
        env.store.saveAccount(env.store.account()!!.copy(homePath = null, principalPath = null))
        env.sync()
        assertEquals(env.server.home, env.store.account()?.homePath)
        env.server.resetCalls()
        env.sync()
        assertEquals(0, env.server.count("discover"))
    }

    // Ergänzungen zur Portierung: Undo-Pfad der Android-Oberfläche

    @Test
    fun restoreAfterDeleteBringsBackSyncedAndUnsyncedItems() = runBlocking {
        val env = Env.make(listOf(todo("Eltern"), todo("Kind") { it.copy(parentUID = "eltern") }))
        env.sync()
        env.store.save(todo("Neues Kind") { it.copy(parentUID = "eltern") }, env.list, later)
        val family = env.store.family("eltern", env.list)
        assertEquals(3, family.size)
        env.store.delete("eltern", env.list)
        assertEquals(0, env.store.tasks(env.list).size)
        env.store.restore(family, env.list, later)
        assertEquals(listOf("Eltern", "Kind", "Neues Kind"), env.store.tasks(env.list).map { it.item.title }.sorted())
        val r = env.sync()
        assertTrue(r.isClean, "${r.errors}")
        assertEquals(listOf("Eltern", "Kind", "Neues Kind"), env.server.titles(env.list))
    }
}
