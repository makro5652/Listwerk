import XCTest
@testable import ListwerkCore

final class ICalCodecTests: XCTestCase {

    // MARK: Round-Trip

    func testUnchangedDocumentIsReturnedByteIdentical() throws {
        for name in Fixture.all {
            let text = try Fixture.text(name)
            let doc = try XCTUnwrap(ICalDocument(parsing: text), name)
            XCTAssertEqual(doc.serialized(), text, "\(name): unverändert muss identisch bleiben")
        }
    }

    func testApplyingUnchangedItemChangesNothing() throws {
        for name in Fixture.all {
            let text = try Fixture.text(name)
            var doc = try XCTUnwrap(ICalDocument(parsing: text), name)
            doc.apply(doc.makeItem(), now: testNow)
            XCTAssertEqual(doc.serialized(), text, "\(name): makeItem → apply darf nichts ändern")
        }
    }

    func testForcedReserializationKeepsAllLines() throws {
        // Auch wenn neu gefaltet wird, müssen alle logischen Zeilen erhalten bleiben.
        for name in Fixture.all {
            let text = try Fixture.text(name)
            var doc = try XCTUnwrap(ICalDocument(parsing: text), name)
            var item = doc.makeItem()
            item.notes += "x"
            doc.apply(item, now: testNow)
            item.notes.removeLast()
            doc.apply(item, now: testNow)
            let out = unfoldedLines(doc.serialized())
            let original = unfoldedLines(text)
            let volatile: Set<String> = ["LAST-MODIFIED", "DTSTAMP", "SEQUENCE", "CREATED"]
            func stable(_ ls: [String]) -> Set<String> {
                Set(ls.filter { l in !volatile.contains(String(l.prefix { $0 != ":" && $0 != ";" })) })
            }
            XCTAssertEqual(stable(out), stable(original), name)
            for line in doc.serialized().components(separatedBy: "\r\n") {
                XCTAssertLessThanOrEqual(line.utf8.count, 75, "\(name): Zeile zu lang: \(line)")
            }
        }
    }

    // MARK: Lesen

