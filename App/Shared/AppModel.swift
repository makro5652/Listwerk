import Foundation
import SwiftUI
import ListwerkCore

enum SidebarSelection: Hashable {
    case list(String)   // Liste (href)
    case all            // alle Einträge aller Listen
    // Intelligente Listen wie in Microsoft To Do
    case myDay, important, planned, completed

    var isSmart: Bool { if case .list = self { return false } else { return true } }
}

/// Sortierung wie in Microsoft To Do; `manual` = eigene Reihenfolge (Ziehen).
enum SortMode: String, CaseIterable, Identifiable {
    case manual, importance, due, alpha, created
    var id: String { rawValue }
    var label: String {
        switch self {
        case .manual: "Eigene Reihenfolge"
        case .importance: "Wichtigkeit"
        case .due: "Fälligkeitsdatum"
        case .alpha: "Alphabetisch"
        case .created: "Erstellungsdatum"
        }
    }
}

enum AppLayout: String, CaseIterable, Identifiable {
    case listwerk, todo
    var id: String { rawValue }
    var label: String { self == .listwerk ? "Listwerk" : "Wie Microsoft To Do" }
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
    var layout: AppLayout { didSet { try? store.setSetting("layout", layout.rawValue); reloadTasks() } }
    var sortMode: SortMode { didSet { try? store.setSetting("sort", sortMode.rawValue); reloadTasks() } }
    var searchText = "" { didSet { reloadTasks() } }

    /// Favoritenliste (Stern): öffnet sich beim Start. Nur auf diesem Gerät.
    var favoriteList: String? { didSet { try? store.setSetting("favorite", favoriteList) } }

    /// Startbildschirm erledigt: angemeldet oder „Ohne Anmeldung nutzen“ gewählt.
    var onboarded: Bool { didSet { try? store.setSetting("onboarded", onboarded ? "1" : nil) } }
    /// Anmeldung nachträglich aus den Einstellungen geöffnet.
    var showLogin = false

    /// UIDs in „Mein Tag“: nur auf diesem Gerät und nur für heute (wie in To Do beginnt jeder Tag leer).
    private(set) var myDay: Set<String> = []

    // Planwerk
    var planwerkURL: String { didSet { try? store.setSetting("planwerk.url", planwerkURL) } }
    var planwerkLinks: [String: PlanwerkLink] = [:]   // Schlüssel: Listen-href

    private var engine: SyncEngine?
    private var syncTask: Task<Void, Never>?

    init() {
        let url = Self.databaseURL()
        // Ein defekter Datenträger soll die App nicht beim Start abstürzen lassen.
        let s = (try? Store(path: url.path)) ?? (try! Store.inMemory())
        store = s
        themeID = (try? s.setting("theme")) ?? ThemeCatalog.systemID
        planwerkURL = (try? s.setting("planwerk.url")) ?? ""
        account = try? s.account()
        layout = AppLayout(rawValue: (try? s.setting("layout")) ?? "") ?? .listwerk
        sortMode = SortMode(rawValue: (try? s.setting("sort")) ?? "") ?? .manual
        onboarded = (try? s.setting("onboarded")) == "1" || (try? s.account()) != nil
        myDay = Self.loadMyDay()
        favoriteList = (try? s.setting("favorite")) ?? nil
        reloadLists()
        if let f = favoriteList, lists.contains(where: { $0.href == f }) { selection = .list(f) }
        else if let first = lists.first { selection = .list(first.href) } else { selection = .all }
        configureEngine()
        // Zähler erst nach dem ersten Bild: hält den Start kurz.
        Task { @MainActor [weak self] in guard let self else { return }; self.updateSmartCounts((try? self.store.allTasks()) ?? []) }
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
        guard let selection else { records = []; return }
        let all: [TaskRecord]
        if case .list(let href) = selection { all = (try? store.tasks(inList: href)) ?? [] } else { all = (try? store.allTasks()) ?? [] }
        // In der To-Do-Darstellung stehen Erledigte immer im eigenen, einklappbaren Abschnitt.
        let keepDone = showCompleted || layout == .todo || selection == .completed
        let query = searchText.trimmingCharacters(in: .whitespaces)
        var tops = all.filter { $0.item.parentUID == nil && matches(selection, $0) && (keepDone || !$0.item.isCompleted) }
        if !query.isEmpty {
            tops = tops.filter { r in
                r.item.title.localizedCaseInsensitiveContains(query) || r.item.notes.localizedCaseInsensitiveContains(query)
                    || all.contains { $0.item.parentUID == r.item.uid && $0.listHref == r.listHref && $0.item.title.localizedCaseInsensitiveContains(query) }
            }
        }
        switch sortMode {
        case .manual: if selection == .planned { tops.sort { ($0.item.due?.date() ?? .distantFuture) < ($1.item.due?.date() ?? .distantFuture) } }
        case .importance: tops.sort { ($0.item.priority == 0 ? 10 : $0.item.priority) < ($1.item.priority == 0 ? 10 : $1.item.priority) }
        case .due: tops.sort { ($0.item.due?.date() ?? .distantFuture) < ($1.item.due?.date() ?? .distantFuture) }
        case .alpha: tops.sort { $0.item.title.localizedCaseInsensitiveCompare($1.item.title) == .orderedAscending }
        case .created: tops.sort { ($0.item.created ?? .distantPast) > ($1.item.created ?? .distantPast) }
        }
        let shown = Set(tops.map(\.id))
        let children = all.filter { r in r.item.parentUID != nil && shown.contains(Self.recordID(list: r.listHref, uid: r.item.parentUID!)) }
        records = tops + children
    }

