import SwiftUI
import ListwerkCore

struct TaskDetailPane: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let id = model.selectedTaskID,
           let rec = model.records.first(where: { $0.id == id }) {
            if model.isPlanwerk(rec) { PlanwerkDetailView(rec: rec) } else { TaskDetailView(rec: rec).id(rec.id) }
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
    @State private var pickingFile = false
    @State private var uploading = false
    @Environment(\.openURL) private var openURL

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
                HStack {
                    TextField("Titel", text: $draft.title)
                    let important = (1...4).contains(draft.priority)
                    Button { draft.priority = important ? 0 : 1 } label: {
                        Image(systemName: important ? "star.fill" : "star").foregroundStyle(important ? theme.color(\.accent) : theme.color(\.textSecondary))
                    }
                    .buttonStyle(.plain).help("Wichtig")
                }
                Toggle(isOn: Binding(get: { model.isInMyDay(rec) }, set: { _ in model.toggleMyDay(rec.item.uid) })) {
                    Label("Mein Tag", systemImage: "sun.max")
                }
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
                Picker(selection: Binding(
                    get: { draft.recurrence ?? "" },
                    set: { rule in
                        draft.recurrence = rule.isEmpty ? nil : rule
                        // Wiederholung braucht einen Termin; wie in To Do: ab heute.
                        if !rule.isEmpty, draft.due == nil { draft.due = ICalDate(date: Date(), allDay: true) }
                    }
                )) {
                    Text("Nicht wiederholen").tag("")
                    Text("Täglich").tag(Recurrence.daily)
                    Text("Werktags").tag(Recurrence.weekdays)
                    Text("Wöchentlich").tag(Recurrence.weekly)
                    Text("Monatlich").tag(Recurrence.monthly)
                    Text("Jährlich").tag(Recurrence.yearly)
                    if draft.recurrence.map(Self.presets.contains) ?? true { Text("Benutzerdefiniert …").tag("FREQ=WEEKLY;INTERVAL=2") }
                    // Eigene oder fremde Regel (z. B. aus Thunderbird) bleibt sichtbar und erhalten.
                    if let r = draft.recurrence, !Self.presets.contains(r) {
                        Text(Recurrence.Rule(r)?.text ?? "Eigene Regel: \(r)").tag(r)
                    }
                } label: { Label("Wiederholen", systemImage: "repeat") }
                if let r = draft.recurrence, let rule = Recurrence.Rule(r) { ruleEditor(rule) }
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
                ForEach(model.children(of: rec)) { TaskRow(rec: $0) }
                HStack {
                    TextField("Unteraufgabe hinzufügen", text: $newSubtask)
                        .onSubmit {
                            guard editable, !newSubtask.isEmpty else { return }
                            addSubtask()
                        }
                }
            }

            attachmentsSection
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(ThemeBackground())
        .disabled(!editable)
        .navigationTitle(draft.title.isEmpty ? "Eintrag" : draft.title)
        .onChange(of: draft) { _, new in model.save(new, in: rec.listHref) }
    }

    // MARK: Teile

    private static let presets = [Recurrence.daily, Recurrence.weekdays, Recurrence.weekly, Recurrence.monthly, Recurrence.yearly]

    /// Intervall, Einheit, Wochentage und Ende frei einstellen.
    @ViewBuilder private func ruleEditor(_ rule: Recurrence.Rule) -> some View {
        let set: (Recurrence.Rule) -> Void = { draft.recurrence = $0.rrule }
        HStack {
            Stepper("Alle \(rule.interval)", value: Binding(get: { rule.interval }, set: { var r = rule; r.interval = max(1, $0); set(r) }), in: 1...99)
            Picker("", selection: Binding(get: { rule.freq }, set: { var r = rule; r.freq = $0; if $0 != .weekly { r.byDay = [] }; set(r) })) {
                Text(rule.interval == 1 ? "Tag" : "Tage").tag(Recurrence.Rule.Freq.daily)
                Text(rule.interval == 1 ? "Woche" : "Wochen").tag(Recurrence.Rule.Freq.weekly)
                Text(rule.interval == 1 ? "Monat" : "Monate").tag(Recurrence.Rule.Freq.monthly)
                Text(rule.interval == 1 ? "Jahr" : "Jahre").tag(Recurrence.Rule.Freq.yearly)
            }
            .labelsHidden().fixedSize()
        }
        if rule.freq == .weekly {
            HStack(spacing: 6) {
                ForEach(Array(zip(Recurrence.Rule.dayOrder, ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"])), id: \.0) { code, name in
                    let on = rule.byDay.contains(code)
                    Button(name) {
                        var r = rule
                        r.byDay = on ? r.byDay.filter { $0 != code } : r.byDay + [code]
                        set(r)
                    }
                    .buttonStyle(.bordered)
                    .tint(on ? theme.color(\.accent) : nil)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        Toggle("Endet", isOn: Binding(get: { rule.until != nil }, set: { var r = rule; r.until = $0 ? Calendar.current.date(byAdding: .month, value: 3, to: Date()) : nil; set(r) }))
        if let until = rule.until {
            DatePicker("Letzter Termin", selection: Binding(get: { until }, set: { var r = rule; r.until = $0; set(r) }), displayedComponents: .date)
        }
    }

    private var attachmentsSection: some View {
        Section("Anhänge") {
            ForEach(Array(draft.attachments.enumerated()), id: \.offset) { idx, a in
                HStack {
                    if let url = URL(string: a.uri), !a.isInline {
                        Button { openURL(url) } label: { Label(a.filename ?? a.uri, systemImage: "paperclip") }
                            .buttonStyle(.plain)
                            .help(a.uri)
                    } else {
                        Label(a.filename ?? "Eingebetteter Anhang", systemImage: "paperclip")
                    }
                    Spacer()
                    Button(role: .destructive) { draft.attachments.remove(at: idx) } label: { Image(systemName: "minus.circle") }
                        .buttonStyle(.plain).help("Anhang entfernen (die Datei selbst bleibt erhalten)")
                }
            }
            HStack {
                Button("Datei anhängen …") { pickingFile = true }.disabled(uploading)
                if uploading { ProgressView().controlSize(.small) }
            }
            .fileImporter(isPresented: $pickingFile, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                uploading = true
                Task {
                    for u in urls { await model.addAttachment(u, to: rec) }
                    // Den gespeicherten Stand übernehmen, sonst überschreibt der Entwurf die neuen Anhänge.
                    if let fresh = model.records.first(where: { $0.id == rec.id }) { draft.attachments = fresh.item.attachments }
                    uploading = false
                }
            }
            Text(model.account == nil ? "Ohne Konto bleibt die Datei auf diesem Gerät." : "Wird in deine Nextcloud hochgeladen (Ordner „Listwerk“).")
                .font(.footnote).foregroundStyle(theme.color(\.textSecondary))
        }
    }

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

/// Aufgabe aus dem Gantt-Plan: nur ansehen und abhaken; ändern in Planwerk.
struct PlanwerkDetailView: View {
    @Environment(AppModel.self) private var model
    let rec: TaskRecord

    var body: some View {
        let i = rec.item
        Form {
            Section {
                Text(i.title).font(.title3.bold())
                if let p = i.tags.first { LabeledContent("Projekt", value: p) }
                if let d = i.due { LabeledContent("Fällig", value: d.date().formatted(date: .complete, time: .omitted)) }
                if let k = i.planwerk?.kind, k != .task { LabeledContent("Art", value: k.categoryTag ?? "") }
                if let g = i.planwerk?.group { LabeledContent("Gruppe", value: g) }
                if let a = i.planwerk?.assignee { LabeledContent("Zuständig", value: a) }
                if !i.notes.isEmpty { Text(i.notes).textSelection(.enabled) }
            }
            Section {
                Button("In Planwerk als erledigt markieren") { model.toggleDone(rec) }
                Text("Aus Planwerk. Titel und Termin änderst du dort.").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(ThemeBackground())
        .navigationTitle(i.title)
    }
}
