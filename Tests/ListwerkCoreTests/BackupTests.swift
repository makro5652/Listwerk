import XCTest
@testable import ListwerkCore

final class BackupTests: XCTestCase {
    func testBackupRoundTripIsLosslessAndIdempotent() throws {
        let src = try Store.inMemory()
        let l = try src.createList(name: "Einkauf", color: "#1e66f5", homePath: Store.localHome)
        _ = try src.save(todo("milch") { $0.notes = "3,5 %"; $0.priority = 1 }, inList: l.href)
        _ = try src.save(todo("brot") { $0.parentUID = "milch"; $0.isCompleted = true }, inList: l.href)
        let data = try src.exportBackup()

        let dst = try Store.inMemory()
        XCTAssertEqual(try dst.importBackup(data, homePath: Store.localHome).tasks, 2)
        XCTAssertEqual(try dst.importBackup(data, homePath: Store.localHome).tasks, 2)   // erneut: aktualisiert nur
        let lists = try dst.lists()
        XCTAssertEqual(lists.map(\.name), ["Einkauf"])
        XCTAssertEqual(lists.first?.color, "#1e66f5")
        XCTAssertEqual(try dst.tasks(inList: lists[0].href).map(\.ics).sorted(), try src.tasks(inList: l.href).map(\.ics).sorted())
    }

    func testRejectsForeignFile() throws {
        let s = try Store.inMemory()
        XCTAssertThrowsError(try s.importBackup(Data("{\"a\":1}".utf8), homePath: Store.localHome))
        XCTAssertTrue(try s.lists().isEmpty)
    }
}
