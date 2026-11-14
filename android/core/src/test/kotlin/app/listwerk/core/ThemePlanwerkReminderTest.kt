package app.listwerk.core

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun resource(path: String): String =
    ThemeTest::class.java.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

fun testCatalog(): ThemeCatalog = ThemeCatalog(
    listOf("papier", "graphit", "catppuccin-latte", "catppuccin-frappe", "catppuccin-macchiato", "catppuccin-mocha")
        .reversed().map { resource("/themes/$it.json") },
    listOf("catppuccin", "farbkarten").reversed().map { resource("/palettes/$it.json") },
)

class ThemeTest {
    private val catalog = testCatalog()

    @Test
    fun allThemesLoad() {
        assertEquals(
            listOf("papier", "graphit", "catppuccin-latte", "catppuccin-frappe", "catppuccin-macchiato", "catppuccin-mocha"),
            catalog.all.map { it.id },
        )
        assertEquals(4, catalog.all.count { it.isDark })
    }

    @Test
    fun catppuccinValuesMatchOfficialPalette() {
        val latte = assertNotNull(catalog.theme("catppuccin-latte"))
        assertEquals("#eff1f5", latte.colors.background)
        assertEquals("#4c4f69", latte.colors.textPrimary)
        val mocha = assertNotNull(catalog.theme("catppuccin-mocha"))
        assertEquals("#1e1e2e", mocha.colors.background)
        assertEquals("#89b4fa", mocha.colors.accent)
    }

    @Test
    fun textContrastMeetsWCAGAA() {
        for (t in catalog.all) {
            val c = t.colors
            for (bg in listOf(c.background, c.sidebar)) {
                assertTrue(Contrast.ratio(c.textPrimary, bg) >= 4.5, "${t.id} Haupttext auf $bg")
                assertTrue(Contrast.ratio(c.textSecondary, bg) >= 4.5, "${t.id} Nebentext auf $bg")
            }
            assertTrue(Contrast.ratio(c.textPrimary, c.surface) >= 4.5, "${t.id} Haupttext auf Fläche")
            assertTrue(Contrast.ratio(c.textSecondary, c.surface) >= 4.5, "${t.id} Nebentext auf Fläche")
            assertTrue(Contrast.ratio(c.accent, c.background) >= 3.0, "${t.id} Akzent (Bedienelement)")
        }
    }

    @Test
    fun resolveSystemTheme() {
        assertEquals("papier", catalog.resolve("system", false).id)
        assertEquals("graphit", catalog.resolve("system", true).id)
        assertEquals("catppuccin-mocha", catalog.resolve("catppuccin-mocha", false).id)
        assertEquals("graphit", catalog.resolve("gibt-es-nicht", true).id)
    }

    @Test
    fun palettes() {
        val p = catalog.palettes
        assertEquals(listOf("catppuccin", "farbkarten"), p.map { it.id })
        assertEquals(14, p[0].colors.size)
        assertEquals(20, p[1].colors.size)
        assertEquals("#8839ef", p[0].colors.first { it.id == "mauve" }.storedHex)
        assertFalse(p[1].colors.any { it.name.lowercase().contains("pantone") }, "keine Markennamen")
        assertEquals(20, p[1].colors.map { it.storedHex }.toSet().size)
    }

    @Test
    fun displayColorFollowsTheme() {
        val mocha = catalog.theme("catppuccin-mocha")!!
        val frappe = catalog.theme("catppuccin-frappe")!!
        val papier = catalog.theme("papier")!!
        val graphit = catalog.theme("graphit")!!
        assertEquals("#cba6f7", catalog.displayHex("#8839ef", mocha))
        assertEquals("#ca9ee6", catalog.displayHex("#8839EF", frappe))
        assertEquals("#8839ef", catalog.displayHex("#8839ef", papier))
        assertEquals("#cba6f7", catalog.displayHex("#8839ef", graphit))
        assertEquals("#e03c31", catalog.displayHex("#e03c31", papier))
        assertNotEquals("#e03c31", catalog.displayHex("#e03c31", graphit))
        assertEquals("#123456", catalog.displayHex("#123456", mocha), "Fremdfarben bleiben")
    }
}

