package app.listwerk.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ICalCodecTest {

    // MARK: Round-Trip

    @Test
    fun unchangedDocumentIsReturnedByteIdentical() {
        for (name in Fixture.all) {
            val text = Fixture.text(name)
            assertEquals(text, doc(text).serialized(), "$name: unverändert muss identisch bleiben")
        }
    }

    @Test
    fun fixturesKeepCRLF() {
        for (name in Fixture.all) assertTrue(Fixture.text(name).contains("\r\n"), "$name: Fixture muss CRLF haben")
    }

    @Test
    fun applyingUnchangedItemChangesNothing() {
        for (name in Fixture.all) {
            val text = Fixture.text(name)
            val d = doc(text)
            d.apply(d.makeItem(), testNow)
            assertEquals(text, d.serialized(), "$name: makeItem → apply darf nichts ändern")
        }
    }

    @Test
    fun forcedReserializationKeepsAllLines() {
        // Auch wenn neu gefaltet wird, müssen alle logischen Zeilen erhalten bleiben.
        for (name in Fixture.all) {
            val text = Fixture.text(name)
            val d = doc(text)
            val item = d.makeItem()
            d.apply(item.copy(notes = item.notes + "x"), testNow)
            d.apply(item, testNow)
            val out = unfoldedLines(d.serialized())
            val original = unfoldedLines(text)
            val volatile = setOf("LAST-MODIFIED", "DTSTAMP", "SEQUENCE", "CREATED")
            fun stable(ls: List<String>) = ls.filter { l -> l.takeWhile { it != ':' && it != ';' } !in volatile }.toSet()
            assertEquals(stable(original), stable(out), name)
            for (line in d.serialized().split("\r\n")) {
                assertTrue(line.toByteArray().size <= 75, "$name: Zeile zu lang: $line")
            }
        }
    }

    // MARK: Lesen

    @Test
    fun nextcloudTaskFields() {
        val t = doc(Fixture.text("nextcloud-tasks")).makeItem()
        assertEquals("5f3c8b1e-0a4d-4f4e-9d61-1c2b3a4d5e6f", t.uid)
        assertEquals("Bericht prüfen", t.title)
        assertEquals("Teil A zuerst, dann Teil B.\nNotenspiegel nicht vergessen.", t.notes)
        assertEquals(1, t.priority)
        assertEquals(listOf("Arbeit", "Labor"), t.tags)
        assertEquals("aa11bb22-cc33-dd44-ee55-ff6677889900", t.parentUID)
        assertEquals(12, t.sortOrder)
        assertFalse(t.isCompleted)
        assertEquals(ICalDate(2026, 11, 20, 15, kind = ICalDate.Kind.Zoned("Asia/Riyadh")), t.due)
        assertEquals(1, t.reminders.size)
        assertEquals(ReminderTrigger.Relative(-3600.0, true), t.reminders[0].trigger)
        assertNull(t.planwerk)
    }

    @Test
    fun appleReminderFields() {
        val t = doc(Fixture.text("apple-reminders")).makeItem()
        assertTrue(t.isCompleted)
        assertEquals(100, t.percentComplete)
        assertEquals(ICalDate.day(2026, 11, 12), t.due)
        assertTrue(t.due!!.isAllDay)
        assertNotNull(t.completedAt)
        if (t.reminders[0].trigger !is ReminderTrigger.Absolute) fail("absoluter Trigger erwartet")
    }

    @Test
    fun thunderbirdKeepsTimezoneAndEmailAlarm() {
        val d = doc(Fixture.text("thunderbird-vtimezone"))
        val t = d.makeItem()
        assertEquals("FREQ=WEEKLY;COUNT=4", t.recurrence)
        assertEquals("EMAIL", t.reminders.firstOrNull()?.action)
        assertFalse(t.reminders[0].firesLocally)
        d.apply(t.copy(title = "Elternbrief fertigstellen"), testNow)
        val out = d.serialized()
        assertTrue(out.contains("BEGIN:VTIMEZONE"))
        assertTrue(out.contains("TZID:Europe/Berlin"))
        assertTrue(out.contains("ATTENDEE:mailto:nutzer@example.org"))
        assertTrue(out.contains("X-MOZ-GENERATION:3"))
        assertTrue(out.contains("SUMMARY:Elternbrief fertigstellen"))
        assertTrue(out.contains("DTSTART;TZID=Europe/Berlin:20261112T080000"))
    }

    @Test
    fun planwerkMetaAndDerivedTags() {
        val t = doc(Fixture.text("planwerk-linked")).makeItem()
        assertEquals(PlanwerkMeta(PlanwerkKind.EXPERIMENT, "Versuche", "Erika Beispiel"), t.planwerk)
        assertEquals(emptyList(), t.tags, "Gruppe, Art und @Person sind keine freien Schlagwörter")
        assertEquals(40, t.percentComplete)
        assertEquals(ICalDate.day(2026, 11, 16), t.start)
        assertEquals(ICalDate.day(2026, 11, 20), t.due)
    }

    @Test
    fun attachments() {
        val d = doc(Fixture.text("inline-attachment"))
        val t = d.makeItem()
        assertEquals(2, t.attachments.size)
        assertEquals("Anhang.pdf", t.attachments[0].filename)
        assertEquals("application/pdf", t.attachments[0].mimeType)
        assertFalse(t.attachments[0].isInline)
        assertTrue(t.attachments[1].isInline)
        d.apply(t.copy(attachments = t.attachments.drop(1)), testNow)
        assertTrue(d.serialized().contains("ENCODING=BASE64"), "eingebetteter Anhang bleibt erhalten")
        assertFalse(d.serialized().contains("Anhang.pdf"))
    }

    // MARK: Schreiben

    @Test
    fun editingOneFieldTouchesOnlyThatFieldAndBumpsStamps() {
        val d = doc(Fixture.text("nextcloud-tasks"))
        val t = d.makeItem()
        d.apply(t.copy(title = "Bericht Nr. 3 prüfen"), testNow)
        val out = doc(d.serialized())
        val t2 = out.makeItem()
        assertEquals("Bericht Nr. 3 prüfen", t2.title)
        assertEquals(t.notes, t2.notes)
        assertEquals(t.due, t2.due)
        assertEquals("20261111T120000Z", out.text("LAST-MODIFIED"))
        assertEquals("20261111T120000Z", out.text("DTSTAMP"))
        assertEquals("0", out.text("SEQUENCE"), "ohne vorherige SEQUENCE beginnt die Zählung bei 0")
        assertEquals("0", out.text("X-OC-HIDESUBTASKS"))
        assertEquals("Asia/Riyadh", out.prop("DUE")?.params?.first()?.value)

        val again = out
        again.apply(again.makeItem().copy(priority = 5), testNow.plusSeconds(60))
        assertEquals("1", again.text("SEQUENCE"))
    }

    @Test
    fun completeAndUncomplete() {
        val d = doc(Fixture.text("nextcloud-tasks"))
        d.apply(d.makeItem().copy(isCompleted = true), testNow)
        assertEquals("COMPLETED", d.text("STATUS"))
        assertEquals("100", d.text("PERCENT-COMPLETE"))
        assertEquals("20261111T120000Z", d.text("COMPLETED"))

        val t = d.makeItem()
        assertTrue(t.isCompleted)
        d.apply(t.copy(isCompleted = false, percentComplete = 0), testNow)
        assertEquals("NEEDS-ACTION", d.text("STATUS"))
        assertNull(d.prop("COMPLETED"))
        assertNull(d.prop("PERCENT-COMPLETE"))
    }

    @Test
    fun dueDateAllDayAndTimed() {
        val d = ICalDocument.create("u-1")
        var t = TodoItem(uid = "u-1", title = "x", due = ICalDate.day(2026, 12, 24))
        d.apply(t, testNow)
        assertEquals("DUE;VALUE=DATE:20261224", d.prop("DUE")?.unfolded)

        val whenAt = Instant.ofEpochSecond(1_797_000_000)
        t = t.copy(due = ICalDate.of(whenAt, allDay = false))
        d.apply(t, testNow)
        assertTrue(d.prop("DUE")!!.value.endsWith("Z"))
        assertEquals(whenAt, d.makeItem().due?.instant())
    }

    @Test
    fun newDocumentRoundTrip() {
        val d = ICalDocument.create("new-1")
        val t = TodoItem(
            uid = "new-1", title = "Hefte, kopieren; abheften",
            notes = "Zeile 1\nZeile 2 \\ Rest",
            tags = listOf("Büro", "a,b"),
            priority = 5, color = "#8839ef", sortOrder = 3, parentUID = "parent-1",
            reminders = listOf(
                Reminder(ReminderTrigger.Relative(-900.0, false)),
                Reminder(ReminderTrigger.Relative(-86_400.0, true), "Morgen fällig"),
                Reminder(ReminderTrigger.Absolute(Instant.ofEpochSecond(1_797_000_000))),
            ),
            attachments = listOf(AttachmentRef("https://cloud.example.org/f/a b.pdf", "application/pdf", "Blatt: 1.pdf")),
        )
        d.apply(t, testNow)

        val text = d.serialized()
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Listwerk//DE\r\nBEGIN:VTODO\r\n"))
        assertTrue(text.endsWith("END:VTODO\r\nEND:VCALENDAR\r\n"))

        val back = doc(text).makeItem()
        assertEquals(t.title, back.title)
        assertEquals(t.notes, back.notes)
        assertEquals(t.tags, back.tags)
        assertEquals(5, back.priority)
        assertEquals("#8839ef", back.color)
        assertEquals(3, back.sortOrder)
        assertEquals("parent-1", back.parentUID)
        assertEquals(t.reminders, back.reminders)
        assertEquals(t.attachments, back.attachments)
        assertNotNull(back.created)
        assertTrue(text.contains("COLOR:"), "RFC-7986-Farbname wird zusätzlich geschrieben")
    }

    @Test
    fun reminderEditKeepsOtherAlarmsRaw() {
        val d = doc(Fixture.text("apple-reminders"))
        val t = d.makeItem()
        d.apply(t.copy(reminders = t.reminders + Reminder(ReminderTrigger.Relative(-600.0, false))), testNow)
        val out = d.serialized()
        assertTrue(out.contains("X-WR-ALARMUID:11112222-3333-4444-5555-666677778888"), "fremde Alarm-Felder bleiben")
        assertEquals(2, doc(out).makeItem().reminders.size)
    }

    @Test
    fun planwerkCategoriesAreWrittenConsistently() {
        val d = doc(Fixture.text("planwerk-linked"))
        val t = d.makeItem()
        d.apply(
            t.copy(planwerk = PlanwerkMeta(PlanwerkKind.MILESTONE, "Abnahmen", "Anton"), tags = listOf("wird-ignoriert")),
            testNow,
        )
        assertEquals("milestone", d.text("X-PLANWERK-KIND"))
        assertEquals("Abnahmen", d.text("X-PLANWERK-GROUP"))
        assertEquals("Anton", d.text("X-PLANWERK-ASSIGNEE"))
        assertEquals("Abnahmen,Meilenstein,@Anton", d.prop("CATEGORIES")?.value)
    }

    @Test
    fun parentRelationKeepsOtherRelations() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:a\r\nSUMMARY:x\r\n" +
            "RELATED-TO;RELTYPE=CHILD:c1\r\nRELATED-TO:p1\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val d = doc(text)
        val t = d.makeItem()
        assertEquals("p1", t.parentUID)
        d.apply(t.copy(parentUID = "p2"), testNow)
        val rel = d.props("RELATED-TO").map { it.unfolded }
        assertTrue("RELATED-TO;RELTYPE=CHILD:c1" in rel)
        assertTrue("RELATED-TO;RELTYPE=PARENT:p2" in rel)
        assertEquals(2, rel.size)
    }

    @Test
    fun otherComponentsAfterVTodoSurvive() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:a\r\nSUMMARY:x\r\nEND:VTODO\r\n" +
            "BEGIN:VTODO\r\nUID:a\r\nRECURRENCE-ID:20261112\r\nSUMMARY:Ausnahme\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val d = doc(text)
        d.apply(d.makeItem().copy(title = "y"), testNow)
        assertTrue(d.serialized().contains("RECURRENCE-ID:20261112"))
        assertTrue(d.serialized().contains("SUMMARY:Ausnahme"))
    }

    @Test
    fun noVTodoGivesNull() {
        assertNull(ICalDocument.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
        assertNull(ICalDocument.parse(""))
    }

    // MARK: Bausteine

    @Test
    fun foldingNeverSplitsUTF8() {
        val line = "SUMMARY:" + "ä".repeat(60) + "🧪" + "ü".repeat(40)
        val folded = ICalText.fold(line)
        for (part in folded.split("\r\n")) assertTrue(part.toByteArray().size <= 75)
        assertEquals(listOf(line), ICalText.unfold(folded))
    }

    @Test
    fun escapeRoundTrip() {
        val s = "a,b;c\\d\ne\r\nf"
        assertEquals("a,b;c\\d\ne\nf", ICalText.unescape(ICalText.escape(s)))
        assertEquals(listOf("a,b", "c", "d"), ICalText.splitList("a\\,b,c,,d"))
    }

    @Test
    fun quotedParameters() {
        val cl = assertNotNull(ContentLine.parse("ATTACH;FILENAME=\"a;b:c.pdf\";FMTTYPE=text/plain:https://x/y"))
        assertEquals("a;b:c.pdf", cl.param("FILENAME"))
        assertEquals("https://x/y", cl.value)
        assertEquals("ATTACH;FILENAME=\"a;b:c.pdf\";FMTTYPE=text/plain:https://x/y", cl.unfolded)
    }

    @Test
    fun dates() {
        assertEquals(ICalDate.day(2026, 11, 20), ICalDate.parse("20261120", emptyList()))
        assertEquals(ICalDate.Kind.Utc, ICalDate.parse("20261120T140000Z", emptyList())?.kind)
        assertEquals(ICalDate.Kind.Floating, ICalDate.parse("20261120T140000", emptyList())?.kind)
        assertEquals(
            ICalDate.Kind.Zoned("Europe/Berlin"),
            ICalDate.parse("20261120T140000", listOf(ICalParam("TZID", "Europe/Berlin")))?.kind,
        )
        assertNull(ICalDate.parse("kaputt", emptyList()))
        val riyadh = ICalDate(2026, 11, 20, 15, kind = ICalDate.Kind.Zoned("Asia/Riyadh"))
        assertEquals(ICalDate(2026, 11, 20, 12, kind = ICalDate.Kind.Utc).instant(), riyadh.instant())
        assertTrue(ICalDate.day(2026, 11, 20).sortKey() < riyadh.sortKey())
        assertTrue(ICalDate.day(2026, 11, 19).sortKey() < ICalDate.day(2026, 11, 20).sortKey())
    }

    @Test
    fun durations() {
        assertEquals(-86_400.0, ICalDuration.parse("-P1D"))
        assertEquals(900.0, ICalDuration.parse("PT15M"))
        assertEquals(-(86_400.0 + 9_000), ICalDuration.parse("-P1DT2H30M"))
        assertEquals(604_800.0, ICalDuration.parse("P1W"))
        assertNull(ICalDuration.parse("15M"))
        for (s in listOf(-86_400.0, -900.0, -5_400.0, 0.0, 3_600.0, -172_800.0)) {
            assertEquals(s, ICalDuration.parse(ICalDuration.format(s)))
        }
        assertEquals("-P1D", ICalDuration.format(-86_400.0))
        assertEquals("-PT1H30M", ICalDuration.format(-5_400.0))
    }
}
