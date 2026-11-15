import XCTest
@testable import ListwerkCore

final class StoreTests: XCTestCase {
    private func makeStore() throws -> (Store, String) {
        let store = try Store.inMemory()
        let list = "/remote.php/dav/calendars/marv/schule/"
        try store.applyRemoteLists([RemoteList(href: list, name: "Schule")])
        return (store, list)
    }

    private func order(_ store: Store, _ list: String) throws -> [String] {
        try store.tasks(inList: list).filter { $0.item.parentUID == nil }.map(\.item.title)
    }

    func testSlug() {
        XCTAssertEqual(Store.slug("Projekt Größe"), "projekt-grosse")
        XCTAssertEqual(Store.slug("Über Äpfel & Birnen"), "uber-apfel-birnen")
        XCTAssertEqual(Store.slug("  ???  "), "liste")
    }

    func testSaveReadAndUnchangedSaveStaysClean() throws {
        let (store, list) = try makeStore()
        var item = todo("Eins") { $0.notes = "n"; $0.due = .day(2026, 12, 1); $0.color = "#8839ef" }
        let rec = try store.save(item, inList: list, now: testNow)
        XCTAssertTrue(rec.isDirty)
        try store.markSynced(uid: "eins", listHref: list, href: list + "eins.ics", etag: "\"1\"", ics: rec.ics)
        item = try XCTUnwrap(try store.record(uid: "eins", inList: list)).item
        let again = try store.save(item, inList: list, now: testNow.addingTimeInterval(60))
        XCTAssertFalse(again.isDirty, "unveränderter Eintrag bleibt sauber")
        XCTAssertEqual(again.item.notes, "n")
        XCTAssertEqual(again.item.color, "#8839ef")
    }

    func testReorderBetweenNeighboursAndRenumbering() throws {
        let (store, list) = try makeStore()
        for t in ["a", "b", "c"] { try store.save(todo(t), inList: list, now: testNow) }
        XCTAssertEqual(try order(store, list), ["a", "b", "c"])

        try store.reorder(uid: "c", inList: list, after: nil, now: testNow)
        XCTAssertEqual(try order(store, list), ["c", "a", "b"])
        XCTAssertEqual(try store.tasks(inList: list).map { $0.item.sortOrder }, [1024, 2048, 3072])

        try store.reorder(uid: "a", inList: list, after: "b", now: testNow)
        XCTAssertEqual(try order(store, list), ["c", "b", "a"])

        // Mittlerer Wert, ohne die Nachbarn anzufassen
        for r in try store.tasks(inList: list) { try store.markSynced(uid: r.item.uid, listHref: list, href: list + r.item.uid + ".ics", etag: "\"x\"", ics: r.ics) }
        try store.reorder(uid: "a", inList: list, after: "c", now: testNow)
        XCTAssertEqual(try order(store, list), ["c", "a", "b"])
        XCTAssertEqual(try store.dirtyRecords(inList: list).map(\.item.uid), ["a"], "nur der verschobene Eintrag wird geändert")
    }

    func testReorderWithoutGapRenumbers() throws {
        let (store, list) = try makeStore()
        try store.save(todo("a") { $0.sortOrder = 1 }, inList: list, now: testNow)
        try store.save(todo("b") { $0.sortOrder = 2 }, inList: list, now: testNow)
        try store.save(todo("x") { $0.sortOrder = 10 }, inList: list, now: testNow)
        try store.reorder(uid: "x", inList: list, after: "a", now: testNow)
        XCTAssertEqual(try order(store, list), ["a", "x", "b"])
        XCTAssertEqual(try store.tasks(inList: list).map { $0.item.sortOrder }, [1024, 2048, 3072])
    }

    func testDeleteCascadesAndHardDeletesUnsynced() throws {
        let (store, list) = try makeStore()
        try store.save(todo("p"), inList: list, now: testNow)
        try store.save(todo("k1") { $0.parentUID = "p" }, inList: list, now: testNow)
        try store.save(todo("k2") { $0.parentUID = "k1" }, inList: list, now: testNow)
        try store.delete(uid: "p", inList: list)
        XCTAssertEqual(try store.tasks(inList: list).count, 0)
        XCTAssertEqual(try store.db.query("SELECT COUNT(*) FROM task") { $0.int(0) }, [0], "nie hochgeladen: keine Spur")
    }

    func testDeleteMarksSyncedItemsAndUndeleteRestores() throws {
        let (store, list) = try makeStore()
        let rec = try store.save(todo("a"), inList: list, now: testNow)
        try store.markSynced(uid: "a", listHref: list, href: list + "a.ics", etag: "\"1\"", ics: rec.ics)
        try store.delete(uid: "a", inList: list)
        XCTAssertEqual(try store.tasks(inList: list).count, 0)
        XCTAssertEqual(try store.dirtyRecords(inList: list).first?.isDeleted, true)
        try store.undelete(uid: "a", inList: list)
        XCTAssertEqual(try store.tasks(inList: list).map(\.item.title), ["a"])
    }

