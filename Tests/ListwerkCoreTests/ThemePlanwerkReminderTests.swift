import XCTest
@testable import ListwerkCore

final class ThemeTests: XCTestCase {
    func testAllThemesLoad() {
        XCTAssertEqual(ThemeCatalog.all.map(\.id),
                       ["papier", "graphit", "catppuccin-latte", "catppuccin-frappe", "catppuccin-macchiato", "catppuccin-mocha", "aqua"])
        XCTAssertEqual(ThemeCatalog.all.filter(\.isDark).count, 4)
    }

    func testCatppuccinValuesMatchOfficialPalette() throws {
        let latte = try XCTUnwrap(ThemeCatalog.theme(id: "catppuccin-latte"))
        XCTAssertEqual(latte.colors.background, "#eff1f5")
        XCTAssertEqual(latte.colors.textPrimary, "#4c4f69")
        let mocha = try XCTUnwrap(ThemeCatalog.theme(id: "catppuccin-mocha"))
        XCTAssertEqual(mocha.colors.background, "#1e1e2e")
        XCTAssertEqual(mocha.colors.accent, "#89b4fa")
    }

    func testTextContrastMeetsWCAGAA() {
        for t in ThemeCatalog.all {
            let c = t.colors
            for bg in [c.background, c.sidebar] {
                XCTAssertGreaterThanOrEqual(Contrast.ratio(c.textPrimary, bg), 4.5, "\(t.id) Haupttext auf \(bg)")
                XCTAssertGreaterThanOrEqual(Contrast.ratio(c.textSecondary, bg), 4.5, "\(t.id) Nebentext auf \(bg)")
            }
            XCTAssertGreaterThanOrEqual(Contrast.ratio(c.textPrimary, c.surface), 4.5, "\(t.id) Haupttext auf Fläche")
            XCTAssertGreaterThanOrEqual(Contrast.ratio(c.textSecondary, c.surface), 4.5, "\(t.id) Nebentext auf Fläche")
            XCTAssertGreaterThanOrEqual(Contrast.ratio(c.accent, c.background), 3.0, "\(t.id) Akzent (Bedienelement)")
        }
    }

    func testResolveSystemTheme() {
        XCTAssertEqual(ThemeCatalog.resolve(id: "system", systemIsDark: false).id, "papier")
        XCTAssertEqual(ThemeCatalog.resolve(id: "system", systemIsDark: true).id, "graphit")
        XCTAssertEqual(ThemeCatalog.resolve(id: "catppuccin-mocha", systemIsDark: false).id, "catppuccin-mocha")
        XCTAssertEqual(ThemeCatalog.resolve(id: "gibt-es-nicht", systemIsDark: true).id, "graphit")
    }

    func testPalettes() {
        let p = ThemeCatalog.palettes
        XCTAssertEqual(p.map(\.id), ["catppuccin", "farbkarten"])
        XCTAssertEqual(p[0].colors.count, 14)
        XCTAssertEqual(p[1].colors.count, 20)
        XCTAssertEqual(p[0].colors.first { $0.id == "mauve" }?.storedHex, "#8839ef")
        XCTAssertFalse(p[1].colors.contains { $0.name.lowercased().contains("pantone") }, "keine Markennamen")
        XCTAssertEqual(Set(p[1].colors.map(\.storedHex)).count, 20)
    }

    func testDisplayColorFollowsTheme() throws {
        let mocha = try XCTUnwrap(ThemeCatalog.theme(id: "catppuccin-mocha"))
        let frappe = try XCTUnwrap(ThemeCatalog.theme(id: "catppuccin-frappe"))
        let papier = try XCTUnwrap(ThemeCatalog.theme(id: "papier"))
        let graphit = try XCTUnwrap(ThemeCatalog.theme(id: "graphit"))
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#8839ef", in: mocha), "#cba6f7")
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#8839EF", in: frappe), "#ca9ee6")
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#8839ef", in: papier), "#8839ef")
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#8839ef", in: graphit), "#cba6f7")
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#e03c31", in: papier), "#e03c31")
        XCTAssertNotEqual(ThemeCatalog.displayHex(stored: "#e03c31", in: graphit), "#e03c31")
        XCTAssertEqual(ThemeCatalog.displayHex(stored: "#123456", in: mocha), "#123456", "Fremdfarben bleiben")
    }
}

