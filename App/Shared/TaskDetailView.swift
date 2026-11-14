import SwiftUI
import ListwerkCore

struct TaskDetailPane: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let id = model.selectedTaskID,
           let rec = model.records.first(where: { $0.id == id }) {
            TaskDetailView(rec: rec).id(rec.id)
        } else {
            ContentUnavailableView("Kein Eintrag gewählt", systemImage: "checklist")
        }
    }
}

struct TaskDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let rec: TaskRecord
    @State private var draft: TodoItem
    @State private var newSubtask = ""

    init(rec: TaskRecord) {
        self.rec = rec
        _draft = State(initialValue: rec.item)
    }

    private var list: TaskList? { model.list(rec.listHref) }
    private var editable: Bool { list?.canWrite ?? false }
    private var link: PlanwerkLink? { model.planwerkLinks[rec.listHref] }

    var body: some View {
        Form {
            Section {
                TextField("Titel", text: $draft.title)
                TextField("Link", text: Binding(get: { draft.url ?? "" }, set: { draft.url = $0.isEmpty ? nil : $0 }))
                    .autocorrectionDisabled()
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $draft.notes).frame(minHeight: 100)
                    if draft.notes.isEmpty { Text("Notizen").foregroundStyle(theme.color(\.textSecondary)).padding(.top, 8).padding(.leading, 5).allowsHitTesting(false) }
                }
            }

            Section("Termin") {
                Toggle("Fällig", isOn: Binding(
                    get: { draft.due != nil },
                    set: { on in draft.due = on ? ICalDate(date: Date().addingTimeInterval(86_400), allDay: true) : nil }
                ))
                if let due = draft.due {
                    Toggle("Ganztägig", isOn: Binding(
                        get: { due.isAllDay },
                        set: { allDay in draft.due = ICalDate(date: due.date(), allDay: allDay) }
                    ))
                    DatePicker("Datum", selection: Binding(
                        get: { due.date() },
                        set: { draft.due = ICalDate(date: $0, allDay: due.isAllDay) }
                    ), displayedComponents: due.isAllDay ? [.date] : [.date, .hourAndMinute])
                }
                remindersSection
            }

            Section("Eigenschaften") {
                Picker("Priorität", selection: Binding(
                    get: { TodoPriority(icalValue: draft.priority) },
                    set: { draft.priority = $0.rawValue }
                )) {
                    Text("Keine").tag(TodoPriority.none)
                    Text("Hoch").tag(TodoPriority.high)
                    Text("Mittel").tag(TodoPriority.medium)
                    Text("Niedrig").tag(TodoPriority.low)
                }
                Picker("Liste", selection: Binding(get: { rec.listHref }, set: { model.move(rec, to: $0) })) {
                    ForEach(model.lists.filter { $0.canWrite || $0.href == rec.listHref }) { Text($0.name).tag($0.href) }
                }
                if list?.isPlanwerkLinked == true { planwerkSection } else {
                    TextField("Schlagwörter (Komma getrennt)", text: Binding(
                        get: { draft.tags.joined(separator: ", ") },
                        set: { draft.tags = $0.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
                    ))
                }
            }

            Section("Farbe") { colorPicker }

            Section("Unteraufgaben") {
                ForEach(model.children(of: rec.item.uid)) { TaskRow(rec: $0) }
                HStack {
                    TextField("Unteraufgabe hinzufügen", text: $newSubtask)
                        .onSubmit {
                            guard editable, !newSubtask.isEmpty else { return }
                            addSubtask()
                        }
                }
            }

            if !draft.attachments.isEmpty {
                Section("Anhänge") {
                    ForEach(draft.attachments) { a in
                        Label(a.filename ?? a.uri, systemImage: "paperclip")
                    }
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(theme.color(\.background))
        .disabled(!editable)
        .navigationTitle(draft.title.isEmpty ? "Eintrag" : draft.title)
        .onChange(of: draft) { _, new in model.save(new, in: rec.listHref) }
    }

    // MARK: Teile

    private var remindersSection: some View {
        Group {
            ForEach(Array(draft.reminders.enumerated()), id: \.element.id) { idx, r in
                HStack {
                    Image(systemName: "bell")
                    Text(describe(r))
                    Spacer()
                    Button(role: .destructive) { draft.reminders.remove(at: idx) } label: { Image(systemName: "minus.circle") }
                        .buttonStyle(.plain)
                }
            }
            Menu("Erinnerung hinzufügen") {
                Button("Zum Fälligkeitszeitpunkt") { add(.relative(0, toEnd: true)) }.disabled(draft.due == nil)
                Button("15 Minuten vorher") { add(.relative(-900, toEnd: true)) }.disabled(draft.due == nil)
                Button("1 Stunde vorher") { add(.relative(-3600, toEnd: true)) }.disabled(draft.due == nil)
                Button("1 Tag vorher") { add(.relative(-86_400, toEnd: true)) }.disabled(draft.due == nil)
                Button("In einer Stunde") { add(.absolute(Date().addingTimeInterval(3600))) }
            }
        }
    }

    private func add(_ t: Reminder.Trigger) { draft.reminders.append(Reminder(trigger: t)) }

    private func describe(_ r: Reminder) -> String {
        switch r.trigger {
        case .absolute(let d): return d.formatted(date: .abbreviated, time: .shortened)
        case .relative(let s, _):
            if s == 0 { return "Zum Fälligkeitszeitpunkt" }
            let m = Int(abs(s) / 60)
            let text = m % 1440 == 0 ? "\(m / 1440) Tag(e)" : (m % 60 == 0 ? "\(m / 60) Std." : "\(m) Min.")
            return s < 0 ? "\(text) vorher" : "\(text) danach"
        }
    }

    private var planwerkSection: some View {
        Group {
            Picker("Art", selection: Binding(
                get: { draft.planwerk?.kind ?? .task },
                set: { var p = draft.planwerk ?? PlanwerkMeta(); p.kind = $0; draft.planwerk = p }
            )) {
                ForEach(PlanwerkKind.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
            }
            if let link {
                Picker("Gruppe", selection: Binding(
                    get: { draft.planwerk?.group ?? "" },
                    set: { var p = draft.planwerk ?? PlanwerkMeta(); p.group = $0.isEmpty ? nil : $0; draft.planwerk = p }
                )) {
                    Text("Keine").tag("")
                    ForEach(link.groups, id: \.self) { Text($0).tag($0) }
                }
                Picker("Zugewiesen", selection: Binding(
                    get: { draft.planwerk?.assignee ?? "" },
                    set: { var p = draft.planwerk ?? PlanwerkMeta(); p.assignee = $0.isEmpty ? nil : $0; draft.planwerk = p }
                )) {
                    Text("Niemand").tag("")
                    ForEach(link.people, id: \.self) { Text($0).tag($0) }
                }
            }
        }
    }

    private var colorPicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(ThemeCatalog.palettes) { palette in
                Text(palette.name).font(.caption).foregroundStyle(theme.color(\.textSecondary))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 30), spacing: 8)], spacing: 8) {
                    ForEach(palette.colors) { c in
                        let selected = draft.color?.lowercased() == c.storedHex.lowercased()
                        Circle()
                            .fill(Color(hex: ThemeCatalog.displayHex(stored: c.storedHex, in: theme)))
                            .frame(width: 26, height: 26)
                            .overlay { if selected { Image(systemName: "checkmark").font(.caption.bold()).foregroundStyle(.white) } }
                            .onTapGesture { draft.color = c.storedHex }
                            .accessibilityLabel(c.name)
                    }
                }
            }
            Button("Farbe entfernen") { draft.color = nil }.disabled(draft.color == nil)
        }
    }

    private func addSubtask() {
        var child = TodoItem(title: newSubtask)
        child.parentUID = rec.item.uid
        model.save(child, in: rec.listHref)
        newSubtask = ""
    }
}
