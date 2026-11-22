import SwiftUI
import ListwerkCore

/// Schnelleingabe mit Listenwahl und Vorschau dessen, was erkannt wurde (Menüleiste und globales Kürzel).
struct QuickEntryView: View {
    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var target: String?
    @FocusState private var focused: Bool
    /// Nach dem Anlegen (z. B. Fenster schließen).
    var onDone: () -> Void = {}

    private var lists: [TaskList] { model.lists.filter(\.canWrite) }
    private var listHref: String? { target ?? model.entryTarget?.href ?? lists.first?.href }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(L("Neue Aufgabe, z. B. „Elternbrief morgen 8 Uhr !“"), text: $text)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(add)
            HStack {
                Picker(L("Liste"), selection: Binding(get: { listHref ?? "" }, set: { target = $0 })) {
                    ForEach(lists) { Text($0.name).tag($0.href) }
                }
                .labelsHidden()
                .fixedSize()
                Spacer()
                Text(preview).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .id(model.language)
        .onAppear { focused = true }
        #if os(macOS)
        .onExitCommand(perform: onDone)
        #endif
    }

    private var preview: String {
        guard let item = AppModel.quickItem(text) else { return "" }
        var parts: [String] = []
        if let d = item.due { parts.append(d.isAllDay ? d.date().lwFormatted(date: .abbreviated, time: .omitted) : d.date().lwFormatted(date: .abbreviated, time: .shortened)) }
        if let r = item.recurrence { parts.append("↻ " + (Recurrence.Rule(r)?.text ?? r)) }
        if item.priority > 0 { parts.append(L("wichtig")) }
        return parts.joined(separator: " · ")
    }

    private func add() {
        guard let href = listHref, AppModel.quickItem(text) != nil else { return }
        model.quickAdd(text, to: href)
        text = ""
        onDone()
    }
}