    private static func recordID(list: String, uid: String) -> String {
        list + "|" + uid  // wie TaskRecord.id
    }

    func matches(_ selection: SidebarSelection, _ rec: TaskRecord) -> Bool {
        switch selection {
        case .myDay:
            if myDay.contains(rec.item.uid) { return true }
            guard let due = rec.item.due else { return false }
            return Calendar.current.startOfDay(for: due.date()) <= Calendar.current.startOfDay(for: Date())
        case .important: return (1...4).contains(rec.item.priority)
        case .planned: return rec.item.due != nil
        case .completed: return rec.item.isCompleted
        case .all, .list: return true
        }
    }

    /// Offene Einträge der Hauptebene je intelligenter Liste. Zwischengespeichert: die Seitenleiste fragt bei jedem Zeichnen.
    private(set) var smartCounts: [SidebarSelection: Int] = [:]

    func smartCount(_ selection: SidebarSelection) -> Int { smartCounts[selection] ?? 0 }

    private func updateSmartCounts(_ all: [TaskRecord]) {
        let open = all.filter { $0.item.parentUID == nil && !$0.item.isCompleted }
        var c: [SidebarSelection: Int] = [:]
        for s in [SidebarSelection.myDay, .important, .planned, .all] { c[s] = open.filter { matches(s, $0) }.count }
        smartCounts = c
    }

    // MARK: Mein Tag, Wichtig

    private static func loadMyDay() -> Set<String> {
        let d = UserDefaults.standard
        guard d.string(forKey: "myDayDate") == Self.todayKey() else { return [] }
        return Set(d.stringArray(forKey: "myDay") ?? [])
    }

    private static func todayKey() -> String { Date().formatted(.iso8601.year().month().day()) }

    func isInMyDay(_ rec: TaskRecord) -> Bool { myDay.contains(rec.item.uid) }

    func toggleMyDay(_ uid: String) {
        myDay = Self.loadMyDay()
        if myDay.contains(uid) { myDay.remove(uid) } else { myDay.insert(uid) }
        UserDefaults.standard.set(Self.todayKey(), forKey: "myDayDate")
        UserDefaults.standard.set(Array(myDay), forKey: "myDay")
        updateSmartCounts((try? store.allTasks()) ?? [])
        reloadTasks()
    }

    /// Stern wie in To Do: hohe Priorität an/aus.
    func toggleImportant(_ rec: TaskRecord) {
        var item = rec.item
        item.priority = (1...4).contains(item.priority) ? 0 : 1
        save(item, in: rec.listHref)
    }

    func reloadAll() {
        reloadLists()
        reloadTasks()
        let all = (try? store.allTasks()) ?? []
        updateSmartCounts(all)
        Task { await NotificationScheduler.reschedule(records: all) }
    }

    var currentList: TaskList? {
        if case .list(let href) = selection { return lists.first { $0.href == href } }
        return nil
    }

    func list(_ href: String) -> TaskList? { lists.first { $0.href == href } }

    func children(of uid: String) -> [TaskRecord] { records.filter { $0.item.parentUID == uid } }
    func children(of rec: TaskRecord) -> [TaskRecord] { records.filter { $0.item.parentUID == rec.item.uid && $0.listHref == rec.listHref } }
    var topLevel: [TaskRecord] { records.filter { $0.item.parentUID == nil } }

    func openCount(_ list: TaskList) -> Int { (try? store.openTaskCount(inList: list.href)) ?? 0 }

    // MARK: Einträge ändern

