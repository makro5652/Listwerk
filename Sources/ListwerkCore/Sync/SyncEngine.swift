import Foundation

public struct SyncReport: Equatable, Sendable {
    public var pushed = 0
    public var pulled = 0
    public var deleted = 0
    public var conflicts = 0
    public var errors: [String] = []
    public init() {}
    public var isClean: Bool { errors.isEmpty }
}

public enum SyncError: Error, Equatable {
    case notConfigured
}

/// Gleicht die lokale Datenbank mit Nextcloud ab. Läuft seriell (Actor), nie zwei Durchläufe gleichzeitig.
///
/// Reihenfolge je Durchlauf: Listenänderungen → ausstehende Verschiebungen → Listen vom Server →
/// je Liste erst hochladen, dann herunterladen.
public actor SyncEngine {
    private let store: Store
    private let service: CalDAVService
    private let now: @Sendable () -> Date

    public init(store: Store, service: CalDAVService, now: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.service = service
        self.now = now
    }

    // MARK: Einstieg

    public func syncAll() async throws -> SyncReport {
        guard let account = try store.account() else { throw SyncError.notConfigured }
        var home = account.homePath
        if home == nil {
            let found = try await service.discover()
            home = found.home
            var a = account
            a.principalPath = found.principal
            a.homePath = found.home
            try store.saveAccount(a)
        }
        var report = SyncReport()

        try await pushListChanges(&report)
        try await pushMoves(&report)

        let remote = try await service.listCollections(home: home!)
        try store.applyRemoteLists(remote)

        for list in try store.lists() {
            do {
                try await syncList(list, remote: remote.first { $0.href == list.href }, &report)
            } catch DAVError.unauthorized {
                throw DAVError.unauthorized
            } catch {
                report.errors.append("\(list.name): \(error)")
            }
        }
        return report
    }

    // MARK: Listen

    private func pushListChanges(_ report: inout SyncReport) async throws {
        for l in try store.allListsIncludingPending() {
            do {
                if l.pendingDelete {
                    try await service.deleteCollection(href: l.href)
                    try store.removeListLocally(l.href)
                } else if l.pendingCreate {
                    do {
                        try await service.makeCollection(href: l.href, name: l.name, color: l.color)
                    } catch DAVError.http(let status, _) where status == 405 || status == 409 {
                        // Existiert bereits (z. B. Antwort ging verloren): als vorhanden behandeln.
                    }
                    try store.clearListFlags(l.href)
                } else if l.pendingProps {
                    try await service.updateCollection(href: l.href, name: l.name, color: l.color)
                    try store.clearListFlags(l.href)
                }
            } catch DAVError.unauthorized {
                throw DAVError.unauthorized
            } catch {
                report.errors.append("Liste \(l.name): \(error)")
            }
        }
    }

    // MARK: Verschiebungen

    private func pushMoves(_ report: inout SyncReport) async throws {
        for m in try store.pendingMoves() {
            guard let rec = try store.record(uid: m.uid, inList: m.toList) else {
                try store.clearPendingMove(uid: m.uid)
                continue
            }
            let name = m.fromHref.split(separator: "/").last.map(String.init) ?? Self.fileName(for: m.uid)
            let target = m.toList + name
            do {
                switch try await service.move(from: m.fromHref, to: target) {
                case .ok:
                    try store.setHref(uid: m.uid, listHref: m.toList, href: target)
                case .failed:
                    // Ersatzweg: im Ziel anlegen, in der Quelle löschen.
                    guard case .ok(let etag) = try await service.put(href: target, ics: rec.ics, etag: nil, create: true) else {
                        report.errors.append("Verschieben von „\(rec.item.title)“ fehlgeschlagen")
                        continue
                    }
                    try await finishUpload(rec, href: target, etag: etag)
                    _ = try await service.delete(href: m.fromHref, etag: rec.etag)
                }
                try store.clearPendingMove(uid: m.uid)
            } catch DAVError.unauthorized {
                throw DAVError.unauthorized
            } catch {
                report.errors.append("Verschieben von „\(rec.item.title)“: \(error)")
            }
        }
    }

    // MARK: Eine Liste

    public func sync(listHref: String) async throws -> SyncReport {
        var report = SyncReport()
        guard let list = try store.list(listHref) else { return report }
        try await syncList(list, remote: nil, &report)
        return report
    }

    private func syncList(_ list: TaskList, remote: RemoteList?, _ report: inout SyncReport) async throws {
        if list.canWrite { try await pushTasks(list, &report) }
        try await pullTasks(try store.list(list.href) ?? list, &report)
    }

    // MARK: Hochladen

    private func pushTasks(_ list: TaskList, _ report: inout SyncReport) async throws {
        for rec in try store.dirtyRecords(inList: list.href) {
            do {
                if rec.isDeleted { try await pushDelete(rec, &report) }
                else if rec.href == nil { try await pushCreate(rec, list: list, &report) }
                else { try await pushUpdate(rec, &report) }
            } catch DAVError.unauthorized {
                throw DAVError.unauthorized
            } catch {
                report.errors.append("„\(rec.item.title)“: \(error)")
            }
        }
    }

    static func fileName(for uid: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return (uid.addingPercentEncoding(withAllowedCharacters: allowed) ?? uid) + ".ics"
    }

    private func pushCreate(_ rec: TaskRecord, list: TaskList, _ report: inout SyncReport) async throws {
        let href = list.href + Self.fileName(for: rec.item.uid)
        switch try await service.put(href: href, ics: rec.ics, etag: nil, create: true) {
        case .ok(let etag):
            try await finishUpload(rec, href: href, etag: etag)
            report.pushed += 1
        case .preconditionFailed:
            // Unter diesem Namen liegt schon etwas (z. B. frühere Übertragung ohne Antwort): wie ein Update behandeln.
            guard let existing = try await service.fetch(list: list.href, hrefs: [href]).first else { return }
            var r = rec
            r.href = href
            r.etag = existing.etag
            try store.setHref(uid: rec.item.uid, listHref: rec.listHref, href: href, etag: existing.etag, baseICS: existing.ics)
            try await resolveAndRetry(r, remote: existing, &report)
        }
    }

    private func pushUpdate(_ rec: TaskRecord, _ report: inout SyncReport) async throws {
        guard let href = rec.href else { return }
        switch try await service.put(href: href, ics: rec.ics, etag: rec.etag, create: false) {
        case .ok(let etag):
            try await finishUpload(rec, href: href, etag: etag)
            report.pushed += 1
        case .preconditionFailed:
            let remote = try await service.fetch(list: rec.listHref, hrefs: [href]).first
            if let remote {
                try await resolveAndRetry(rec, remote: remote, &report)
            } else {
                // Auf dem Server gelöscht, lokal geändert: neu anlegen.
                if case .ok(let etag) = try await service.put(href: href, ics: rec.ics, etag: nil, create: true) {
                    try await finishUpload(rec, href: href, etag: etag)
                    report.pushed += 1
                }
            }
        }
    }

    /// Nach dem Hochladen: Server-ETag und Serverstand als Basis für den nächsten Dreiwege-Abgleich merken.
    /// Fehlt der ETag, hat der Server die Daten verändert gespeichert; dann wird neu geladen.
    private func finishUpload(_ rec: TaskRecord, href: String, etag: String?) async throws {
        if let etag {
            try store.markSynced(uid: rec.item.uid, listHref: rec.listHref, href: href, etag: etag, ics: rec.ics)
        } else if let fetched = try await service.fetch(list: rec.listHref, hrefs: [href]).first {
            try store.markSynced(uid: rec.item.uid, listHref: rec.listHref, href: href, etag: fetched.etag, ics: fetched.ics)
        } else {
            try store.markSynced(uid: rec.item.uid, listHref: rec.listHref, href: href, etag: nil, ics: rec.ics)
        }
    }

    private func pushDelete(_ rec: TaskRecord, _ report: inout SyncReport) async throws {
        guard let href = rec.href else {
            try store.purge(uid: rec.item.uid, listHref: rec.listHref)
            return
        }
        switch try await service.delete(href: href, etag: rec.etag) {
        case .ok, .notFound:
            try store.purge(uid: rec.item.uid, listHref: rec.listHref)
            report.deleted += 1
        case .preconditionFailed:
            // Lokal gelöscht, auf dem Server geändert: Die Serveränderung gewinnt, der Eintrag erscheint wieder.
            if let remote = try await service.fetch(list: rec.listHref, hrefs: [href]).first {
                try store.upsertFromServer(listHref: rec.listHref, href: href, etag: remote.etag, ics: remote.ics)
                report.pulled += 1
            } else {
                try store.purge(uid: rec.item.uid, listHref: rec.listHref)
            }
        }
    }

    // MARK: Zusammenführen

    /// Führt lokalen und Serverstand zusammen und lädt die Fassung einmal erneut hoch.
    private func resolveAndRetry(_ rec: TaskRecord, remote: RemoteResource, _ report: inout SyncReport) async throws {
        guard try merge(rec, with: remote, &report) else { return }
        guard let merged = try store.record(uid: rec.item.uid, inList: rec.listHref), let href = merged.href else { return }
        switch try await service.put(href: href, ics: merged.ics, etag: merged.etag, create: false) {
        case .ok(let etag):
            try await finishUpload(merged, href: href, etag: etag)
            report.pushed += 1
        case .preconditionFailed:
            report.errors.append("„\(rec.item.title)“: Konflikt, wird beim nächsten Abgleich erneut versucht")
        }
    }

    /// - Returns: `true`, wenn noch lokale Änderungen hochzuladen sind.
    private func merge(_ rec: TaskRecord, with remote: RemoteResource, _ report: inout SyncReport) throws -> Bool {
        guard var remoteDoc = ICalDocument(parsing: remote.ics) else { return false }
        let base = rec.baseICS.flatMap { ICalDocument(parsing: $0) }?.makeItem()
        let result = TodoMerge.merge(base: base, local: rec.item, remote: remoteDoc.makeItem())
        remoteDoc.apply(result.merged, now: now())
        let mergedICS = remoteDoc.serialized()
        for c in result.conflicts {
            try store.logConflict(uid: rec.item.uid, field: c.field, local: c.local, remote: c.remote, at: now())
            report.conflicts += 1
        }
        if mergedICS == remote.ics {
            try store.markSynced(uid: rec.item.uid, listHref: rec.listHref, href: remote.href, etag: remote.etag, ics: remote.ics)
            return false
        }
        try store.storeMerged(uid: rec.item.uid, listHref: rec.listHref, serverICS: remote.ics, serverETag: remote.etag, mergedICS: mergedICS)
        try store.setHref(uid: rec.item.uid, listHref: rec.listHref, href: remote.href)
        return true
    }

    // MARK: Herunterladen

    private func pullTasks(_ list: TaskList, _ report: inout SyncReport) async throws {
        var changed: [RemoteEntry] = []
        var removedHrefs: [String] = []
        var newToken: String?
        var isFullSet = false

        var result: RemoteChanges?
        do {
            result = try await service.changes(list: list.href, since: list.syncToken)
            if result?.tokenInvalid == true {
                result = try await service.changes(list: list.href, since: nil)
                isFullSet = true
            } else if list.syncToken == nil {
                isFullSet = true
            }
        } catch DAVError.unauthorized {
            throw DAVError.unauthorized
        } catch {
            result = nil  // sync-collection nicht unterstützt: ETag-Vergleich
        }

        let local = try store.recordsWithHref(inList: list.href)
        let localByHref = Dictionary(local.map { ($0.href!, $0) }, uniquingKeysWith: { a, _ in a })

        if let result, !result.tokenInvalid {
            changed = result.changed
            removedHrefs = result.removed
            newToken = result.newToken
            if isFullSet {
                let present = Set(changed.map(\.href))
                removedHrefs += local.filter { !present.contains($0.href!) }.map { $0.href! }
            }
        } else {
            let entries = try await service.listAll(list: list.href)
            changed = entries
            let present = Set(entries.map(\.href))
            removedHrefs = local.filter { !present.contains($0.href!) }.map { $0.href! }
        }

        let toFetch = changed.filter { localByHref[$0.href]?.etag == nil || localByHref[$0.href]?.etag != $0.etag }.map(\.href)
        if !toFetch.isEmpty {
            for res in try await service.fetch(list: list.href, hrefs: toFetch) {
                guard let item = ICalDocument(parsing: res.ics)?.makeItem(), !item.uid.isEmpty else { continue }
                if let rec = try store.record(uid: item.uid, inList: list.href), rec.isDirty, !rec.isDeleted {
                    _ = try merge(rec, with: res, &report)  // wird beim nächsten Durchlauf hochgeladen
                } else if let rec = try store.record(uid: item.uid, inList: list.href), rec.isDeleted {
                    continue  // lokal gelöscht: das Löschen wird noch hochgeladen
                } else {
                    try store.upsertFromServer(listHref: list.href, href: res.href, etag: res.etag, ics: res.ics)
                }
                report.pulled += 1
            }
        }

        for href in removedHrefs {
            guard let rec = localByHref[href] else { continue }
            if rec.isDirty && !rec.isDeleted {
                // Lokal geändert, auf dem Server gelöscht: neu anlegen.
                try store.setHref(uid: rec.item.uid, listHref: list.href, href: nil, etag: nil, baseICS: nil)
            } else {
                try store.purge(uid: rec.item.uid, listHref: list.href)
                report.deleted += 1
            }
        }

        var ctag: String?
        if newToken == nil { ctag = try await service.collectionState(list.href).ctag }
        try store.updateListSyncState(list.href, ctag: ctag ?? list.ctag, syncToken: newToken ?? list.syncToken)
    }
}
