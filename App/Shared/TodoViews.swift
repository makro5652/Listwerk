import SwiftUI
import ListwerkCore

/// Intelligente Listen mit Namen, Symbol und Farbe wie in Microsoft To Do.
struct SmartListInfo: Identifiable {
    let selection: SidebarSelection
    let name: String
    let icon: String
    let tint: Color
    var id: SidebarSelection { selection }

    static let all: [SmartListInfo] = [
        .init(selection: .myDay, name: "Mein Tag", icon: "sun.max", tint: Color(hex: "#7a7574")),
        .init(selection: .important, name: "Wichtig", icon: "star", tint: Color(hex: "#c6427f")),
        .init(selection: .planned, name: "Geplant", icon: "calendar", tint: Color(hex: "#138a72")),
        .init(selection: .all, name: "Aufgaben", icon: "infinity", tint: Color(hex: "#d13438")),
        .init(selection: .completed, name: "Erledigt", icon: "checkmark.circle", tint: Color(hex: "#2e7d32")),
    ]

    static func info(_ s: SidebarSelection?) -> SmartListInfo? { all.first { $0.selection == s } }
}

extension AppModel {
    /// Titel der Auswahl (intelligente Liste oder Listenname).
    var selectionTitle: String {
        if let info = SmartListInfo.info(selection) { return info.name }
        return currentList?.name ?? ""
    }
}

/// Sortiermenü für beide Darstellungen.
struct SortMenu: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var model = model
        Menu {
            Picker("Sortieren nach", selection: $model.sortMode) {
                ForEach(SortMode.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.inline)
        } label: { Label("Sortieren", systemImage: "arrow.up.arrow.down") }
    }
}

// MARK: - Darstellung „Wie Microsoft To Do“

struct TodoRootView: View {
    var body: some View {
        NavigationSplitView {
            TodoSidebar()
        } content: {
            TodoListView()
        } detail: {
            TaskDetailPane()
        }
    }
}

private struct TodoSidebar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var creating = false
    @State private var newName = ""
    @State private var renaming: TaskList?
    @State private var renameText = ""

    var body: some View {
        @Bindable var model = model
        List(selection: $model.selection) {
            ForEach(SmartListInfo.all) { s in
                row(icon: s.icon, tint: s.tint, name: s.name, count: model.smartCount(s.selection)).tag(s.selection)
            }
            Divider()
            ForEach(model.lists) { list in
                row(icon: "list.bullet", tint: list.color.map { Color(hex: ThemeCatalog.displayHex(stored: $0, in: theme)) } ?? theme.color(\.accent),
                    name: list.name, count: model.openCount(list), favorite: model.favoriteList == list.href)
                    .tag(SidebarSelection.list(list.href))
                    .dropDestination(for: String.self) { items, _ in
                        guard list.canWrite else { return false }
                        for s in items { handleDrop(s, onto: list) }
                        return true
                    }
                    .contextMenu {
                        Button("Umbenennen …") { renaming = list; renameText = list.name }.disabled(!list.canWrite)
                        Button("Nach oben") { model.moveList(list, by: -1) }.disabled(list.href == model.lists.first?.href)
                        Button("Nach unten") { model.moveList(list, by: 1) }.disabled(list.href == model.lists.last?.href)
                        Button(model.favoriteList == list.href ? "Favorit entfernen" : "Als Favorit festlegen") { model.toggleFavorite(list) }
                        Divider()
                        Button("Löschen", role: .destructive) { model.deleteList(list) }
                    }
            }
            .onMove { offsets, dest in model.moveLists(from: offsets, to: dest) }
        }
        .scrollContentBackground(.hidden)
        .background(theme.color(\.sidebar))
        .navigationTitle("Listwerk")
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                if let s = model.statusMessage { Text(s).font(.caption).foregroundStyle(theme.color(\.textSecondary)) }
                Button { creating = true } label: { Label("Neue Liste", systemImage: "plus") }
                    .buttonStyle(.plain).foregroundStyle(theme.color(\.accent))
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        }
        .toolbar {
            if model.account != nil {
                ToolbarItem {
                    Button { Task { await model.syncNow() } } label: {
                        if model.isSyncing { ProgressView() } else { Label("Abgleichen", systemImage: "arrow.triangle.2.circlepath") }
                    }
                }
            }
        }
        .alert("Neue Liste", isPresented: $creating) {
            TextField("Name", text: $newName)
            Button("Anlegen") { model.createList(name: newName, color: nil); newName = "" }
            Button("Abbrechen", role: .cancel) { newName = "" }
        }
        .alert("Liste umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $renameText)
            Button("Speichern") { if let l = renaming { model.renameList(l, name: renameText, color: l.color) } }
            Button("Abbrechen", role: .cancel) {}
        }
    }

    private func row(icon: String, tint: Color, name: String, count: Int, favorite: Bool = false) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(tint).frame(width: 18)
            Text(name).lineLimit(1)
            if favorite { Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow).accessibilityLabel("Favorit") }
            Spacer()
            if count > 0 { Text("\(count)").font(.caption).foregroundStyle(theme.color(\.textSecondary)) }
        }
        .padding(.vertical, 3)
    }

    private func handleDrop(_ payload: String, onto list: TaskList) {
        let parts = payload.components(separatedBy: "\n")
        guard parts.count == 2, parts[0] != list.href,
              let rec = (try? model.store.record(uid: parts[1], inList: parts[0])) ?? nil else { return }
        model.move(rec, to: list.href)
    }
}