    func testMoveCarriesSubtasksAndTracksOriginalLocation() throws {
        let (store, a) = try makeStore()
        let b = "/remote.php/dav/calendars/marv/privat/"
        try store.applyRemoteLists([RemoteList(href: a, name: "Schule"), RemoteList(href: b, name: "Privat")])
        for (uid, parent) in [("p", nil), ("k", "p")] as [(String, String?)] {
            let r = try store.save(todo(uid) { $0.parentUID = parent }, inList: a, now: testNow)
            try store.markSynced(uid: uid, listHref: a, href: a + uid + ".ics", etag: "\"1\"", ics: r.ics)
        }
        try store.move(uid: "p", fromList: a, toList: b)
        XCTAssertEqual(try store.tasks(inList: a).count, 0)
        XCTAssertEqual(try store.tasks(inList: b).map(\.item.uid).sorted(), ["k", "p"])
        XCTAssertEqual(try store.pendingMoves().count, 2)
        XCTAssertTrue(try store.tasks(inList: b).allSatisfy { !$0.isDirty })

        try store.move(uid: "p", fromList: b, toList: a)  // zurück: Verschiebung hebt sich auf
        XCTAssertEqual(try store.pendingMoves().count, 0)
        XCTAssertEqual(try store.tasks(inList: a).count, 2)
    }

    func testPersistenceAcrossReopenAndIdempotentMigration() throws {
        let path = NSTemporaryDirectory() + "listwerk-test-\(UUID().uuidString).sqlite"
        defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + s) } }
        do {
            let s = try Store(path: path)
            try s.applyRemoteLists([RemoteList(href: "/l/", name: "L")])
            try s.save(todo("bleibt"), inList: "/l/", now: testNow)
            try s.saveAccount(Account(serverURL: URL(string: "https://x.example")!, loginName: "marv"))
        }
        let s2 = try Store(path: path)
        XCTAssertEqual(try s2.tasks(inList: "/l/").map(\.item.title), ["bleibt"])
        XCTAssertEqual(try s2.account()?.loginName, "marv")
    }

    func testWipeRemovesEverything() throws {
        let (store, list) = try makeStore()
        try store.save(todo("a"), inList: list, now: testNow)
        try store.saveAccount(Account(serverURL: URL(string: "https://x.example")!, loginName: "marv"))
        try store.wipe()
        XCTAssertEqual(try store.lists().count, 0)
        XCTAssertNil(try store.account())
    }

    func testAllTasksOrderedByDueDate() throws {
        let (store, list) = try makeStore()
        try store.save(todo("spät") { $0.due = .day(2026, 12, 24) }, inList: list, now: testNow)
        try store.save(todo("ohne"), inList: list, now: testNow)
        try store.save(todo("früh") { $0.due = .day(2026, 11, 12) }, inList: list, now: testNow)
        XCTAssertEqual(try store.allTasks().map(\.item.title), ["früh", "spät", "ohne"])
    }

    /// Richtwerte aus Abschnitt 9: 5.000 Einträge, Datenbank unter 10 MB.
    func testFiveThousandItemsSizeAndLoadTime() throws {
        let path = NSTemporaryDirectory() + "listwerk-perf-\(UUID().uuidString).sqlite"
        defer { for s in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + s) } }
        let store = try Store(path: path)
        try store.applyRemoteLists((0..<10).map { RemoteList(href: "/l\($0)/", name: "L\($0)") })
        try store.db.transaction {
            for i in 0..<5000 {
                let t = todo("Eintrag \(i)") {
                    $0.notes = "Notiz zu Eintrag \(i) mit etwas Text, damit die Größe realistisch wird."
                    $0.due = .day(2026, 11, 1 + i % 28)
                    $0.priority = i % 3 == 0 ? 1 : 0
                    $0.reminders = [Reminder(trigger: .relative(-3600, toEnd: true))]
                }
                try store.save(t, inList: "/l\(i % 10)/", now: testNow)
            }
        }
        let start = Date()
        let one = try store.tasks(inList: "/l3/")
        let oneTime = Date().timeIntervalSince(start)
        let allStart = Date()
        let all = try store.allTasks()
        let allTime = Date().timeIntervalSince(allStart)
        _ = try store.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        let size = (try FileManager.default.attributesOfItem(atPath: path)[.size] as? Int) ?? 0
        print("MESSUNG 5000 Einträge: Liste (500) \(Int(oneTime * 1000)) ms, alle \(Int(allTime * 1000)) ms, Datenbank \(size / 1024) KB")
        XCTAssertEqual(one.count, 500)
        XCTAssertEqual(all.count, 5000)
        XCTAssertLessThan(size, 10 * 1024 * 1024)
        XCTAssertLessThan(allTime, 5.0)
    }
}
