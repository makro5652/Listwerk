import XCTest
@testable import ListwerkCore

/// Modus ohne Konto (lokale Listen, später übernommen) und Wiederholungsregeln.
final class LocalModeRecurrenceTests: XCTestCase {
    func testAdoptLocalListsMovesListsAndTasksIntoAccount() throws {
        let store = try Store.inMemory()
        let home = "/remote.php/dav/calendars/marv/"
        let local = try store.createList(name: "Einkauf", color: "#1e66f5", homePath: Store.localHome)
        XCTAssertEqual(local.href, "local:/einkauf/")
        _ = try store.save(todo("milch"), inList: local.href)
        _ = try store.save(todo("brot") { $0.parentUID = "milch" }, inList: local.href)
        // Gleichnamige Liste existiert schon im Konto: neue Href bekommt eine Nummer.
        try store.applyRemoteLists([RemoteList(href: home + "einkauf/", name: "Einkauf (Server)")])

        let moved = try store.adoptLocalLists(homePath: home)

        let target = home + "einkauf-2/"
        XCTAssertEqual(moved, [local.href: target])
        XCTAssertEqual(Set(try store.lists().map(\.href)), [home + "einkauf/", target])
        XCTAssertEqual(try store.tasks(inList: target).map(\.item.uid).sorted(), ["brot", "milch"])
        XCTAssertTrue(try store.tasks(inList: target).allSatisfy { $0.isDirty && $0.href == nil })
        XCTAssertEqual(try store.adoptLocalLists(homePath: home), [:], "zweiter Aufruf ändert nichts")
    }

    func testLocalListsAreUploadedAfterLogin() async throws {
        let env = try await Env.make()
        let local = try env.store.createList(name: "Offline", color: nil, homePath: Store.localHome)
        _ = try env.store.save(todo("Ohne Konto"), inList: local.href)
        let r = try await env.sync()
        XCTAssertTrue(r.isClean, "\(r.errors)")
        let href = env.server.home + "offline/"
        XCTAssertNotNil(env.server.collections[href])
        XCTAssertEqual(env.server.item(href, uid: "ohne-konto")?.title, "Ohne Konto")
        XCTAssertTrue(try env.store.tasks(inList: href).allSatisfy { !$0.isDirty })
        XCTAssertTrue(try env.store.lists().allSatisfy { !$0.href.hasPrefix(Store.localHome) })
    }

    func testListOrderIsUploadedAndSurvivesSync() async throws {
        let env = try await Env.make()
        let second = env.server.addCollection("Arbeit", color: nil)
        try await env.sync()
        let before = try env.store.lists().map(\.href)
        XCTAssertEqual(before.count, 2)

        try env.store.reorderLists(before.reversed())
        let r = try await env.sync()
        XCTAssertTrue(r.isClean, "\(r.errors)")
        XCTAssertEqual(try env.store.lists().map(\.href), before.reversed(), "Reihenfolge bleibt nach dem Abgleich")
        XCTAssertNotNil(env.server.collections[second]?.order, "calendar-order wurde hochgeladen")
        try await env.sync()
        XCTAssertEqual(try env.store.lists().map(\.href), before.reversed())
    }

    func testListOrderKeptWhenServerSendsNone() throws {
        let store = try Store.inMemory()
        let a = "/cal/a/", b = "/cal/b/"
        try store.applyRemoteLists([RemoteList(href: a, name: "A"), RemoteList(href: b, name: "B")])
        try store.reorderLists([b, a])
        try store.clearListFlags(b); try store.clearListFlags(a)  // wie nach dem Hochladen
        try store.applyRemoteLists([RemoteList(href: a, name: "A"), RemoteList(href: b, name: "B")])
        XCTAssertEqual(try store.lists().map(\.href), [b, a])
    }

    func testRecurrence() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Berlin")!
        func day(_ y: Int, _ m: Int, _ d: Int) -> Date { cal.date(from: DateComponents(year: y, month: m, day: d))! }
        let fri = day(2026, 11, 20)
        XCTAssertEqual(Recurrence.next(Recurrence.daily, from: fri, calendar: cal), day(2026, 11, 21))
        XCTAssertEqual(Recurrence.next(Recurrence.weekdays, from: fri, calendar: cal), day(2026, 11, 23), "Freitag → Montag")
        XCTAssertEqual(Recurrence.next(Recurrence.weekdays, from: day(2026, 11, 19), calendar: cal), fri)
        XCTAssertEqual(Recurrence.next("FREQ=WEEKLY;INTERVAL=2", from: fri, calendar: cal), day(2026, 12, 4))
        XCTAssertEqual(Recurrence.next(Recurrence.monthly, from: day(2027, 1, 31), calendar: cal), day(2027, 2, 28))
        XCTAssertNil(Recurrence.next("FREQ=MONTHLY;BYMONTHDAY=-1", from: fri, calendar: cal))
        XCTAssertNil(Recurrence.next("FREQ=DAILY;UNTIL=20261120T000000Z", from: fri, calendar: cal))
    }
}
