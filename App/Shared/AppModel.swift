import Foundation
import SwiftUI
import ListwerkCore

enum SidebarSelection: Hashable {
    case list(String)   // Liste (href)
    case all            // alle offenen Einträge
}

/// Zentrale Zustandsverwaltung. Die Oberfläche liest ausschließlich aus der lokalen Datenbank;
/// der Abgleich mit Nextcloud läuft getrennt (SyncEngine).
@MainActor
@Observable
final class AppModel {
    // Persistenter Zustand
    private(set) var store: Store
    var account: Account?
    var lists: [TaskList] = []
    var records: [TaskRecord] = []          // Einträge der aktuellen Auswahl (inkl. Unteraufgaben)
    var selection: SidebarSelection? { didSet { reloadTasks() } }
    var selectedTaskID: String?
    var showCompleted = false { didSet { reloadTasks() } }

    // Status
    var isSyncing = false
    var statusMessage: String?
    var errorMessage: String?
    var loginURL: URL?                      // Anmeldeseite (Login Flow v2), wird von der View geöffnet

    // Darstellung
    var themeID: String { didSet { try? store.setSetting("theme", themeID) } }

    // Planwerk
    var planwerkURL: String { didSet { try? store.setSetting("planwerk.url", planwerkURL) } }
    var planwerkLinks: [String: PlanwerkLink] = [:]   // Schlüssel: Listen-href

    private var engine: SyncEngine?
    private var syncTask: Task<Void, Never>?

    init() {
        let url = Self.databaseURL()
        // Ein defekter Datenträger soll die App nicht beim Start abstürzen lassen.
        store = (try? Store(path: url.path)) ?? (try! Store.inMemory())
        themeID = (try? store.setting("theme")) ?? ThemeCatalog.systemID
        planwerkURL = (try? store.setting("planwerk.url")) ?? ""
        account = try? store.account()
        reloadLists()
        if let first = lists.first { selection = .list(first.href) } else { selection = .all }
        configureEngine()
    }