class PlanwerkTest {
    companion object {
        // Beispielantwort von GET /api/state (frei erfundene Daten).
        const val STATE_JSON = """
        {"rev":42,"teams":[],"members":[{"id":1,"name":"Erika Beispiel","email":"","color":"#40a02b"},{"id":2,"name":"Anton","email":"","color":"#40a02b"}],
         "projects":[
          {"id":3,"name":"Projekt Alpha","description":"","color":"#2bb07f","start_date":"2026-08-24","end_date":"2027-06-25","team_id":1,"archived":0,"sort":0,
           "nc_calendar":"https://cloud.example.org/remote.php/dav/calendars/nutzer/projekt-alpha","nc_calendar_name":"Projekt Alpha","lessons":"Di D"},
          {"id":4,"name":"Ohne Liste","color":"#1e66f5","archived":0,"nc_calendar":null,"nc_calendar_name":null},
          {"id":5,"name":"Altlast","color":"#1e66f5","archived":1,"nc_calendar":"https://cloud.example.org/remote.php/dav/calendars/nutzer/alt/"}],
         "groups":[{"id":1,"project_id":3,"name":"Versuche","sort":0,"collapsed":0},{"id":2,"project_id":3,"name":"Organisation","sort":1,"collapsed":0},{"id":3,"project_id":4,"name":"Andere","sort":0,"collapsed":0}],
         "tasks":[],"blocks":[],"nextcloud":{"url":null}}
        """
    }

    class Counting : PlanwerkConnector {
        var rev = 1
        var stateCalls = 0
        override suspend fun fetchState(): PlanwerkState {
            stateCalls++
            return PlanwerkJson.parseState(STATE_JSON)
        }
        override suspend fun currentRevision() = rev
        override suspend fun triggerNextcloudSync() {}
    }

    @Test
    fun decodeStateAndLinkedLists() = runBlocking {
        val links = Counting().linkedLists()
        assertEquals(1, links.size, "archivierte und unverknüpfte Projekte entfallen")
        val l = links[0]
        assertEquals("Projekt Alpha", l.projectName)
        assertEquals("/remote.php/dav/calendars/nutzer/projekt-alpha/", l.listPath)
        assertEquals(listOf("Versuche", "Organisation"), l.groups)
        assertEquals(listOf("Anton", "Erika Beispiel"), l.people)
    }

    @Test
    fun clientSendsBearerToken() = runBlocking {
        val t = MockTransport { req -> if (req.url.endsWith("/api/rev")) Reply(200, body = """{"rev":7}""") else Reply(200, body = STATE_JSON) }
        val c = PlanwerkClient("https://planwerk.example.org", "pwk_abc", t)
        assertEquals(7, c.currentRevision())
        assertEquals("Bearer pwk_abc", t.last.header("Authorization"))
        assertEquals(3, c.fetchState().projects.size)
        c.triggerNextcloudSync()
        assertEquals("POST", t.last.method)
        assertEquals("https://planwerk.example.org/api/nextcloud/sync", t.last.url)
    }

    @Test
    fun unauthorizedIsReported() = runBlocking {
        val c = PlanwerkClient("https://p.example.org", "x", MockTransport { Reply(401) })
        assertFailsWith<DavError.Unauthorized> { c.fetchState() }
        Unit
    }

    @Test
    fun plainHttpIsRejected() {
        assertFailsWith<DavError> { PlanwerkClient("http://p.example.org", "x", MockTransport { Reply(200) }) }
        assertFailsWith<DavError> { CalDAVClient("http://cloud.example.org", "n", "p", MockTransport { Reply(200) }) }
    }

