import SwiftUI
import ListwerkCore

struct SidebarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.colorScheme) private var scheme
    @State private var creating = false
    @State private var newName = ""
    @State private var renaming: TaskList?
    @State private var renameText = ""

    var body: some View {
        @Bindable var model = model
        List(selection: $model.selection) {
            ForEach(SmartListInfo.all) { s in
                HStack {
                    Label { Text(s.selection == .all ? L("Alle") : L(s.name)) } icon: { Image(systemName: s.icon).foregroundStyle(s.tint) }
                    Spacer()
                    let n = model.smartCount(s.selection)
                    if n > 0 && s.selection != .completed { Text("\(n)").font(.caption).foregroundStyle(theme.color(\.textSecondary)) }
                }
                .tag(s.selection)
            }
            Section(L("Listen")) {
                ForEach(model.lists) { list in
                    row(list).tag(SidebarSelection.list(list.href))
                        .dropDestination(for: String.self) { items, _ in
                            guard list.canWrite else { return false }
                            for s in items { handleDrop(s, onto: list) }
                            return true
                        }
                        .contextMenu {
                            Button(L("Umbenennen …")) { renaming = list; renameText = list.name }
                                .disabled(!list.canWrite)
                            Button(L("Nach oben")) { model.moveList(list, by: -1) }.disabled(list.href == model.lists.first?.href)
                            Button(L("Nach unten")) { model.moveList(list, by: 1) }.disabled(list.href == model.lists.last?.href)
                            Button(model.favoriteList == list.href ? L("Favorit entfernen") : L("Als Favorit festlegen")) { model.toggleFavorite(list) }
                            Divider()
                            Button(L("Löschen"), role: .destructive) { model.deleteList(list) }
                        }
                }
                .onMove { offsets, dest in model.moveLists(from: offsets, to: dest) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(theme.color(\.sidebar))
        .navigationTitle("ToDo-Listwerk")
        .toolbar {
            ToolbarItem {
                Button { creating = true } label: { Label(L("Neue Liste"), systemImage: "plus") }
            }
            if model.account != nil {
                ToolbarItem {
                    Button { Task { await model.syncNow() } } label: {
                        if model.isSyncing { ProgressView() } else { Label(L("Abgleichen"), systemImage: "arrow.triangle.2.circlepath") }
                    }
                }
            }
        }
        .alert(L("Neue Liste"), isPresented: $creating) {
            TextField(L("Name"), text: $newName)
            Button(L("Anlegen")) { model.createList(name: newName, color: nil); newName = "" }
            Button(L("Abbrechen"), role: .cancel) { newName = "" }
        }
        .alert(L("Liste umbenennen"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L("Name"), text: $renameText)
            Button(L("Speichern")) { if let l = renaming { model.renameList(l, name: renameText, color: l.color) } }
            Button(L("Abbrechen"), role: .cancel) {}
        }
        .safeAreaInset(edge: .bottom) {
            if let s = model.statusMessage {
                Text(s).font(.caption).padding(6).frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(theme.color(\.textSecondary))
            }
        }
    }

    private func row(_ list: TaskList) -> some View {
        HStack {
            Circle().fill(Color(hex: ThemeCatalog.displayHex(stored: list.color ?? theme.colors.accent, in: theme)))
                .frame(width: 10, height: 10)
            Text(list.name)
            if model.favoriteList == list.href { Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow).accessibilityLabel(L("Favorit")) }
            if list.isShared { Image(systemName: "person.2").font(.caption) }
            if list.isPlanwerkLinked { Image(systemName: "link").font(.caption) }
            if !list.canWrite { Image(systemName: "lock").font(.caption) }
            Spacer()
            Text("\(model.openCount(list))").font(.caption).foregroundStyle(theme.color(\.textSecondary))
        }
    }

    /// Dragging liefert "<listHref>\n<uid>".
    private func handleDrop(_ payload: String, onto list: TaskList) {
        let parts = payload.components(separatedBy: "\n")
        guard parts.count == 2, parts[0] != list.href,
              let rec = (try? model.store.record(uid: parts[1], inList: parts[0])) ?? nil else { return }
        model.move(rec, to: list.href)
    }
}
