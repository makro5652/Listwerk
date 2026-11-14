import XCTest
@testable import ListwerkCore

final class SyncEngineTests: XCTestCase {
    private let later = testNow.addingTimeInterval(100)

    private func record(_ env: Env, _ uid: String, list: String? = nil) throws -> TaskRecord {
        try XCTUnwrap(try env.store.record(uid: uid, inList: list ?? env.list), "Eintrag \(uid) fehlt lokal")
    }

    // MARK: Herunterladen

    func testInitialPullAndIdleSecondSync() async throws {
        let env = try await Env.make(items: [todo("Eins"), todo("Zwei") { $0.priority = 1 }])
        let r1 = try await env.sync()
        XCTAssertTrue(r1.isClean, "\(r1.errors)")
        XCTAssertEqual(r1.pulled, 2)
        XCTAssertEqual(try env.store.lists().map(\.name), ["Arbeit"])
        XCTAssertEqual(try env.store.tasks(inList: env.list).map(\.item.title).sorted(), ["Eins", "Zwei"])
        XCTAssertTrue(try env.store.tasks(inList: env.list).allSatisfy { !$0.isDirty && $0.etag != nil && $0.href != nil })

        env.server.resetCalls()
        let r2 = try await env.sync()
        XCTAssertEqual(r2.pulled, 0)
        XCTAssertEqual(env.server.count("fetch"), 0, "ohne Änderungen wird nichts geladen")
        XCTAssertEqual(env.server.count("put"), 0)
    }

    func testPullEditFromServer() async throws {
        let env = try await Env.make(items: [todo("Alt")])
        try await env.sync()
        env.server.clock = later
        env.server.edit(env.list, uid: "alt") { $0.title = "Neu"; $0.notes = "Notiz" }
        try await env.sync()
        let r = try record(env, "alt")
        XCTAssertEqual(r.item.title, "Neu")
        XCTAssertEqual(r.item.notes, "Notiz")
        XCTAssertFalse(r.isDirty)
    }

    func testServerDeleteRemovesLocal() async throws {
        let env = try await Env.make(items: [todo("Weg"), todo("Bleibt")])
        try await env.sync()
        env.server.remove(env.list, uid: "weg")
        let r = try await env.sync()
        XCTAssertEqual(r.deleted, 1)
        XCTAssertEqual(try env.store.tasks(inList: env.list).map(\.item.title), ["Bleibt"])
    }

    // MARK: Hochladen

    func testPushNewItem() async throws {
        let env = try await Env.make()
        try await env.sync()
        try env.store.save(todo("Neu") { $0.due = .day(2026, 12, 1) }, inList: env.list, now: later)
        XCTAssertTrue(try record(env, "neu").isDirty)
        let r = try await env.sync()
        XCTAssertEqual(r.pushed, 1)
        XCTAssertEqual(env.server.titles(env.list), ["Neu"])
        let rec = try record(env, "neu")
        XCTAssertFalse(rec.isDirty)
        XCTAssertNotNil(rec.etag)
        XCTAssertEqual(rec.baseICS, rec.ics)
        XCTAssertEqual(env.server.item(env.list, uid: "neu")?.due, .day(2026, 12, 1))
    }

