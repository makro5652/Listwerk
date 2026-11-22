import XCTest
@testable import ListwerkCore

final class QuickEntryTests: XCTestCase {
    // Freitag, 20.11.2026, 10:00 in Berlin.
    private var cal: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Europe/Berlin")!; return c }()
    private var now: Date { cal.date(from: DateComponents(year: 2026, month: 11, day: 20, hour: 10))! }
    private func p(_ s: String) -> QuickEntry { QuickEntry.parse(s, now: now, calendar: cal) }
    private func ymdhm(_ d: ICalDate?) -> [Int] {
        guard let d else { return [] }
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: d.date(in: cal.timeZone))
        return [c.year!, c.month!, c.day!, c.hour!, c.minute!]
    }

    func testExampleFromUser() {
        let q = p("Elternbrief morgen 8 Uhr !")
        XCTAssertEqual(q.title, "Elternbrief")
        XCTAssertTrue(q.important)
        XCTAssertEqual(ymdhm(q.due), [2026, 11, 21, 8, 0])
        XCTAssertFalse(q.due!.isAllDay)
    }

    func testDatesAndWeekdays() {
        XCTAssertEqual(p("Bericht heute").due, .day(2026, 11, 20))
        XCTAssertEqual(p("Bericht übermorgen").due, .day(2026, 11, 22))
        XCTAssertEqual(p("Konferenz am Montag").due, .day(2026, 11, 23))
        XCTAssertEqual(p("Konferenz am Montag").title, "Konferenz")
        XCTAssertEqual(p("Freitagsrunde Freitag").due, .day(2026, 11, 20), "heute zählt mit")
        XCTAssertEqual(p("Zeugnisse 24.11.").due, .day(2026, 11, 24))
        XCTAssertEqual(p("Abitur 3.2.").due, .day(2027, 2, 3), "vergangenes Datum ohne Jahr → nächstes Jahr")
        XCTAssertEqual(p("Abitur 3.2.2027").due, .day(2027, 2, 3))
        XCTAssertEqual(p("Abitur 3.2.27").due, .day(2027, 2, 3))
        XCTAssertEqual(p("Rückmeldung in 3 Tagen").due, .day(2026, 11, 23))
        XCTAssertEqual(p("Rückmeldung in 2 Wochen").due, .day(2026, 12, 4))
        XCTAssertNil(p("31.2. ist kein Datum").due)
    }

    func testTimes() {
        XCTAssertEqual(ymdhm(p("Anruf 14:30").due), [2026, 11, 20, 14, 30])
        XCTAssertEqual(ymdhm(p("Anruf 9 Uhr").due), [2026, 11, 21, 9, 0], "vorbei → morgen")
        XCTAssertEqual(ymdhm(p("Anruf um 16").due), [2026, 11, 20, 16, 0])
        XCTAssertEqual(ymdhm(p("Anruf Montag 8.30 Uhr").due), [2026, 11, 23, 8, 30])
        XCTAssertEqual(p("Anruf um 16").title, "Anruf")
    }

    func testRecurrence() {
        XCTAssertEqual(p("Blumen gießen täglich").recurrence, Recurrence.daily)
        XCTAssertEqual(p("Blumen gießen täglich").due, .day(2026, 11, 20), "Wiederholung ohne Datum: ab heute")
        XCTAssertEqual(p("Müll jeden Dienstag").recurrence, "FREQ=WEEKLY;BYDAY=TU")
        XCTAssertEqual(p("Müll jeden Dienstag").due, .day(2026, 11, 24))
        XCTAssertEqual(p("Müll jeden Dienstag").title, "Müll")
        XCTAssertEqual(p("Bettwäsche alle 2 Wochen").recurrence, "FREQ=WEEKLY;INTERVAL=2")
        XCTAssertEqual(p("Steuer jedes Jahr").recurrence, "FREQ=YEARLY")
    }

    func testPlainTextStaysUntouched() {
        let q = p("Kapitel 3 lesen, am besten bald")
        XCTAssertEqual(q.title, "Kapitel 3 lesen, am besten bald")
        XCTAssertNil(q.due); XCTAssertFalse(q.important); XCTAssertNil(q.recurrence)
        XCTAssertEqual(p("heute").title, "heute", "nur Schlüsselwort: als Titel behalten")
    }

    func testRuleRoundTrip() {
        let r = Recurrence.Rule("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO")!
        XCTAssertEqual(r.interval, 2)
        XCTAssertEqual(r.byDay, ["MO", "WE"])
        XCTAssertEqual(r.rrule, "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE")
        L10n.language = .de
        defer { L10n.language = .en }
        XCTAssertEqual(r.text, "Alle 2 Wochen am Mo, Mi")
        XCTAssertEqual(Recurrence.Rule("FREQ=MONTHLY;UNTIL=20271231")?.rrule, "FREQ=MONTHLY;UNTIL=20271231")
        XCTAssertNil(Recurrence.Rule("FREQ=MONTHLY;BYMONTHDAY=1"), "nicht bearbeitbar, wird nur durchgereicht")
        XCTAssertEqual(Recurrence.Rule(Recurrence.daily)?.text, "Täglich")
        L10n.language = .en
        XCTAssertEqual(r.text, "Every 2 weeks on Mon, Wed")
        XCTAssertEqual(Recurrence.Rule(Recurrence.daily)?.text, "Daily")
    }

    // MARK: Englische Schnelleingabe

    func testEnglishTomorrowWithTime() {
        let q = p("Call parents tomorrow 8am !")
        XCTAssertEqual(q.title, "Call parents")
        XCTAssertTrue(q.important)
        XCTAssertEqual(ymdhm(q.due), [2026, 11, 21, 8, 0])
    }

    func testEnglishAtAndPM() {
        XCTAssertEqual(ymdhm(p("Meeting today at 3:30pm").due), [2026, 11, 20, 15, 30])
        XCTAssertEqual(ymdhm(p("Meeting tomorrow 8 pm").due), [2026, 11, 21, 20, 0])
        XCTAssertEqual(p("Meeting tomorrow 8 pm").title, "Meeting")
        XCTAssertEqual(ymdhm(p("Lunch 12pm tomorrow").due), [2026, 11, 21, 12, 0])
        XCTAssertEqual(ymdhm(p("Night 12am tomorrow").due), [2026, 11, 21, 0, 0])
    }

    func testEnglishWeekdayAndRecurrence() {
        let q = p("Team call next Monday")
        XCTAssertEqual(q.title, "Team call")
        XCTAssertEqual(q.due, .day(2026, 11, 23))
        let e = p("Water plants every 2 weeks")
        XCTAssertEqual(e.recurrence, "FREQ=WEEKLY;INTERVAL=2")
        XCTAssertEqual(e.title, "Water plants")
        XCTAssertEqual(p("Standup every Tuesday").recurrence, "FREQ=WEEKLY;BYDAY=TU")
        XCTAssertEqual(p("Report weekdays").recurrence, Recurrence.weekdays)
        XCTAssertEqual(p("Dentist in 3 days").due, .day(2026, 11, 23))
    }

    func testGermanAmStaysGerman() {
        let q = p("Treffen 8 am Montag")
        XCTAssertEqual(q.due, .day(2026, 11, 23))
        XCTAssertEqual(p("Teamsitzung program").title, "Teamsitzung program")
    }
}

