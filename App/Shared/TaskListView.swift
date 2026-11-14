import SwiftUI
import ListwerkCore

struct TaskListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var newTitle = ""

    var body: some View {
        @Bindable var model = model
        List(selection: $model.selectedTaskID) {
            ForEach(model.topLevel) { rec in
                VStack(alignment: .leading, spacing: 0) {
                    TaskRow(rec: rec)
                    ForEach(model.children(of: rec.item.uid)) { child in
                        TaskRow(rec: child).padding(.leading, 28)
                    }
                }
                .tag(rec.id)
                .draggable("\(rec.listHref)\n\(rec.item.uid)")
                .contextMenu { menu(for: rec) }
                .swipeActions(edge: .trailing) {
                    Button("Löschen", role: .destructive) { model.delete(rec) }
                }
                .listRowBackground(theme.color(\.surface))
            }
            .onMove { offsets, dest in model.reorder(from: offsets, to: dest) }
        }
        .scrollContentBackground(.hidden)
        .background(theme.color(\.background))
        .navigationTitle(title)
        .toolbar {
            ToolbarItem {
                Toggle(isOn: $model.showCompleted) { Label("Erledigte zeigen", systemImage: "checkmark.circle") }
            }
        }
        .safeAreaInset(edge: .bottom) {
            HStack {
                Image(systemName: "plus.circle.fill").foregroundStyle(theme.color(\.accent))
                TextField(canAdd ? "Neuer Eintrag" : "Liste wählen, um Einträge anzulegen", text: $newTitle)
                    .textFieldStyle(.plain)
                    .disabled(!canAdd)
                    .onSubmit {
                        model.addTask(title: newTitle)
                        newTitle = ""
                    }
            }
            .padding(10)
            .background(theme.color(\.surfaceElevated))
        }
    }

    private var title: String { model.currentList?.name ?? "Alle offenen" }
    private var canAdd: Bool { model.currentList?.canWrite == true }

    @ViewBuilder
    private func menu(for rec: TaskRecord) -> some View {
        Button(rec.item.isCompleted ? "Wieder öffnen" : "Erledigt") { model.toggleDone(rec) }
        Menu("Verschieben nach …") {
            ForEach(model.lists.filter { $0.href != rec.listHref && $0.canWrite }) { l in
                Button(l.name) { model.move(rec, to: l.href) }
            }
        }
        Button("Löschen", role: .destructive) { model.delete(rec) }
    }
}

struct TaskRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let rec: TaskRecord

    var body: some View {
        HStack(spacing: 10) {
            if let c = rec.item.color {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color(hex: ThemeCatalog.displayHex(stored: c, in: theme)))
                    .frame(width: 4, height: 28)
            }
            Button { model.toggleDone(rec) } label: {
                Image(systemName: rec.item.isCompleted ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(rec.item.isCompleted ? theme.color(\.success) : theme.color(\.textSecondary))
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 2) {
                Text(rec.item.title.isEmpty ? "Ohne Titel" : rec.item.title)
                    .strikethrough(rec.item.isCompleted)
                    .foregroundStyle(rec.item.isCompleted ? theme.color(\.textSecondary) : theme.color(\.textPrimary))
                if let due = rec.item.due {
                    Text(dueText(due))
                        .font(.caption)
                        .foregroundStyle(isOverdue(due) && !rec.item.isCompleted ? theme.color(\.danger) : theme.color(\.textSecondary))
                }
            }
            Spacer()
            if rec.item.priority >= 1 && rec.item.priority <= 4 { Image(systemName: "exclamationmark").foregroundStyle(theme.color(\.warning)) }
            if !rec.item.reminders.isEmpty { Image(systemName: "bell").font(.caption).foregroundStyle(theme.color(\.textSecondary)) }
            if rec.isDirty { Image(systemName: "arrow.up.circle").font(.caption).foregroundStyle(theme.color(\.textSecondary)) }
        }
        .padding(.vertical, 4)
    }

    private func isOverdue(_ d: ICalDate) -> Bool { d.date() < Date() && !(d.isAllDay && Calendar.current.isDateInToday(d.date())) }

    private func dueText(_ d: ICalDate) -> String {
        let date = d.date()
        return d.isAllDay
            ? date.formatted(date: .abbreviated, time: .omitted)
            : date.formatted(date: .abbreviated, time: .shortened)
    }
}