final class PlanwerkTests: XCTestCase {
    // Aufbau nach src/db.rs (Tabellen projects, task_groups, members) und GET /api/state.
    static let stateJSON = """
    {"rev":42,"teams":[],"members":[{"id":1,"name":"Mia Muster","email":"","color":"#40a02b"},{"id":2,"name":"Jona","email":"","color":"#40a02b"}],
     "projects":[
      {"id":3,"name":"9a Chemie","description":"","color":"#2bb07f","start_date":"2026-08-24","end_date":"2027-06-25","team_id":1,"archived":0,"sort":0,
       "nc_calendar":"https://cloud.example.org/remote.php/dav/calendars/marv/klasse-9a","nc_calendar_name":"Klasse 9a","lessons":"Di D"},
      {"id":4,"name":"Ohne Liste","color":"#1e66f5","archived":0,"nc_calendar":null,"nc_calendar_name":null},
      {"id":5,"name":"Altlast","color":"#1e66f5","archived":1,"nc_calendar":"https://cloud.example.org/remote.php/dav/calendars/marv/alt/"}],
     "groups":[{"id":1,"project_id":3,"name":"Experimente","sort":0,"collapsed":0},{"id":2,"project_id":3,"name":"Organisation","sort":1,"collapsed":0},{"id":3,"project_id":4,"name":"Andere","sort":0,"collapsed":0}],
     "tasks":[],"blocks":[],"nextcloud":{"url":null}}
    """

    final class Counting: PlanwerkConnector, @unchecked Sendable {
        var rev = 1
        var stateCalls = 0
        func fetchState() async throws -> PlanwerkState {
            stateCalls += 1
            return try JSONDecoder().decode(PlanwerkState.self, from: Data(PlanwerkTests.stateJSON.utf8))
        }
        func currentRevision() async throws -> Int { rev }
        func triggerNextcloudSync() async throws {}
    }

    func testDecodeStateAndLinkedLists() async throws {
        let links = try await Counting().linkedLists()
        XCTAssertEqual(links.count, 1, "archivierte und unverknüpfte Projekte entfallen")
        let l = links[0]
        XCTAssertEqual(l.projectName, "9a Chemie")
        XCTAssertEqual(l.listPath, "/remote.php/dav/calendars/marv/klasse-9a/")
        XCTAssertEqual(l.groups, ["Experimente", "Organisation"])
        XCTAssertEqual(l.people, ["Jona", "Mia Muster"])
    }

    func testClientSendsBearerToken() async throws {
        let t = MockTransport { req in
            req.url!.path.hasSuffix("/api/rev") ? (200, [:], #"{"rev":7}"#) : (200, [:], PlanwerkTests.stateJSON)
        }
        let c = PlanwerkClient(baseURL: URL(string: "https://planwerk.example.org")!, token: "pwk_abc", transport: t)
        let rev = try await c.currentRevision()
        XCTAssertEqual(rev, 7)
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Authorization"), "Bearer pwk_abc")
        let s = try await c.fetchState()
        XCTAssertEqual(s.projects.count, 3)
        try await c.triggerNextcloudSync()
        XCTAssertEqual(t.last.httpMethod, "POST")
        XCTAssertEqual(t.last.url?.path, "/api/nextcloud/sync")
    }

    func testUnauthorizedIsReported() async throws {
        let c = PlanwerkClient(baseURL: URL(string: "https://p.example.org")!, token: "x", transport: MockTransport { _ in (401, [:], "") })
        do { _ = try await c.fetchState(); XCTFail() } catch { XCTAssertEqual(error as? DAVError, .unauthorized) }
    }

    func testCacheReloadsOnlyWhenRevisionChanges() async throws {
        let conn = Counting()
        let cache = PlanwerkLinkCache(connector: conn, minInterval: 60)
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        _ = try await cache.current(now: t0)
        _ = try await cache.current(now: t0.addingTimeInterval(10))   // innerhalb des Intervalls
        _ = try await cache.current(now: t0.addingTimeInterval(120))  // gleiche Revision
        XCTAssertEqual(conn.stateCalls, 1)
        conn.rev = 2
        _ = try await cache.current(now: t0.addingTimeInterval(300))
        XCTAssertEqual(conn.stateCalls, 2)
    }

    func testMarksMatchingListsAsLinked() async throws {
        let store = try Store.inMemory()
        try store.applyRemoteLists([
            RemoteList(href: "/remote.php/dav/calendars/marv/klasse-9a/", name: "Klasse 9a"),
            RemoteList(href: "/remote.php/dav/calendars/marv/privat/", name: "Privat"),
        ])
        let cache = PlanwerkLinkCache(connector: Counting())
        try await cache.apply(to: store)
        XCTAssertEqual(try store.lists().map { $0.isPlanwerkLinked }, [true, false])
    }
}

final class ReminderPlannerTests: XCTestCase {
    private let nairobi = TimeZone(identifier: "Africa/Nairobi")!
    private func rec(_ item: TodoItem, deleted: Bool = false) -> TaskRecord {
        TaskRecord(item: item, listHref: "/l/", href: nil, etag: nil, isDirty: false, isDeleted: deleted, ics: "", baseICS: nil)
    }

    func testRelativeToAllDayDueUsesNineAM() throws {
        var t = todo("Abgabe") { $0.due = .day(2026, 11, 20) }
        t.reminders = [Reminder(trigger: .relative(-86_400, toEnd: true))]
        let plan = ReminderPlanner.plan(records: [rec(t)], now: Date(timeIntervalSince1970: 1_790_000_000), timeZone: nairobi)
        XCTAssertEqual(plan.count, 1)
        var cal = Calendar(identifier: .gregorian); cal.timeZone = nairobi
        let c = cal.dateComponents([.year, .month, .day, .hour], from: plan[0].fireDate)
        XCTAssertEqual([c.year, c.month, c.day, c.hour], [2026, 11, 19, 9])
        XCTAssertEqual(plan[0].id, "/l/|abgabe|0")
    }