    @Test
    fun cacheReloadsOnlyWhenRevisionChanges() = runBlocking {
        val conn = Counting()
        val cache = PlanwerkLinkCache(conn, Duration.ofSeconds(60))
        val t0 = Instant.ofEpochSecond(1_800_000_000)
        cache.current(t0)
        cache.current(t0.plusSeconds(10)) // innerhalb des Intervalls
        cache.current(t0.plusSeconds(120)) // gleiche Revision
        assertEquals(1, conn.stateCalls)
        conn.rev = 2
        cache.current(t0.plusSeconds(300))
        assertEquals(2, conn.stateCalls)
    }

    @Test
    fun marksMatchingListsAsLinked() = runBlocking {
        val store = Store(InMemoryBackend())
        store.applyRemoteLists(
            listOf(
                RemoteList("/remote.php/dav/calendars/nutzer/projekt-alpha/", "Projekt Alpha"),
                RemoteList("/remote.php/dav/calendars/nutzer/privat/", "Privat"),
            ),
        )
        PlanwerkLinkCache(Counting()).apply(store)
        assertEquals(listOf(true, false), store.lists().map { it.isPlanwerkLinked })
    }
}

class ReminderPlannerTest {
    private val riyadh = ZoneId.of("Asia/Riyadh")
    private fun rec(item: TodoItem, deleted: Boolean = false) =
        TaskRecord(item, "/l/", null, null, false, deleted, "", null)

    @Test
    fun relativeToAllDayDueUsesNineAM() {
        val t = todo("Abgabe") { it.copy(due = ICalDate.day(2026, 11, 20), reminders = listOf(Reminder(ReminderTrigger.Relative(-86_400.0, true)))) }
        val plan = ReminderPlanner.plan(listOf(rec(t)), Instant.ofEpochSecond(1_790_000_000), zone = riyadh)
        assertEquals(1, plan.size)
        val z = plan[0].fireDate.atZone(riyadh)
        assertEquals(listOf(2026, 11, 19, 9), listOf(z.year, z.monthValue, z.dayOfMonth, z.hour))
        assertEquals("/l/|abgabe|0", plan[0].id)
    }

    @Test
    fun excludesPastCompletedDeletedAndEmail() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val future = Reminder(ReminderTrigger.Absolute(now.plusSeconds(3600)))
        val past = Reminder(ReminderTrigger.Absolute(now.minusSeconds(3600)))
        val email = Reminder(ReminderTrigger.Absolute(now.plusSeconds(3600)), action = "EMAIL")
        val ok = todo("ok") { it.copy(reminders = listOf(future, past, email)) }
        val done = todo("fertig") { it.copy(isCompleted = true, reminders = listOf(future)) }
        val gone = todo("weg") { it.copy(reminders = listOf(future)) }
        val plan = ReminderPlanner.plan(listOf(rec(ok), rec(done), rec(gone, deleted = true)), now, zone = riyadh)
        assertEquals(listOf("ok"), plan.map { it.uid })
        assertEquals(1, plan.size)
    }

    @Test
    fun limitKeepsNearestInOrder() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val items = (0 until 100).map { i ->
            rec(todo("t$i") { it.copy(reminders = listOf(Reminder(ReminderTrigger.Absolute(now.plusSeconds((100 - i) * 60L))))) })
        }
        val plan = ReminderPlanner.plan(items, now, zone = riyadh)
        assertEquals(64, plan.size)
        assertEquals("t99", plan.first().uid)
        assertTrue(plan.zipWithNext().all { (a, b) -> a.fireDate <= b.fireDate })
    }

    @Test
    fun relativeToStartFallsBackToDue() {
        val t = todo("x") {
            it.copy(
                due = ICalDate(2026, 11, 20, 12, kind = ICalDate.Kind.Utc),
                reminders = listOf(Reminder(ReminderTrigger.Relative(-900.0, false))),
            )
        }
        val d = ReminderPlanner.fireDate(t.reminders[0], t, riyadh)
        assertEquals(ICalDate(2026, 11, 20, 11, 45, kind = ICalDate.Kind.Utc).instant(), d)
    }
}