    func testPushEditThenIdle() async throws {
        let env = try await Env.make(items: [todo("Alt")])
        try await env.sync()
        var item = try record(env, "alt").item
        item.title = "Geändert"
        try env.store.save(item, inList: env.list, now: later)
        try await env.sync()
        XCTAssertEqual(env.server.titles(env.list), ["Geändert"])
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("put"), 0)
        XCTAssertEqual(env.server.count("fetch"), 0, "eigene Änderungen werden nicht zurückgeladen")
    }

    func testServerWithoutETagOnPutRefetches() async throws {
        let env = try await Env.make()
        try await env.sync()
        env.server.omitETagOnPut = true
        try env.store.save(todo("Neu"), inList: env.list, now: later)
        try await env.sync()
        let serverETag = env.server.collections[env.list]!.items.values.first!.etag
        XCTAssertEqual(try record(env, "neu").etag, serverETag)
    }

    func testReadOnlyListIsNotWritten() async throws {
        let env = try await Env.make()
        let ro = env.server.addCollection("Lesen", canWrite: false)
        env.server.seed(ro, todo("Fremd"))
        try await env.sync()
        var item = try record(env, "fremd", list: ro).item
        item.title = "Manipuliert"
        try env.store.save(item, inList: ro, now: later)
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("put"), 0)
    }

    // MARK: Konflikte

    func testDifferentFieldsMergeWithoutConflict() async throws {
        let env = try await Env.make(items: [todo("Aufgabe")])
        try await env.sync()
        var local = try record(env, "aufgabe").item
        local.priority = 1
        try env.store.save(local, inList: env.list, now: later)
        env.server.clock = testNow.addingTimeInterval(50)
        env.server.edit(env.list, uid: "aufgabe") { $0.title = "Aufgabe neu" }

        let r = try await env.sync()
        XCTAssertTrue(r.isClean, "\(r.errors)")
        XCTAssertEqual(r.conflicts, 0)
        let serverItem = try XCTUnwrap(env.server.item(env.list, uid: "aufgabe"))
        XCTAssertEqual(serverItem.title, "Aufgabe neu")
        XCTAssertEqual(serverItem.priority, 1)
        let rec = try record(env, "aufgabe")
        XCTAssertEqual(rec.item.title, "Aufgabe neu")
        XCTAssertEqual(rec.item.priority, 1)
        XCTAssertFalse(rec.isDirty)
    }

    func testSameFieldNewerLocalWinsAndIsLogged() async throws {
        let env = try await Env.make(items: [todo("Original")])
        try await env.sync()
        var local = try record(env, "original").item
        local.title = "Lokal"
        try env.store.save(local, inList: env.list, now: later)               // +100 s
        env.server.clock = testNow.addingTimeInterval(50)
        env.server.edit(env.list, uid: "original") { $0.title = "Server" }    // +50 s

        let r = try await env.sync()
        XCTAssertEqual(r.conflicts, 1)
        XCTAssertEqual(env.server.item(env.list, uid: "original")?.title, "Lokal")
        let log = try env.store.conflicts(forUID: "original")
        XCTAssertEqual(log.count, 1)
        XCTAssertEqual(log[0].field, "title")
        XCTAssertEqual(log[0].remoteValue, "Server")
    }

    func testSameFieldNewerServerWins() async throws {
        let env = try await Env.make(items: [todo("Original")])
        try await env.sync()
        var local = try record(env, "original").item
        local.title = "Lokal"
        try env.store.save(local, inList: env.list, now: testNow.addingTimeInterval(50))
        env.server.clock = later
        env.server.edit(env.list, uid: "original") { $0.title = "Server" }

        try await env.sync()
        XCTAssertEqual(try record(env, "original").item.title, "Server")
        XCTAssertEqual(env.server.item(env.list, uid: "original")?.title, "Server")
        XCTAssertFalse(try record(env, "original").isDirty)
    }

    func testLocalEditAfterServerDeleteRecreates() async throws {
        let env = try await Env.make(items: [todo("Zurück")])
        try await env.sync()
        env.server.remove(env.list, uid: "zurück")
        var item = try record(env, "zurück").item
        item.notes = "lokal weitergearbeitet"
        try env.store.save(item, inList: env.list, now: later)
        try await env.sync()
        XCTAssertEqual(env.server.item(env.list, uid: "zurück")?.notes, "lokal weitergearbeitet")
        XCTAssertFalse(try record(env, "zurück").isDirty)
    }

    func testLocalDeleteAfterServerEditServerWins() async throws {
        let env = try await Env.make(items: [todo("Doch da")])
        try await env.sync()
        try env.store.delete(uid: "doch-da", inList: env.list)
        env.server.clock = later
        env.server.edit(env.list, uid: "doch-da") { $0.title = "Doch da (bearbeitet)" }
        try await env.sync()
        XCTAssertEqual(try record(env, "doch-da").item.title, "Doch da (bearbeitet)")
        XCTAssertEqual(env.server.titles(env.list), ["Doch da (bearbeitet)"])
    }

    // MARK: Löschen

    func testLocalDeleteDeletesOnServerIncludingSubtasks() async throws {
        let env = try await Env.make(items: [todo("Eltern"), todo("Kind") { $0.parentUID = "eltern" }, todo("Daneben")])
        try await env.sync()
        try env.store.delete(uid: "eltern", inList: env.list)
        XCTAssertEqual(try env.store.tasks(inList: env.list).map(\.item.title), ["Daneben"])
        let r = try await env.sync()
        XCTAssertEqual(r.deleted, 2)
        XCTAssertEqual(env.server.titles(env.list), ["Daneben"])
        XCTAssertNil(try env.store.record(uid: "eltern", inList: env.list))
    }

    func testDeletingNeverUploadedItemLeavesNoTrace() async throws {
        let env = try await Env.make()
        try await env.sync()
        try env.store.save(todo("Flüchtig"), inList: env.list)
        try env.store.delete(uid: "flüchtig", inList: env.list)
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("put"), 0)
        XCTAssertEqual(env.server.count("delete"), 0)
    }

    // MARK: Verschieben

    func testMoveBetweenListsUsesMove() async throws {
        let env = try await Env.make(items: [todo("Wandert")])
        let other = env.server.addCollection("Privat")
        try await env.sync()
        let oldHref = try record(env, "wandert").href!
        try env.store.move(uid: "wandert", fromList: env.list, toList: other)
        try await env.sync()

        XCTAssertEqual(env.server.hrefs(env.list), [])
        XCTAssertEqual(env.server.hrefs(other), [other + (oldHref.split(separator: "/").last.map(String.init) ?? "")])
        XCTAssertEqual(env.server.count("move"), 1)
        let rec = try record(env, "wandert", list: other)
        XCTAssertFalse(rec.isDirty)
        XCTAssertEqual(rec.href, env.server.hrefs(other).first)
        XCTAssertEqual(try env.store.tasks(inList: env.list).count, 0)
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("fetch"), 0)
    }

    func testMoveFallsBackToPutAndDelete() async throws {
        let env = try await Env.make(items: [todo("Wandert")])
        let other = env.server.addCollection("Privat")
        env.server.movesSupported = false
        try await env.sync()
        try env.store.move(uid: "wandert", fromList: env.list, toList: other)
        let r = try await env.sync()
        XCTAssertTrue(r.isClean, "\(r.errors)")
        XCTAssertEqual(env.server.hrefs(env.list), [])
        XCTAssertEqual(env.server.titles(other), ["Wandert"])
        XCTAssertFalse(try record(env, "wandert", list: other).isDirty)
    }

    func testMoveOfflineCreatedItemCreatesItInTarget() async throws {
        let env = try await Env.make()
        let other = env.server.addCollection("Privat")
        try await env.sync()
        try env.store.save(todo("Neu"), inList: env.list, now: later)
        try env.store.move(uid: "neu", fromList: env.list, toList: other)
        try await env.sync()
        XCTAssertEqual(env.server.titles(env.list), [])
        XCTAssertEqual(env.server.titles(other), ["Neu"])
        XCTAssertEqual(env.server.count("move"), 0)
    }

    // MARK: Listen

    func testOfflineCreatedListIsUploadedWithItsItems() async throws {
        let env = try await Env.make()
        try await env.sync()
        let l = try env.store.createList(name: "Projekt Größe", color: "#ff0000", homePath: env.server.home)
        XCTAssertEqual(l.href, env.server.home + "projekt-grosse/")
        try env.store.save(todo("Erster Eintrag"), inList: l.href, now: later)
        let r = try await env.sync()
        XCTAssertTrue(r.isClean, "\(r.errors)")
        XCTAssertEqual(env.server.collections[l.href]?.name, "Projekt Größe")
        XCTAssertEqual(env.server.titles(l.href), ["Erster Eintrag"])
        XCTAssertEqual(try env.store.lists().count, 2)
        XCTAssertFalse(try XCTUnwrap(try env.store.list(l.href)).pendingCreate)
    }

    func testRenameAndDeleteList() async throws {
        let env = try await Env.make(items: [todo("X")])
        try await env.sync()
        try env.store.updateList(env.list, name: "Arbeit 2027")
        try await env.sync()
        XCTAssertEqual(env.server.collections[env.list]?.name, "Arbeit 2027")
        XCTAssertEqual(try env.store.list(env.list)?.name, "Arbeit 2027")

        try env.store.deleteList(env.list)
        XCTAssertEqual(try env.store.lists().count, 0)
        try await env.sync()
        XCTAssertNil(env.server.collections[env.list])
        XCTAssertEqual(try env.store.allListsIncludingPending().count, 0)
    }

    func testListDeletedOnServerDisappearsLocally() async throws {
        let env = try await Env.make(items: [todo("X")])
        try await env.sync()
        try await env.server.deleteCollection(href: env.list)
        try await env.sync()
        XCTAssertEqual(try env.store.lists().count, 0)
        XCTAssertEqual(try env.store.allTasks().count, 0)
    }

    // MARK: Ausweichverhalten

    func testInvalidSyncTokenFallsBackToFullComparison() async throws {
        let env = try await Env.make(items: [todo("A"), todo("B")])
        try await env.sync()
        env.server.invalidateTokens = true
        env.server.remove(env.list, uid: "a")
        env.server.clock = later
        env.server.edit(env.list, uid: "b") { $0.title = "B2" }
        try await env.sync()
        XCTAssertEqual(try env.store.tasks(inList: env.list).map(\.item.title), ["B2"])
    }

    func testServerWithoutSyncCollectionUsesETagComparison() async throws {
        let env = try await Env.make(items: [todo("A"), todo("B")])
        env.server.supportsSyncCollection = false
        try await env.sync()
        XCTAssertEqual(try env.store.tasks(inList: env.list).count, 2)
        env.server.remove(env.list, uid: "a")
        env.server.clock = later
        env.server.edit(env.list, uid: "b") { $0.title = "B2" }
        try await env.sync()
        XCTAssertEqual(try env.store.tasks(inList: env.list).map(\.item.title), ["B2"])
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("fetch"), 0)
    }

    func testUnauthorizedStopsSync() async throws {
        let env = try await Env.make(items: [todo("A")])
        try await env.sync()
        env.server.failAuth = true
        do {
            _ = try await env.sync()
            XCTFail("401 muss den Abgleich abbrechen")
        } catch {
            XCTAssertEqual(error as? DAVError, .unauthorized)
        }
        XCTAssertEqual(try env.store.tasks(inList: env.list).count, 1, "lokale Daten bleiben erhalten")
    }

    func testDiscoveryRunsOnceAndPersists() async throws {
        let env = try await Env.make()
        var a = try XCTUnwrap(try env.store.account())
        a.homePath = nil
        a.principalPath = nil
        try env.store.saveAccount(a)
        try await env.sync()
        XCTAssertEqual(try env.store.account()?.homePath, env.server.home)
        env.server.resetCalls()
        try await env.sync()
        XCTAssertEqual(env.server.count("discover"), 0)
    }
}