    func testExcludesPastCompletedDeletedAndEmail() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let future = Reminder(trigger: .absolute(now.addingTimeInterval(3600)))
        let past = Reminder(trigger: .absolute(now.addingTimeInterval(-3600)))
        let email = Reminder(trigger: .absolute(now.addingTimeInterval(3600)), action: "EMAIL")
        let ok = todo("ok") { $0.reminders = [future, past, email] }
        let done = todo("fertig") { $0.isCompleted = true; $0.reminders = [future] }
        let gone = todo("weg") { $0.reminders = [future] }
        let plan = ReminderPlanner.plan(records: [rec(ok), rec(done), rec(gone, deleted: true)], now: now, timeZone: nairobi)
        XCTAssertEqual(plan.map(\.uid), ["ok"])
        XCTAssertEqual(plan.count, 1)
    }

    func testLimitKeepsNearestInOrder() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let items = (0..<100).map { i in
            rec(todo("t\(i)") { $0.reminders = [Reminder(trigger: .absolute(now.addingTimeInterval(Double(100 - i) * 60)))] })
        }
        let plan = ReminderPlanner.plan(records: items, now: now, timeZone: nairobi)
        XCTAssertEqual(plan.count, 64)
        XCTAssertEqual(plan.first?.uid, "t99")
        XCTAssertTrue(zip(plan, plan.dropFirst()).allSatisfy { $0.fireDate <= $1.fireDate })
    }

    func testRelativeToStartFallsBackToDue() {
        let t = todo("x") { $0.due = ICalDate(year: 2026, month: 11, day: 20, hour: 12, kind: .utc); $0.reminders = [Reminder(trigger: .relative(-900, toEnd: false))] }
        let d = ReminderPlanner.fireDate(for: t.reminders[0], item: t, timeZone: nairobi)
        XCTAssertEqual(d, ICalDate(year: 2026, month: 11, day: 20, hour: 11, minute: 45, kind: .utc).date())
    }
}

final class MSTodoTests: XCTestCase {
    func testImportIsIdempotentAndMapsFields() async throws {
        let t = MockTransport { req in
            let u = req.url!.absoluteString
            if u.hasSuffix("/me/todo/lists") { return (200, [:], #"{"value":[{"id":"L1=","displayName":"Schule"}]}"#) }
            return (200, [:], #"""
            {"value":[
              {"id":"T1=","title":"Arbeiten korrigieren","status":"notStarted","importance":"high","body":{"content":"Notiz"},
               "dueDateTime":{"dateTime":"2026-11-20T00:00:00.0000000","timeZone":"UTC"},
               "isReminderOn":true,"reminderDateTime":{"dateTime":"2026-11-19T07:00:00.0000000","timeZone":"UTC"}},
              {"id":"T2=","title":"Fertig","status":"completed","completedDateTime":{"dateTime":"2026-11-01T10:00:00.0000000","timeZone":"UTC"}}]}
            """#)
        }
        let all = try await MSTodoClient(clientID: "x", transport: t).fetchAll(accessToken: "tok")
        XCTAssertEqual(t.last.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        let store = try Store.inMemory()
        let r = try store.importMSTodo(all, homePath: "/dav/home/")
        XCTAssertEqual(r.lists, 1); XCTAssertEqual(r.tasks, 2)
        _ = try store.importMSTodo(all, homePath: "/dav/home/")
        let href = try store.lists()[0].href
        let recs = try store.tasks(inList: href)
        XCTAssertEqual(recs.count, 2, "zweiter Import verdoppelt nichts")
        let a = try XCTUnwrap(recs.first { $0.item.title == "Arbeiten korrigieren" }?.item)
        XCTAssertEqual(a.priority, 1); XCTAssertEqual(a.due, ICalDate.day(2026, 11, 20))
        XCTAssertEqual(a.notes, "Notiz"); XCTAssertEqual(a.reminders.count, 1)
        XCTAssertTrue(try XCTUnwrap(recs.first { $0.item.title == "Fertig" }).item.isCompleted)
    }

    func testPendingDeviceLoginReturnsNil() async throws {
        let t = MockTransport { _ in (400, [:], #"{"error":"authorization_pending","error_description":"x","error_codes":[70016],"trace_id":"t"}"#) }
        let d = MSTodoClient.DeviceCode(userCode: "A", verificationURL: URL(string: "https://x")!, deviceCode: "d", interval: 5, expiresIn: 900)
        let tokens = try await MSTodoClient(clientID: "x", transport: t).pollDeviceLogin(d)
        XCTAssertNil(tokens)
    }
}
