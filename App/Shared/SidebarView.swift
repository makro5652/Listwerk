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
            Label("Alle offenen", systemImage: "tray.full").tag(SidebarSelection.all)
            Section("Listen") {
                ForEach(model.lists) { list in
                    row(list).tag(SidebarSelection.list(list.href))
                        .dropDestination(for: String.self) { items, _ in
                            guard list.canWrite else { return false }
                            for s in items { handleDrop(s, onto: list) }
                            return true
                        }
                        .contextMenu {
                            Button("Umbenennen …") { renaming = list; renameText = list.name }
                                .disabled(!list.canWrite)
                            Button("Löschen", role: .destructive) { model.deleteList(list) }
                        }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(theme.color(\.sidebar))
        .navigationTitle("Listwerk")
        .toolbar {
            ToolbarItem {
                Button { creating = true } label: { Label("Neue Liste", systemImage: "plus") }
            }
            ToolbarItem {
                Button { Task { await model.syncNow() } } label: {
                    if model.isSyncing { ProgressView() } else { Label("Abgleichen", systemImage: "arrow.triangle.2.circlepath") }
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