    /// Liste, in die das Eingabefeld schreibt: die gewählte, in intelligenten Listen die zuletzt benutzte beschreibbare.
    var entryTarget: TaskList? {
        if let l = currentList { return l.canWrite ? l : nil }
        let writable = lists.filter(\.canWrite)
        return writable.first { $0.href == lastWritableList } ?? writable.first
    }

    private var lastWritableList: String? {
        get { UserDefaults.standard.string(forKey: "lastWritableList") }
        set { UserDefaults.standard.set(newValue, forKey: "lastWritableList") }
    }

    func addTask(title: String, parent: String? = nil, due: ICalDate? = nil) {
        guard let list = entryTarget else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var item = TodoItem(title: trimmed)
        item.parentUID = parent
        item.due = due
        if list.isPlanwerkLinked { item.planwerk = PlanwerkMeta() }
        // Wie in To Do: in „Wichtig“ mit Stern, in „Geplant“ für heute, in „Mein Tag“ direkt im Tag.
        if selection == .important { item.priority = 1 }
        if selection == .planned, item.due == nil { item.due = ICalDate(date: Date(), allDay: true) }
        lastWritableList = list.href
        perform { _ = try self.store.save(item, inList: list.href) }
        if selection == .myDay { toggleMyDay(item.uid) }
        scheduleSync()
    }

    func save(_ item: TodoItem, in listHref: String) {
        perform { _ = try self.store.save(item, inList: listHref) }
        scheduleSync(delay: 2)
    }

    func toggleDone(_ rec: TaskRecord) {
        var item = rec.item
        // Wiederkehrend (wie To Do): dieser Eintrag wird erledigt, ein neuer mit dem nächsten Termin entsteht.
        if !item.isCompleted, let rule = item.recurrence, let due = item.due, let next = Recurrence.next(rule, from: due.date()) {
            var copy = item
            copy.uid = UUID().uuidString.lowercased()
            let cal = Calendar.current
            let time = cal.dateComponents([.hour, .minute, .second], from: due.date())
            let nextDate = due.isAllDay ? next : (cal.date(byAdding: time, to: next) ?? next)
            copy.due = ICalDate(date: nextDate, allDay: due.isAllDay)
            copy.start = nil
            copy.created = nil
            copy.lastModified = nil
            copy.attachments = []
            copy.reminders = item.reminders.filter { if case .relative = $0.trigger { return true } else { return false } }
            item.recurrence = nil
            perform { _ = try self.store.save(copy, inList: rec.listHref) }
        }
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
        guard let list = currentList, list.canWrite, sortMode == .manual, searchText.isEmpty else { return }
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
        // Ohne Konto entstehen lokale Listen; beim Anmelden übernimmt sie Store.adoptLocalLists ins Konto.
        let home = account?.homePath ?? Store.localHome
        perform {
            let l = try self.store.createList(name: name, color: color, homePath: home)
            self.selection = .list(l.href)
        }
        scheduleSync()
    }

    func toggleFavorite(_ list: TaskList) { favoriteList = favoriteList == list.href ? nil : list.href }

    /// Listen in der Seitenleiste umordnen (Ziehen). Die Reihenfolge geht beim Abgleich als calendar-order an Nextcloud.
    func moveLists(from offsets: IndexSet, to destination: Int) {
        var hrefs = lists.map(\.href)
        hrefs.move(fromOffsets: offsets, toOffset: destination)
        perform { try self.store.reorderLists(hrefs) }
        scheduleSync(delay: 2)
    }

    /// Eine Liste um eine Stelle nach oben (-1) oder unten (+1) schieben.
    func moveList(_ list: TaskList, by delta: Int) {
        guard let i = lists.firstIndex(where: { $0.href == list.href }) else { return }
        let j = i + delta
        guard lists.indices.contains(j) else { return }
        moveLists(from: IndexSet(integer: i), to: delta > 0 ? j + 1 : j)
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
            // Ohne Anmeldung angelegte Listen gehen ins Konto und werden beim folgenden Abgleich hochgeladen.
            let moved = try store.adoptLocalLists(homePath: found.home)
            if case .list(let old) = selection, let new = moved[old] { selection = .list(new) }
            if let old = lastWritableList, let new = moved[old] { lastWritableList = new }
            if let old = favoriteList, let new = moved[old] { favoriteList = new }
            account = acc
            onboarded = true
            showLogin = false
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
        onboarded = false
        planwerkLinks = [:]
        reloadAll()
        selection = nil
    }