final class LocalizationTests: XCTestCase {
    override func tearDown() { L10n.language = .en }

    func testDefaultIsEnglish() {
        XCTAssertEqual(AppLanguage.allCases.first, .en)
        L10n.language = .en
        XCTAssertEqual(L("Mein Tag"), "My Day")
        XCTAssertEqual(L("{0} von {1}", 2, 5), "2 of 5")
    }

    func testGermanAndFallback() {
        L10n.language = .de
        XCTAssertEqual(L("Mein Tag"), "Mein Tag")
        XCTAssertEqual(L("{0} von {1}", 2, 5), "2 von 5")
        L10n.language = .en
        XCTAssertEqual(L("Kein Schlüssel vorhanden"), "Kein Schlüssel vorhanden", "fehlende Übersetzung: deutscher Text")
    }

    func testEveryEntryKeepsPlaceholders() {
        for (de, en) in L10nTable.en {
            for i in 0..<4 where de.contains("{\(i)}") {
                XCTAssertTrue(en.contains("{\(i)}"), "Platzhalter {\(i)} fehlt: \(de) → \(en)")
            }
        }
    }
}

final class PlanwerkPlannedTests: XCTestCase {
    private let json = """
    {"rev":7,"projects":[{"id":1,"name":"12 Chemie","color":"#1e66f5","archived":0},
      {"id":2,"name":"Alt","archived":1},
      {"id":3,"name":"9a","nc_calendar":"https://c.example.org/remote.php/dav/calendars/m/9a/"}],
     "groups":[{"id":4,"project_id":1,"name":"Kurs A"}],"members":[{"id":1,"name":"Marvin"}],
     "tasks":[{"id":10,"project_id":1,"title":"Klausur","kind":"milestone","end_date":"2026-12-03","done":0,"group_id":4,"assignee_id":1},
              {"id":11,"project_id":1,"title":"Erledigt","end_date":"2026-12-03","done":1},
              {"id":12,"project_id":2,"title":"Archiviert","end_date":"2026-12-03","done":0},
              {"id":13,"project_id":3,"title":"Über Nextcloud","end_date":"2026-12-03","done":0},
              {"id":14,"project_id":1,"title":"Ohne Datum","done":0}]}
    """