private struct TodoListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var newTitle = ""
    @State private var showDone = true

    private var accent: Color {
        if let s = SmartListInfo.info(model.selection) { return s.tint }
        if let c = model.currentList?.color { return Color(hex: ThemeCatalog.displayHex(stored: c, in: theme)) }
        return theme.color(\.accent)
    }

    var body: some View {
        @Bindable var model = model
        let tops = model.topLevel
        let open = model.selection == .completed ? tops : tops.filter { !$0.item.isCompleted }
        let done = model.selection == .completed ? [] : tops.filter { $0.item.isCompleted }
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.selectionTitle).font(.largeTitle.bold()).foregroundStyle(.white).lineLimit(2)
                if model.selection == .myDay {
                    Text(Date().formatted(date: .complete, time: .omitted)).foregroundStyle(.white.opacity(0.85))
                }
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 10)

            if model.selection != .completed {
                HStack {
                    Image(systemName: "plus").foregroundStyle(theme.color(\.accent))
                    TextField(model.entryTarget == nil ? "Liste wählen, um Aufgaben anzulegen" : "Aufgabe hinzufügen", text: $newTitle)
                        .textFieldStyle(.plain)
                        .disabled(model.entryTarget == nil)
                        .onSubmit { model.addTask(title: newTitle); newTitle = "" }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 8).fill(theme.color(\.surface)))
                .padding(.horizontal, 16).padding(.bottom, 8)
            }

            List(selection: $model.selectedTaskID) {
                ForEach(open) { rec in card(rec) }
                    .onMove { offsets, dest in model.reorder(from: offsets, to: dest) }
                if !done.isEmpty {
                    Button { showDone.toggle() } label: {
                        Label("Erledigt  \(done.count)", systemImage: showDone ? "chevron.down" : "chevron.right")
                            .font(.callout.weight(.semibold)).foregroundStyle(.white)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(Capsule().fill(.black.opacity(0.2)))
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    if showDone { ForEach(done) { rec in card(rec) } }
                }
            }
            .scrollContentBackground(.hidden)
            .listStyle(.plain)
        }
        .background(accent)
        .navigationTitle(model.selectionTitle)
        .searchable(text: $model.searchText, prompt: "Suchen")
        .toolbar {
            ToolbarItem { SortMenu() }
            ToolbarItem { PDFExportButton() }
        }
    }

    private func card(_ rec: TaskRecord) -> some View {
        TodoCard(rec: rec)
            .tag(rec.id)
            .draggable("\(rec.listHref)\n\(rec.item.uid)")
            .contextMenu { TaskContextMenu(rec: rec) }
            .listRowBackground(RoundedRectangle(cornerRadius: 8).fill(theme.color(\.surface)).padding(.vertical, 2).padding(.horizontal, 8))
            .listRowSeparator(.hidden)
    }
}