    static func databaseURL() -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Listwerk", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("listwerk.sqlite")
    }

    // MARK: Theme

    func theme(systemIsDark: Bool) -> Theme { ThemeCatalog.resolve(id: themeID, systemIsDark: systemIsDark) }

    // MARK: Laden

    func reloadLists() {
        lists = (try? store.lists()) ?? []
    }

    func reloadTasks() {
        switch selection {
        case .list(let href):
            records = (try? store.tasks(inList: href)) ?? []
        case .all:
            records = (try? store.allTasks()) ?? []
        case nil:
            records = []
        }
        if !showCompleted { records = records.filter { !$0.item.isCompleted || $0.item.parentUID != nil } }
    }

    func reloadAll() {
        reloadLists()
        reloadTasks()
        let store = self.store
        Task { await NotificationScheduler.reschedule(records: (try? store.allTasks()) ?? []) }
    }

    var currentList: TaskList? {
        if case .list(let href) = selection { return lists.first { $0.href == href } }
        return nil
    }

    func list(_ href: String) -> TaskList? { lists.first { $0.href == href } }

    func children(of uid: String) -> [TaskRecord] { records.filter { $0.item.parentUID == uid } }
    var topLevel: [TaskRecord] { records.filter { $0.item.parentUID == nil } }

    func openCount(_ list: TaskList) -> Int { (try? store.openTaskCount(inList: list.href)) ?? 0 }

    // MARK: Einträge ändern

    func addTask(title: String, parent: String? = nil) {
        guard let list = currentList, list.canWrite else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var item = TodoItem(title: trimmed)
        item.parentUID = parent
        if list.isPlanwerkLinked { item.planwerk = PlanwerkMeta() }
        perform { _ = try self.store.save(item, inList: list.href) }
        scheduleSync()
    }

    func save(_ item: TodoItem, in listHref: String) {
        perform { _ = try self.store.save(item, inList: listHref) }
        scheduleSync(delay: 2)
    }

    func toggleDone(_ rec: TaskRecord) {
        var item = rec.item
        item.isCompleted.toggle()
        item.completedAt = item.isCompleted ? Date() : nil
        item.percentComplete = item.isCompleted ? 100 : 0
        save(item, in: rec.listHref)
    }

    func delete(_ rec: TaskRecord) {
        perform { try self.store.delete(uid: rec.item.uid, inList: rec.listHref) }
        scheduleSync()
    }

    func move(_ rec: TaskRecord, to target: String) {
        guard rec.listHref != target else { return }
        perform { try self.store.move(uid: rec.item.uid, fromList: rec.listHref, toList: target) }
        scheduleSync()
    }

    /// Ziehen innerhalb der Liste: `offsets` der sichtbaren Hauptebene → neue Position.
    func reorder(from offsets: IndexSet, to destination: Int) {
        guard let list = currentList, list.canWrite else { return }
        var items = topLevel
        items.move(fromOffsets: offsets, toOffset: destination)
        guard let moved = offsets.first.map({ topLevel[$0] }),
              let newIndex = items.firstIndex(where: { $0.id == moved.id }) else { return }
        let after = newIndex == 0 ? nil : items[newIndex - 1].item.uid
        perform { try self.store.reorder(uid: moved.item.uid, inList: list.href, after: after) }
        scheduleSync(delay: 2)
    }

    // MARK: Listen

    func createList(name: String, color: String?) {
        guard let home = account?.homePath else { errorMessage = "Nicht mit Nextcloud verbunden."; return }
        perform {
            let l = try self.store.createList(name: name, color: color, homePath: home)
            self.selection = .list(l.href)
        }
        scheduleSync()
    }

    func renameList(_ list: TaskList, name: String, color: String?) {
        perform { try self.store.updateList(list.href, name: name, color: .some(color)) }
        scheduleSync()
    }

    func deleteList(_ list: TaskList) {
        perform { try self.store.deleteList(list.href) }
        if selection == .list(list.href) { selection = .all }
        scheduleSync()
    }

    private func perform(_ work: () throws -> Void) {
        do { try work() } catch { errorMessage = "Speichern fehlgeschlagen: \(error.localizedDescription)" }
        reloadAll()
    }

    // MARK: Anmeldung (Login Flow v2)

    func login(server: String) async {
        errorMessage = nil
        var text = server.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.contains("://") { text = "https://" + text }
        guard let url = URL(string: text), url.host != nil else { errorMessage = "Ungültige Server-Adresse."; return }
        do {
            let flow = LoginFlowV2()
            let session = try await flow.start(server: url)
            loginURL = session.loginURL
            statusMessage = "Bitte im Browser den Zugriff bestätigen …"
            let creds = try await flow.waitForCredentials(session)
            loginURL = nil
            let client = CalDAVClient(serverURL: creds.serverURL, loginName: creds.loginName, appPassword: creds.appPassword)
            let found = try await client.discover()
            let acc = Account(serverURL: creds.serverURL, loginName: creds.loginName, principalPath: found.principal, homePath: found.home)
            CredentialStore.save(creds.appPassword, account: Self.accountKey(acc))
            try store.saveAccount(acc)
            account = acc
            statusMessage = nil
            await NotificationScheduler.requestAuthorization()
            configureEngine()
            await syncNow()
        } catch {
            loginURL = nil
            statusMessage = nil
            errorMessage = "Anmeldung fehlgeschlagen: \(error.localizedDescription)"
        }
    }

    func logout() {
        if let acc = account { CredentialStore.delete(account: Self.accountKey(acc)) }
        engine = nil
        try? store.wipe()
        account = nil
        planwerkLinks = [:]
        reloadAll()
        selection = nil
    }

    static func accountKey(_ a: Account) -> String { "\(a.loginName)@\(a.serverURL.absoluteString)" }

    private func configureEngine() {
        guard let acc = account, let pw = CredentialStore.load(account: Self.accountKey(acc)) else { engine = nil; return }
        let client = CalDAVClient(serverURL: acc.serverURL, loginName: acc.loginName, appPassword: pw)
        engine = SyncEngine(store: store, service: client)
    }

    // MARK: Synchronisation

    /// Bündelt viele schnelle Änderungen zu einem Abgleich.
    func scheduleSync(delay: TimeInterval = 0.5) {
        guard engine != nil else { return }
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            if Task.isCancelled { return }
            await self?.syncNow()
        }
    }

    func syncNow() async {
        guard let engine, !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let report = try await engine.syncAll()
            statusMessage = report.isClean ? nil : report.errors.joined(separator: "\n")
            if report.conflicts > 0 { statusMessage = "\(report.conflicts) Konflikt(e) automatisch aufgelöst." }
        } catch SyncError.notConfigured {
            errorMessage = "Kein Nextcloud-Konto eingerichtet."
        } catch DAVError.unauthorized {
            errorMessage = "Anmeldung abgelaufen. Bitte neu anmelden."
        } catch {
            errorMessage = "Abgleich fehlgeschlagen: \(error.localizedDescription)"
        }
        await refreshPlanwerk()
        reloadAll()
    }

    // MARK: Planwerk

    var planwerkToken: String {
        get { CredentialStore.load(account: "planwerk-token") ?? "" }
        set { newValue.isEmpty ? CredentialStore.delete(account: "planwerk-token") : CredentialStore.save(newValue, account: "planwerk-token") }
    }

    func refreshPlanwerk() async {
        guard let base = URL(string: planwerkURL), !planwerkURL.isEmpty, !planwerkToken.isEmpty else { return }
        let cache = PlanwerkLinkCache(connector: PlanwerkClient(baseURL: base, token: planwerkToken))
        do {
            try await cache.apply(to: store)
            let links = try await cache.current()
            var map: [String: PlanwerkLink] = [:]
            for l in links { map[l.listPath] = l }
            planwerkLinks = map
        } catch {
            // Planwerk ist optional; ein Fehler blockiert nichts.
            statusMessage = "Planwerk nicht erreichbar."
        }
    }
}