    func testNextcloudTaskFields() throws {
        let doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("nextcloud-tasks")))
        let t = doc.makeItem()
        XCTAssertEqual(t.uid, "5f3c8b1e-0a4d-4f4e-9d61-1c2b3a4d5e6f")
        XCTAssertEqual(t.title, "Bericht prüfen")
        XCTAssertEqual(t.notes, "Teil A zuerst, dann Teil B.\nNotenspiegel nicht vergessen.")
        XCTAssertEqual(t.priority, 1)
        XCTAssertEqual(t.tags, ["Arbeit", "Labor"])
        XCTAssertEqual(t.parentUID, "aa11bb22-cc33-dd44-ee55-ff6677889900")
        XCTAssertEqual(t.sortOrder, 12)
        XCTAssertFalse(t.isCompleted)
        XCTAssertEqual(t.due, ICalDate(year: 2026, month: 11, day: 20, hour: 15, kind: .zoned("Asia/Riyadh")))
        XCTAssertEqual(t.reminders.count, 1)
        XCTAssertEqual(t.reminders[0].trigger, .relative(-3600, toEnd: true))
        XCTAssertNil(t.planwerk)
    }

    func testAppleReminderFields() throws {
        let t = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("apple-reminders"))).makeItem()
        XCTAssertTrue(t.isCompleted)
        XCTAssertEqual(t.percentComplete, 100)
        XCTAssertEqual(t.due, .day(2026, 11, 12))
        XCTAssertTrue(t.due!.isAllDay)
        XCTAssertNotNil(t.completedAt)
        guard case .absolute = t.reminders[0].trigger else { return XCTFail("absoluter Trigger erwartet") }
    }

    func testThunderbirdKeepsTimezoneAndEmailAlarm() throws {
        var doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("thunderbird-vtimezone")))
        var t = doc.makeItem()
        XCTAssertEqual(t.recurrence, "FREQ=WEEKLY;COUNT=4")
        XCTAssertEqual(t.reminders.first?.action, "EMAIL")
        XCTAssertFalse(t.reminders[0].firesLocally)
        t.title = "Elternbrief fertigstellen"
        doc.apply(t, now: testNow)
        let out = doc.serialized()
        XCTAssertTrue(out.contains("BEGIN:VTIMEZONE"))
        XCTAssertTrue(out.contains("TZID:Europe/Berlin"))
        XCTAssertTrue(out.contains("ATTENDEE:mailto:nutzer@example.org"))
        XCTAssertTrue(out.contains("X-MOZ-GENERATION:3"))
        XCTAssertTrue(out.contains("SUMMARY:Elternbrief fertigstellen"))
        XCTAssertTrue(out.contains("DTSTART;TZID=Europe/Berlin:20261112T080000"))
    }

    func testPlanwerkMetaAndDerivedTags() throws {
        let t = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("planwerk-linked"))).makeItem()
        XCTAssertEqual(t.planwerk, PlanwerkMeta(kind: .experiment, group: "Versuche", assignee: "Erika Beispiel"))
        XCTAssertEqual(t.tags, [], "Gruppe, Art und @Person sind keine freien Schlagwörter")
        XCTAssertEqual(t.percentComplete, 40)
        XCTAssertEqual(t.start, .day(2026, 11, 16))
        XCTAssertEqual(t.due, .day(2026, 11, 20))
    }

    func testAttachments() throws {
        var doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("inline-attachment")))
        var t = doc.makeItem()
        XCTAssertEqual(t.attachments.count, 2)
        XCTAssertEqual(t.attachments[0].filename, "Anhang.pdf")
        XCTAssertEqual(t.attachments[0].mimeType, "application/pdf")
        XCTAssertFalse(t.attachments[0].isInline)
        XCTAssertTrue(t.attachments[1].isInline)
        t.attachments.remove(at: 0)
        doc.apply(t, now: testNow)
        XCTAssertTrue(doc.serialized().contains("ENCODING=BASE64"), "eingebetteter Anhang bleibt erhalten")
        XCTAssertFalse(doc.serialized().contains("Anhang.pdf"))
    }

    // MARK: Schreiben

    func testEditingOneFieldTouchesOnlyThatFieldAndBumpsStamps() throws {
        let text = try Fixture.text("nextcloud-tasks")
        var doc = try XCTUnwrap(ICalDocument(parsing: text))
        var t = doc.makeItem()
        t.title = "Bericht Nr. 3 prüfen"
        doc.apply(t, now: testNow)
        let out = try XCTUnwrap(ICalDocument(parsing: doc.serialized()))
        let t2 = out.makeItem()
        XCTAssertEqual(t2.title, "Bericht Nr. 3 prüfen")
        XCTAssertEqual(t2.notes, t.notes)
        XCTAssertEqual(t2.due, t.due)
        XCTAssertEqual(out.text("LAST-MODIFIED"), "20261111T120000Z")
        XCTAssertEqual(out.text("DTSTAMP"), "20261111T120000Z")
        XCTAssertEqual(out.text("SEQUENCE"), "0", "ohne vorherige SEQUENCE beginnt die Zählung bei 0")
        XCTAssertEqual(out.text("X-OC-HIDESUBTASKS"), "0")
        XCTAssertEqual(out.prop("DUE")?.params.first?.value, "Asia/Riyadh")

        var again = out
        var t3 = again.makeItem()
        t3.priority = 5
        again.apply(t3, now: testNow.addingTimeInterval(60))
        XCTAssertEqual(again.text("SEQUENCE"), "1")
    }

    func testCompleteAndUncomplete() throws {
        var doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("nextcloud-tasks")))
        var t = doc.makeItem()
        t.isCompleted = true
        doc.apply(t, now: testNow)
        XCTAssertEqual(doc.text("STATUS"), "COMPLETED")
        XCTAssertEqual(doc.text("PERCENT-COMPLETE"), "100")
        XCTAssertEqual(doc.text("COMPLETED"), "20261111T120000Z")

        t = doc.makeItem()
        XCTAssertTrue(t.isCompleted)
        t.isCompleted = false
        t.percentComplete = 0
        doc.apply(t, now: testNow)
        XCTAssertEqual(doc.text("STATUS"), "NEEDS-ACTION")
        XCTAssertNil(doc.prop("COMPLETED"))
        XCTAssertNil(doc.prop("PERCENT-COMPLETE"))
    }

    func testDueDateAllDayAndTimed() throws {
        var doc = ICalDocument(newWithUID: "u-1")
        var t = TodoItem(uid: "u-1", title: "x")
        t.due = .day(2026, 12, 24)
        doc.apply(t, now: testNow)
        XCTAssertEqual(doc.prop("DUE")?.unfolded, "DUE;VALUE=DATE:20261224")

        let when = Date(timeIntervalSince1970: 1_797_000_000)
        t.due = ICalDate(date: when, allDay: false)
        doc.apply(t, now: testNow)
        XCTAssertTrue(doc.prop("DUE")!.value.hasSuffix("Z"))
        XCTAssertEqual(doc.makeItem().due?.date(), when)
    }

    func testNewDocumentRoundTrip() throws {
        var doc = ICalDocument(newWithUID: "new-1")
        var t = TodoItem(uid: "new-1", title: "Hefte, kopieren; abheften")
        t.notes = "Zeile 1\nZeile 2 \\ Rest"
        t.tags = ["Büro", "a,b"]
        t.priority = 5
        t.color = "#8839ef"
        t.sortOrder = 3
        t.parentUID = "parent-1"
        t.reminders = [
            Reminder(trigger: .relative(-900, toEnd: false)),
            Reminder(trigger: .relative(-86_400, toEnd: true), message: "Morgen fällig"),
            Reminder(trigger: .absolute(Date(timeIntervalSince1970: 1_797_000_000))),
        ]
        t.attachments = [AttachmentRef(uri: "https://cloud.example.org/f/a b.pdf", mimeType: "application/pdf", filename: "Blatt: 1.pdf")]
        doc.apply(t, now: testNow)

        let text = doc.serialized()
        XCTAssertTrue(text.hasPrefix("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Listwerk//DE\r\nBEGIN:VTODO\r\n"))
        XCTAssertTrue(text.hasSuffix("END:VTODO\r\nEND:VCALENDAR\r\n"))

        let back = try XCTUnwrap(ICalDocument(parsing: text)).makeItem()
        XCTAssertEqual(back.title, t.title)
        XCTAssertEqual(back.notes, t.notes)
        XCTAssertEqual(back.tags, t.tags)
        XCTAssertEqual(back.priority, 5)
        XCTAssertEqual(back.color, "#8839ef")
        XCTAssertEqual(back.sortOrder, 3)
        XCTAssertEqual(back.parentUID, "parent-1")
        XCTAssertEqual(back.reminders, t.reminders)
        XCTAssertEqual(back.attachments, t.attachments)
        XCTAssertNotNil(back.created)
        XCTAssertTrue(text.contains("COLOR:"), "RFC-7986-Farbname wird zusätzlich geschrieben")
    }

    func testReminderEditKeepsOtherAlarmsRaw() throws {
        var doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("apple-reminders")))
        var t = doc.makeItem()
        t.reminders.append(Reminder(trigger: .relative(-600, toEnd: false)))
        doc.apply(t, now: testNow)
        let out = doc.serialized()
        XCTAssertTrue(out.contains("X-WR-ALARMUID:11112222-3333-4444-5555-666677778888"), "fremde Alarm-Felder bleiben")
        XCTAssertEqual(try XCTUnwrap(ICalDocument(parsing: out)).makeItem().reminders.count, 2)
    }

    func testPlanwerkCategoriesAreWrittenConsistently() throws {
        var doc = try XCTUnwrap(ICalDocument(parsing: try Fixture.text("planwerk-linked")))
        var t = doc.makeItem()
        t.planwerk = PlanwerkMeta(kind: .milestone, group: "Abnahmen", assignee: "Anton")
        t.tags = ["wird-ignoriert"]
        doc.apply(t, now: testNow)
        XCTAssertEqual(doc.text("X-PLANWERK-KIND"), "milestone")
        XCTAssertEqual(doc.text("X-PLANWERK-GROUP"), "Abnahmen")
        XCTAssertEqual(doc.text("X-PLANWERK-ASSIGNEE"), "Anton")
        XCTAssertEqual(doc.prop("CATEGORIES")?.value, "Abnahmen,Meilenstein,@Anton")
    }

    func testParentRelationKeepsOtherRelations() throws {
        let text = """
        BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:a\r\nSUMMARY:x\r\n\
        RELATED-TO;RELTYPE=CHILD:c1\r\nRELATED-TO:p1\r\nEND:VTODO\r\nEND:VCALENDAR\r\n
        """
        var doc = try XCTUnwrap(ICalDocument(parsing: text))
        var t = doc.makeItem()
        XCTAssertEqual(t.parentUID, "p1")
        t.parentUID = "p2"
        doc.apply(t, now: testNow)
        let rel = doc.props("RELATED-TO").map(\.unfolded)
        XCTAssertTrue(rel.contains("RELATED-TO;RELTYPE=CHILD:c1"))
        XCTAssertTrue(rel.contains("RELATED-TO;RELTYPE=PARENT:p2"))
        XCTAssertEqual(rel.count, 2)
    }

    func testOtherComponentsAfterVTodoSurvive() throws {
        let text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:a\r\nSUMMARY:x\r\nEND:VTODO\r\nBEGIN:VTODO\r\nUID:a\r\nRECURRENCE-ID:20261112\r\nSUMMARY:Ausnahme\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        var doc = try XCTUnwrap(ICalDocument(parsing: text))
        var t = doc.makeItem()
        t.title = "y"
        doc.apply(t, now: testNow)
        XCTAssertTrue(doc.serialized().contains("RECURRENCE-ID:20261112"))
        XCTAssertTrue(doc.serialized().contains("SUMMARY:Ausnahme"))
    }

    func testNoVTodoGivesNil() {
        XCTAssertNil(ICalDocument(parsing: "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
        XCTAssertNil(ICalDocument(parsing: ""))
    }

    // MARK: Bausteine

    func testFoldingNeverSplitsUTF8() {
        let line = "SUMMARY:" + String(repeating: "ä", count: 60) + "🧪" + String(repeating: "ü", count: 40)
        let folded = ICalText.fold(line)
        for part in folded.components(separatedBy: "\r\n") {
            XCTAssertLessThanOrEqual(part.utf8.count, 75)
        }
        XCTAssertEqual(ICalText.unfold(folded), [line])
    }

    func testEscapeRoundTrip() {
        let s = "a,b;c\\d\ne\r\nf"
        XCTAssertEqual(ICalText.unescape(ICalText.escape(s)), "a,b;c\\d\ne\nf")
        XCTAssertEqual(ICalText.splitList("a\\,b,c,,d"), ["a,b", "c", "d"])
    }

    func testQuotedParameters() throws {
        let cl = try XCTUnwrap(ContentLine(parsing: "ATTACH;FILENAME=\"a;b:c.pdf\";FMTTYPE=text/plain:https://x/y"))
        XCTAssertEqual(cl.param("FILENAME"), "a;b:c.pdf")
        XCTAssertEqual(cl.value, "https://x/y")
        XCTAssertEqual(cl.unfolded, "ATTACH;FILENAME=\"a;b:c.pdf\";FMTTYPE=text/plain:https://x/y")
    }

    func testDates() {
        XCTAssertEqual(ICalDate(value: "20261120", params: []), .day(2026, 11, 20))
        XCTAssertEqual(ICalDate(value: "20261120T140000Z", params: [])?.kind, .utc)
        XCTAssertEqual(ICalDate(value: "20261120T140000", params: [])?.kind, .floating)
        XCTAssertEqual(ICalDate(value: "20261120T140000", params: [ICalParam(name: "TZID", value: "Europe/Berlin")])?.kind, .zoned("Europe/Berlin"))
        XCTAssertNil(ICalDate(value: "kaputt", params: []))
        let riyadh = ICalDate(year: 2026, month: 11, day: 20, hour: 15, kind: .zoned("Asia/Riyadh"))
        XCTAssertEqual(riyadh.date().timeIntervalSince1970, ICalDate(year: 2026, month: 11, day: 20, hour: 12, kind: .utc).date().timeIntervalSince1970)
        XCTAssertLessThan(ICalDate.day(2026, 11, 20).sortKey, riyadh.sortKey)
        XCTAssertLessThan(ICalDate.day(2026, 11, 19).sortKey, ICalDate.day(2026, 11, 20).sortKey)
    }

    func testDurations() {
        XCTAssertEqual(ICalDuration.parse("-P1D"), -86_400)
        XCTAssertEqual(ICalDuration.parse("PT15M"), 900)
        XCTAssertEqual(ICalDuration.parse("-P1DT2H30M"), -(86_400 + 9_000))
        XCTAssertEqual(ICalDuration.parse("P1W"), 604_800)
        XCTAssertNil(ICalDuration.parse("15M"))
        for s in [-86_400.0, -900, -5_400, 0, 3_600, -172_800] {
            XCTAssertEqual(ICalDuration.parse(ICalDuration.format(s)), s)
        }
        XCTAssertEqual(ICalDuration.format(-86_400), "-P1D")
        XCTAssertEqual(ICalDuration.format(-5_400), "-PT1H30M")
    }
}