/// Eine Aufgabe als To-Do-Karte: Kreis, Titel, Unterzeile (Mein Tag · Liste · Fälligkeit · Schritte · Symbole), Stern.
struct TodoCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let rec: TaskRecord

    var body: some View {
        let item = rec.item
        let steps = model.children(of: rec)
        HStack(spacing: 10) {
            Button { model.toggleDone(rec) } label: {
                CheckCircle(done: item.isCompleted,
                            tint: item.color.map { Color(hex: ThemeCatalog.displayHex(stored: $0, in: theme)) }
                                ?? (item.isCompleted ? theme.color(\.success) : theme.color(\.textSecondary)),
                            size: 20)
                    .font(.title3)
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title.isEmpty ? "Ohne Titel" : item.title)
                    .strikethrough(item.isCompleted)
                    .foregroundStyle(item.isCompleted ? theme.color(\.textSecondary) : theme.color(\.textPrimary))
                    .lineLimit(2)
                let parts = subtitle(steps: steps)
                if !parts.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                            if i > 0 { Text("·") }
                            Text(p.text).foregroundStyle(p.alert ? theme.color(\.danger) : theme.color(\.textSecondary))
                        }
                        if !item.reminders.isEmpty { Image(systemName: "bell") }
                        if item.recurrence != nil { Image(systemName: "repeat") }
                        if !item.notes.isEmpty { Image(systemName: "note.text") }
                        if rec.isDirty && model.account != nil { Image(systemName: "arrow.up.circle") }
                    }
                    .font(.caption).foregroundStyle(theme.color(\.textSecondary)).lineLimit(1)
                }
            }
            Spacer()
            Button { model.toggleImportant(rec) } label: {
                let on = (1...4).contains(item.priority)
                Image(systemName: on ? "star.fill" : "star").foregroundStyle(on ? theme.color(\.accent) : theme.color(\.textSecondary))
            }
            .buttonStyle(.plain)
            .help("Wichtig")
        }
        .padding(.vertical, 8).padding(.horizontal, 6)
    }

    private func subtitle(steps: [TaskRecord]) -> [(text: String, alert: Bool)] {
        var out: [(String, Bool)] = []
        if model.isInMyDay(rec) { out.append(("☀︎ Mein Tag", false)) }
        if model.selection?.isSmart == true, let l = model.list(rec.listHref) { out.append((l.name, false)) }
        if let due = rec.item.due {
            let d = due.date()
            let overdue = !rec.item.isCompleted && d < Date() && !(due.isAllDay && Calendar.current.isDateInToday(d))
            out.append((due.isAllDay ? d.formatted(date: .abbreviated, time: .omitted) : d.formatted(date: .abbreviated, time: .shortened), overdue))
        }
        if !steps.isEmpty { out.append(("\(steps.filter(\.item.isCompleted).count) von \(steps.count)", false)) }
        if !rec.item.tags.isEmpty { out.append((rec.item.tags.joined(separator: ", "), false)) }
        return out.map { (text: $0.0, alert: $0.1) }
    }
}

/// Kontextmenü einer Aufgabe (beide Darstellungen).
struct TaskContextMenu: View {
    @Environment(AppModel.self) private var model
    let rec: TaskRecord
    var body: some View {
        Button(rec.item.isCompleted ? "Wieder öffnen" : "Erledigt") { model.toggleDone(rec) }
        Button((1...4).contains(rec.item.priority) ? "Nicht mehr wichtig" : "Als wichtig markieren") { model.toggleImportant(rec) }
        Button(model.isInMyDay(rec) ? "Aus „Mein Tag“ entfernen" : "Zu „Mein Tag“ hinzufügen") { model.toggleMyDay(rec.item.uid) }
        Menu("Verschieben nach …") {
            ForEach(model.lists.filter { $0.href != rec.listHref && $0.canWrite }) { l in
                Button(l.name) { model.move(rec, to: l.href) }
            }
        }
        Divider()
        Button("Löschen", role: .destructive) { model.delete(rec) }
    }
}
