import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

public enum SQLValue: Sendable, Equatable {
    case null
    case int(Int64)
    case double(Double)
    case text(String)
    case blob(Data)

    public static func opt(_ s: String?) -> SQLValue { s.map { .text($0) } ?? .null }
    public static func opt(_ i: Int?) -> SQLValue { i.map { .int(Int64($0)) } ?? .null }
    public static func bool(_ b: Bool) -> SQLValue { .int(b ? 1 : 0) }
}

public struct SQLiteError: Error, CustomStringConvertible {
    public let code: Int32
    public let message: String
    public let sql: String
    public var description: String { "SQLite \(code): \(message) [\(sql)]" }
}

/// Zeile eines Abfrageergebnisses (Spaltenzugriff nach Index).
public struct SQLRow {
    fileprivate let stmt: OpaquePointer

    public func int(_ i: Int32) -> Int { Int(sqlite3_column_int64(stmt, i)) }
    public func double(_ i: Int32) -> Double { sqlite3_column_double(stmt, i) }
    public func bool(_ i: Int32) -> Bool { sqlite3_column_int64(stmt, i) != 0 }
    public func isNull(_ i: Int32) -> Bool { sqlite3_column_type(stmt, i) == SQLITE_NULL }
    public func text(_ i: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, i) else { return "" }
        return String(cString: c)
    }
    public func optText(_ i: Int32) -> String? { isNull(i) ? nil : text(i) }
    public func optInt(_ i: Int32) -> Int? { isNull(i) ? nil : int(i) }
}

/// Dünner Wrapper um die C-API von SQLite. Zugriffe sind über ein Lock serialisiert.
public final class SQLiteDatabase: @unchecked Sendable {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    private var depth = 0

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// `path` = Dateipfad oder `:memory:`.
    public init(path: String) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(path, &handle, flags, nil)
        guard rc == SQLITE_OK, let handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(handle)
            throw SQLiteError(code: rc, message: msg, sql: "open \(path)")
        }
        db = handle
        sqlite3_busy_timeout(handle, 3000)
        try execute("PRAGMA foreign_keys = ON")
        if path != ":memory:" {
            try execute("PRAGMA journal_mode = WAL")
            try execute("PRAGMA synchronous = NORMAL")
        }
    }

    deinit { sqlite3_close(db) }

    private func prepare(_ sql: String, _ params: [SQLValue]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        var rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard rc == SQLITE_OK, let stmt else { throw error(rc, sql) }
        for (i, p) in params.enumerated() {
            let idx = Int32(i + 1)
            switch p {
            case .null: rc = sqlite3_bind_null(stmt, idx)
            case .int(let v): rc = sqlite3_bind_int64(stmt, idx, v)
            case .double(let v): rc = sqlite3_bind_double(stmt, idx, v)
            case .text(let v): rc = sqlite3_bind_text(stmt, idx, v, -1, Self.transient)
            case .blob(let d):
                rc = d.withUnsafeBytes { sqlite3_bind_blob(stmt, idx, $0.baseAddress, Int32(d.count), Self.transient) }
            }
            if rc != SQLITE_OK { sqlite3_finalize(stmt); throw error(rc, sql) }
        }
        return stmt
    }

    private func error(_ rc: Int32, _ sql: String) -> SQLiteError {
        SQLiteError(code: rc, message: db.map { String(cString: sqlite3_errmsg($0)) } ?? "closed", sql: sql)
    }

    @discardableResult
    public func execute(_ sql: String, _ params: [SQLValue] = []) throws -> Int {
        lock.lock(); defer { lock.unlock() }
        let stmt = try prepare(sql, params)
        defer { sqlite3_finalize(stmt) }
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_DONE || rc == SQLITE_ROW else { throw error(rc, sql) }
        return Int(sqlite3_changes(db))
    }

    public func query<T>(_ sql: String, _ params: [SQLValue] = [], _ map: (SQLRow) throws -> T) throws -> [T] {
        lock.lock(); defer { lock.unlock() }
        let stmt = try prepare(sql, params)
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW { out.append(try map(SQLRow(stmt: stmt))) }
            else if rc == SQLITE_DONE { break }
            else { throw error(rc, sql) }
        }
        return out
    }

    public func queryOne<T>(_ sql: String, _ params: [SQLValue] = [], _ map: (SQLRow) throws -> T) throws -> T? {
        try query(sql, params, map).first
    }

    public func lastInsertRowID() -> Int64 { sqlite3_last_insert_rowid(db) }

    /// Führt `body` in einer Transaktion aus; verschachtelte Aufrufe nutzen Savepoints.
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        let name = "sp\(depth)"
        try execute(depth == 0 ? "BEGIN IMMEDIATE" : "SAVEPOINT \(name)")
        depth += 1
        do {
            let result = try body()
            depth -= 1
            try execute(depth == 0 ? "COMMIT" : "RELEASE \(name)")
            return result
        } catch {
            depth -= 1
            _ = try? execute(depth == 0 ? "ROLLBACK" : "ROLLBACK TO \(name)")
            if depth > 0 { _ = try? execute("RELEASE \(name)") }
            throw error
        }
    }

    public var userVersion: Int {
        get { (try? queryOne("PRAGMA user_version") { $0.int(0) }) ?? 0 }
        set { _ = try? execute("PRAGMA user_version = \(newValue)") }
    }
}