    func testPlannedRecordsSkipDoneArchivedUndatedAndSyncedLists() throws {
        let state = try JSONDecoder().decode(PlanwerkState.self, from: Data(json.utf8))
        let recs = state.plannedRecords(skipLists: ["/remote.php/dav/calendars/m/9a/"])
        XCTAssertEqual(recs.map(\.item.title), ["Klausur"])
        let r = recs[0]
        XCTAssertEqual(r.item.due, .day(2026, 12, 3))
        XCTAssertEqual(r.item.tags, ["12 Chemie"])
        XCTAssertEqual(r.item.planwerk, PlanwerkMeta(kind: .milestone, group: "Kurs A", assignee: "Marvin"))
        XCTAssertEqual(PlanwerkTask.taskID(r), 10)
        XCTAssertEqual(state.plannedRecords().count, 2, "ohne lokale Liste kommt das Nextcloud-Projekt mit")
    }

    func testStateWithoutTasksStillDecodes() throws {
        let s = try JSONDecoder().decode(PlanwerkState.self, from: Data(#"{"rev":1,"projects":[],"groups":[],"members":[]}"#.utf8))
        XCTAssertTrue(s.plannedRecords().isEmpty)
    }

    func testSetDoneSendsPut() async throws {
        let t = MockTransport { _ in (200, [:], "{}") }
        try await PlanwerkClient(baseURL: URL(string: "https://p.example.org")!, token: "pwk_x", transport: t).setDone(taskID: 10, true)
        XCTAssertEqual(t.last.httpMethod, "PUT")
        XCTAssertEqual(t.last.url?.absoluteString, "https://p.example.org/api/tasks/10")
        XCTAssertEqual(t.body(), #"{"done":1}"#)
    }

    func testUploadAttachment() async throws {
        let t = MockTransport { req in
            switch req.httpMethod {
            case "MKCOL": return (405, [:], "")
            case "PUT": return (201, [:], "")
            default: return (207, [:], """
                <?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
                <d:href>/x</d:href><d:propstat><d:prop><oc:fileid>4711</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                </d:response></d:multistatus>
                """)
            }
        }
        let c = CalDAVClient(serverURL: URL(string: "https://cloud.example.org")!, loginName: "m@x.de", appPassword: "pw", transport: t)
        let a = try await c.uploadAttachment(Data("hallo".utf8), filename: "Brief Nr 1.pdf", mimeType: "application/pdf", userID: "Marvin")
        XCTAssertEqual(a.uri, "https://cloud.example.org/index.php/f/4711")
        XCTAssertEqual(a.filename, "Brief Nr 1.pdf")
        XCTAssertEqual(t.requests.map(\.httpMethod), ["MKCOL", "PUT", "PROPFIND"])
        let put = t.requests[1].url!.absoluteString
        XCTAssertTrue(put.hasPrefix("https://cloud.example.org/remote.php/dav/files/Marvin/Listwerk/"), put)
        XCTAssertTrue(put.hasSuffix("-Brief%20Nr%201.pdf"), put)
        XCTAssertEqual(t.requests[1].httpBody, Data("hallo".utf8))
    }
}