    /// „Ohne Anmeldung nutzen“: lokal arbeiten, eine erste Liste anlegen.
    func skipLogin() {
        onboarded = true
        if lists.isEmpty { createList(name: "Aufgaben", color: nil) }
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

    @discardableResult
    func refreshPlanwerk() async -> String {
        guard let base = URL(string: planwerkURL), !planwerkURL.isEmpty, !planwerkToken.isEmpty else {
            return "Adresse und Token eintragen."
        }
        let cache = PlanwerkLinkCache(connector: PlanwerkClient(baseURL: base, token: planwerkToken))
        do {
            try await cache.apply(to: store)
            let links = try await cache.current()
            var map: [String: PlanwerkLink] = [:]
            for l in links { map[l.listPath] = l }
            planwerkLinks = map
            return "Verbunden. \(links.count) Projekt(e) mit Nextcloud-Liste verknüpft."
        } catch {
            // Planwerk ist optional; ein Fehler blockiert nichts.
            statusMessage = "Planwerk nicht erreichbar."
            return "Fehler: \(error)"
        }
    }

    // MARK: Microsoft To Do (Import)

    var msClientID: String {
        get { (try? store.setting("mstodo.clientid")) ?? "" }
        set { try? store.setSetting("mstodo.clientid", newValue.trimmingCharacters(in: .whitespaces)) }
    }
    var msConnected: Bool { CredentialStore.load(account: "mstodo-refresh") != nil }
    var msLogin: MSTodoClient.DeviceCode?     // Code, den der Nutzer auf microsoft.com/link eingibt

    /// Verbindet per Geräte-Code (falls nötig) und importiert alle Listen. Gibt eine Meldung für die Oberfläche zurück.
    func importMSTodo() async -> String {
        guard !msClientID.isEmpty else { return "Bitte zuerst die App-ID (Client-ID) eintragen." }
        let home = account?.homePath ?? Store.localHome
        let client = MSTodoClient(clientID: msClientID)
        do {
            var tokens: MSTodoClient.Tokens
            if let refresh = CredentialStore.load(account: "mstodo-refresh") {
                do { tokens = try await client.refresh(refresh) } catch DAVError.http(let st, _) where st == 400 {
                    CredentialStore.delete(account: "mstodo-refresh")   // Token widerrufen oder abgelaufen
                    return "Anmeldung abgelaufen, bitte erneut auf „Verbinden“ klicken."
                }
            } else {
                let code = try await client.startDeviceLogin()
                msLogin = code
                defer { msLogin = nil }
                let deadline = Date().addingTimeInterval(TimeInterval(code.expiresIn))
                var found: MSTodoClient.Tokens?
                while found == nil, Date() < deadline {
                    try await Task.sleep(for: .seconds(code.interval))
                    found = try await client.pollDeviceLogin(code)
                }
                guard let f = found else { return "Anmeldung abgelaufen, bitte erneut versuchen." }
                tokens = f
            }
            CredentialStore.save(tokens.refresh, account: "mstodo-refresh")
            let data = try await client.fetchAll(accessToken: tokens.access)
            let n = try store.importMSTodo(data, homePath: home)
            reloadAll()
            scheduleSync()
            return "\(n.lists) Liste(n), \(n.tasks) Aufgabe(n) importiert."
        } catch {
            return "Fehler: \(error)"
        }
    }

    func disconnectMSTodo() { CredentialStore.delete(account: "mstodo-refresh") }

    // MARK: Sichern und Drucken

    var homeForNewLists: String { account?.homePath ?? Store.localHome }

    func importBackup(from url: URL) -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let n = try store.importBackup(Data(contentsOf: url), homePath: homeForNewLists)
            reloadAll()
            scheduleSync()
            return "\(n.lists) Liste(n), \(n.tasks) Eintrag/Einträge wiederhergestellt."
        } catch {
            return "Fehler: \(error.localizedDescription)"
        }
    }

    /// Alle Listen, nur offene Einträge: zum Ausdrucken und Abhaken.
    func pdfAllLists() -> Data {
        PDFExport.make(lists.map { l in
            let all = (try? store.tasks(inList: l.href)) ?? []
            return .init(title: l.name, rows: PDFExport.rows(all, tops: all.filter { $0.item.parentUID == nil }, includeDone: false))
        })
    }

    /// Die gerade angezeigte Liste bzw. Ansicht, wie sie zu sehen ist.
    func pdfCurrentView() -> Data {
        PDFExport.make([.init(title: selectionTitle,
                              rows: PDFExport.rows(records, tops: topLevel, includeDone: showCompleted || selection == .completed))])
    }
}
