import Foundation

/// Ein iCalendar-Dokument mit genau einem Haupt-VTODO.
///
/// Grundprinzip (Spezifikation, Abschnitt 3): Das Dokument hält alle Zeilen, auch die unbekannten.
/// `apply(_:)` ändert nur die Properties, deren Wert sich gegenüber dem aktuellen Stand geändert hat.
/// Alles andere, etwa fremde `X-`-Properties, Zeitzonendefinitionen, Alarme anderer Art und weitere
/// Komponenten, bleibt unverändert erhalten.
public struct ICalDocument: Sendable {
    enum Item: Equatable, Sendable {
        case prop(ContentLine)
        case component(name: String, lines: [String])
        case raw(String)
    }

    static let prodID = "-//Listwerk//DE"

    var head: [String]
    var items: [Item]
    var tail: [String]
    private var pristine: String?
    private(set) var isModified: Bool

    // MARK: Erzeugen

    /// Liest das erste VTODO. Nil, wenn der Text kein VTODO enthält.
    public init?(parsing text: String) {
        let lines = ICalText.unfold(text)
        guard let begin = lines.firstIndex(where: { $0.uppercased() == "BEGIN:VTODO" }) else { return nil }
        guard let end = ICalDocument.matchingEnd(in: lines, from: begin) else { return nil }

        var items: [Item] = []
        var i = begin + 1
        while i < end {
            let line = lines[i]
            if line.uppercased().hasPrefix("BEGIN:") {
                let name = String(line.dropFirst(6)).uppercased()
                let close = ICalDocument.matchingEnd(in: lines, from: i) ?? i
                items.append(.component(name: name, lines: Array(lines[i...close])))
                i = close + 1
                continue
            }
            if let cl = ContentLine(parsing: line) {
                items.append(.prop(cl))
            } else if !line.isEmpty {
                items.append(.raw(line))
            }
            i += 1
        }
        self.head = Array(lines[..<begin])
        self.items = items
        self.tail = end + 1 < lines.count ? Array(lines[(end + 1)...]) : []
        self.pristine = text
        self.isModified = false
    }

    /// Leeres Dokument für einen neuen Eintrag. Mit `apply(_:)` füllen.
    public init(newWithUID uid: String) {
        self.head = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:" + ICalDocument.prodID]
        self.items = [.prop(ContentLine(name: "UID", value: uid))]
        self.tail = ["END:VCALENDAR"]
        self.pristine = nil
        self.isModified = true
    }

    private static func matchingEnd(in lines: [String], from begin: Int) -> Int? {
        var depth = 0
        for i in begin..<lines.count {
            let u = lines[i].uppercased()
            if u.hasPrefix("BEGIN:") {
                depth += 1
            } else if u.hasPrefix("END:") {
                depth -= 1
                if depth == 0 { return i }
            }
        }
        return nil
    }

    // MARK: Ausgeben

    public func serialized() -> String {
        if !isModified, let pristine { return pristine }
        var out = ""
        for l in head { out += ICalText.fold(l) }
        out += ICalText.fold("BEGIN:VTODO")
        for it in items {
            switch it {
            case .prop(let p): out += ICalText.fold(p.unfolded)
            case .component(_, let lines): for l in lines { out += ICalText.fold(l) }
            case .raw(let l): out += ICalText.fold(l)
            }
        }
        out += ICalText.fold("END:VTODO")
        for l in tail { out += ICalText.fold(l) }
        return out
    }

    // MARK: Zugriff auf Properties

    func props(_ name: String) -> [ContentLine] {
        items.compactMap { if case .prop(let p) = $0, p.name == name { return p } else { return nil } }
    }

    func prop(_ name: String) -> ContentLine? { props(name).first }

    func text(_ name: String) -> String? { prop(name).map { ICalText.unescape($0.value) } }

    func date(_ name: String) -> ICalDate? {
        guard let p = prop(name) else { return nil }
        return ICalDate(value: p.value, params: p.params)
    }

    func components(named name: String) -> [[String]] {
        items.compactMap { if case .component(let n, let l) = $0, n == name { return l } else { return nil } }
    }

    /// Ersetzt alle passenden Properties durch `lines`. Ändert nichts, wenn der Inhalt schon gleich ist.
    mutating func replace(_ name: String, with lines: [ContentLine], where match: ((ContentLine) -> Bool)? = nil) {
        var indices: [Int] = []
        var existing: [ContentLine] = []
        for (i, it) in items.enumerated() {
            if case .prop(let p) = it, p.name == name, match?(p) ?? true {
                indices.append(i)
                existing.append(p)
            }
        }
        if existing == lines { return }
        let insertAt = indices.first ?? items.count
        for i in indices.reversed() { items.remove(at: i) }
        items.insert(contentsOf: lines.map { Item.prop($0) }, at: min(insertAt, items.count))
    }

    mutating func setText(_ name: String, _ value: String?) {
        if let v = value, !v.isEmpty {
            replace(name, with: [ContentLine(name: name, value: ICalText.escape(v))])
        } else {
            replace(name, with: [])
        }
    }

    mutating func setDate(_ name: String, _ value: ICalDate?) {
        replace(name, with: value.map { [$0.line(named: name)] } ?? [])
    }

    // MARK: Lesen

    /// Das Modell zum aktuellen Stand des Dokuments.
    public func makeItem() -> TodoItem {
        var item = TodoItem(uid: prop("UID")?.value ?? "")
        item.title = text("SUMMARY") ?? ""
        item.notes = text("DESCRIPTION") ?? ""
        item.url = prop("URL")?.value
        item.start = date("DTSTART")
        item.due = date("DUE")

        let status = prop("STATUS")?.value.uppercased()
        let completedDate = prop("COMPLETED").flatMap { ICalStamp.parse($0.value) }
        item.isCompleted = status == "COMPLETED" || completedDate != nil
        item.completedAt = completedDate
        item.percentComplete = prop("PERCENT-COMPLETE").flatMap { Int($0.value.trimmingCharacters(in: .whitespaces)) }
            ?? (item.isCompleted ? 100 : 0)
        item.priority = prop("PRIORITY").flatMap { Int($0.value.trimmingCharacters(in: .whitespaces)) } ?? 0

        let categories = rawCategories()
        item.planwerk = planwerkMeta()
        if let meta = item.planwerk {
            var derived: Set<String> = []
            if let g = meta.group { derived.insert(g) }
            if let k = meta.kind.categoryTag { derived.insert(k) }
            item.tags = categories.filter { !derived.contains($0) && !$0.hasPrefix("@") }
        } else {
            item.tags = categories
        }

        item.parentUID = props("RELATED-TO").first(where: Self.isParentRelation)?.value
        item.sortOrder = prop("X-APPLE-SORT-ORDER").flatMap { Int($0.value.trimmingCharacters(in: .whitespaces)) }
        item.color = prop("X-LISTWERK-COLOR")?.value
        item.reminders = components(named: "VALARM").map(Self.parseAlarm).map { r in
            // Die App schreibt den Titel als Beschreibung, wenn keine eigene Nachricht gesetzt ist.
            var r = r
            if r.message == item.title || (item.title.isEmpty && r.message == "Erinnerung") { r.message = nil }
            return r
        }
        item.attachments = props("ATTACH").map(Self.parseAttachment)
        item.recurrence = prop("RRULE")?.value
        item.created = prop("CREATED").flatMap { ICalStamp.parse($0.value) }
        item.lastModified = prop("LAST-MODIFIED").flatMap { ICalStamp.parse($0.value) }
        return item
    }

    private func rawCategories() -> [String] {
        props("CATEGORIES").flatMap { ICalText.splitList($0.value) }
    }

    private func planwerkMeta() -> PlanwerkMeta? {
        let kind = prop("X-PLANWERK-KIND"), group = prop("X-PLANWERK-GROUP"), assignee = prop("X-PLANWERK-ASSIGNEE")
        guard kind != nil || group != nil || assignee != nil else { return nil }
        return PlanwerkMeta(
            kind: kind.flatMap { PlanwerkKind(rawValue: $0.value.lowercased()) } ?? .task,
            group: group.map { ICalText.unescape($0.value) },
            assignee: assignee.map { ICalText.unescape($0.value) }
        )
    }

    private static func isParentRelation(_ p: ContentLine) -> Bool {
        let t = p.param("RELTYPE")?.uppercased()
        return t == nil || t == "PARENT"
    }

    private static func parseAlarm(_ lines: [String]) -> Reminder {
        var action = "DISPLAY"
        var message: String?
        var trigger: Reminder.Trigger = .relative(0, toEnd: false)
        for l in lines.dropFirst().dropLast() {
            guard let cl = ContentLine(parsing: l) else { continue }
            switch cl.name {
            case "ACTION": action = cl.value.uppercased()
            case "DESCRIPTION": message = ICalText.unescape(cl.value)
            case "TRIGGER":
                if cl.param("VALUE")?.uppercased() == "DATE-TIME" {
                    if let d = ICalStamp.parse(cl.value) { trigger = .absolute(d) }
                } else if let secs = ICalDuration.parse(cl.value) {
                    trigger = .relative(secs, toEnd: cl.param("RELATED")?.uppercased() == "END")
                }
            default: break
            }
        }
        var r = Reminder(trigger: trigger, message: message, action: action)
        r.rawLines = lines
        return r
    }

    private static func parseAttachment(_ p: ContentLine) -> AttachmentRef {
        var a = AttachmentRef(uri: p.value, mimeType: p.param("FMTTYPE"), filename: p.param("FILENAME"))
        if p.param("ENCODING")?.uppercased() == "BASE64" || p.param("VALUE")?.uppercased() == "BINARY" {
            a.isInline = true
            a.uri = ""
        }
        a.raw = p
        return a
    }

    // MARK: Schreiben

    /// Überträgt das Modell ins Dokument. Nur geänderte Felder werden angefasst. Bei einer Änderung
    /// werden `LAST-MODIFIED`, `DTSTAMP` und `SEQUENCE` fortgeschrieben.
    public mutating func apply(_ new: TodoItem, now: Date = Date()) {
        let cur = makeItem()
        let before = items

        if new.title != cur.title { setText("SUMMARY", new.title) }
        if new.notes != cur.notes { setText("DESCRIPTION", new.notes) }
        if new.url != cur.url {
            replace("URL", with: new.url.map { [ContentLine(name: "URL", value: $0)] } ?? [])
        }
        if new.start != cur.start { setDate("DTSTART", new.start) }
        if new.due != cur.due { setDate("DUE", new.due) }

        if new.isCompleted != cur.isCompleted || new.percentComplete != cur.percentComplete {
            if new.isCompleted {
                replace("STATUS", with: [ContentLine(name: "STATUS", value: "COMPLETED")])
                let stamp = new.completedAt ?? cur.completedAt ?? now
                replace("COMPLETED", with: [ContentLine(name: "COMPLETED", value: ICalStamp.string(stamp))])
                replace("PERCENT-COMPLETE", with: [ContentLine(name: "PERCENT-COMPLETE", value: "100")])
            } else {
                let status = new.percentComplete > 0 ? "IN-PROCESS" : "NEEDS-ACTION"
                replace("STATUS", with: [ContentLine(name: "STATUS", value: status)])
                replace("COMPLETED", with: [])
                replace("PERCENT-COMPLETE",
                        with: new.percentComplete > 0 ? [ContentLine(name: "PERCENT-COMPLETE", value: String(new.percentComplete))] : [])
            }
        }

        if new.priority != cur.priority {
            replace("PRIORITY", with: new.priority > 0 ? [ContentLine(name: "PRIORITY", value: String(new.priority))] : [])
        }

        applyCategories(new, current: cur)

        if new.parentUID != cur.parentUID {
            replace("RELATED-TO",
                    with: new.parentUID.map { [ContentLine(name: "RELATED-TO", params: [ICalParam(name: "RELTYPE", value: "PARENT")], value: $0)] } ?? [],
                    where: Self.isParentRelation)
            // replace() setzt Ersatzzeilen an die Stelle der alten; andere RELATED-TO (CHILD, SIBLING) bleiben stehen.
        }

        if new.sortOrder != cur.sortOrder {
            replace("X-APPLE-SORT-ORDER", with: new.sortOrder.map { [ContentLine(name: "X-APPLE-SORT-ORDER", value: String($0))] } ?? [])
        }

        if new.color != cur.color {
            replace("X-LISTWERK-COLOR", with: new.color.map { [ContentLine(name: "X-LISTWERK-COLOR", value: $0)] } ?? [])
            replace("COLOR", with: new.color.flatMap(CSSColorNames.nearestName(forHex:)).map { [ContentLine(name: "COLOR", value: $0)] } ?? [])
        }

        if new.planwerk != cur.planwerk {
            let m = new.planwerk
            replace("X-PLANWERK-KIND", with: m.map { [ContentLine(name: "X-PLANWERK-KIND", value: $0.kind.rawValue)] } ?? [])
            replace("X-PLANWERK-GROUP", with: m?.group.map { [ContentLine(name: "X-PLANWERK-GROUP", value: ICalText.escape($0))] } ?? [])
            replace("X-PLANWERK-ASSIGNEE", with: m?.assignee.map { [ContentLine(name: "X-PLANWERK-ASSIGNEE", value: ICalText.escape($0))] } ?? [])
        }

        if new.reminders != cur.reminders { rebuildAlarms(new, current: cur) }
        if new.attachments != cur.attachments { rebuildAttachments(new, current: cur) }
        if new.recurrence != cur.recurrence {
            replace("RRULE", with: new.recurrence.map { [ContentLine(name: "RRULE", value: $0)] } ?? [])
        }

        if items != before {
            isModified = true
            let stamp = ICalStamp.string(now)
            replace("LAST-MODIFIED", with: [ContentLine(name: "LAST-MODIFIED", value: stamp)])
            replace("DTSTAMP", with: [ContentLine(name: "DTSTAMP", value: stamp)])
            let seq = prop("SEQUENCE").flatMap { Int($0.value) } ?? -1
            replace("SEQUENCE", with: [ContentLine(name: "SEQUENCE", value: String(seq + 1))])
            if prop("CREATED") == nil {
                replace("CREATED", with: [ContentLine(name: "CREATED", value: stamp)])
            }
        }
    }

    private mutating func applyCategories(_ new: TodoItem, current cur: TodoItem) {
        let existing = rawCategories()
        let desired: [String]
        if let meta = new.planwerk {
            // Planwerk liest die Kategorien als Gruppe, Art und @Person. Freie Tags sind hier nicht möglich.
            var d: [String] = []
            if let g = meta.group { d.append(g) }
            if let k = meta.kind.categoryTag { d.append(k) }
            if let a = meta.assignee { d.append("@" + a) }
            desired = d
        } else {
            desired = new.tags
        }
        if Set(existing) == Set(desired) && existing.count == desired.count { return }
        let line = desired.isEmpty ? [] : [ContentLine(name: "CATEGORIES", value: desired.map(ICalText.escape).joined(separator: ","))]
        replace("CATEGORIES", with: line)
    }

    private mutating func rebuildAlarms(_ new: TodoItem, current cur: TodoItem) {
        var blocks: [[String]] = []
        for r in new.reminders {
            if let same = cur.reminders.first(where: { $0 == r }), let raw = same.rawLines {
                blocks.append(raw)
            } else {
                blocks.append(Self.alarmLines(r, fallbackMessage: new.title))
            }
        }
        items.removeAll { if case .component(let n, _) = $0 { return n == "VALARM" } else { return false } }
        items.append(contentsOf: blocks.map { Item.component(name: "VALARM", lines: $0) })
    }

    static func alarmLines(_ r: Reminder, fallbackMessage: String) -> [String] {
        var lines = ["BEGIN:VALARM", "ACTION:" + r.action]
        lines.append("DESCRIPTION:" + ICalText.escape(r.message ?? (fallbackMessage.isEmpty ? "Erinnerung" : fallbackMessage)))
        switch r.trigger {
        case .absolute(let d):
            lines.append("TRIGGER;VALUE=DATE-TIME:" + ICalStamp.string(d))
        case .relative(let s, let toEnd):
            lines.append("TRIGGER" + (toEnd ? ";RELATED=END" : "") + ":" + ICalDuration.format(s))
        }
        lines.append("END:VALARM")
        return lines
    }

    private mutating func rebuildAttachments(_ new: TodoItem, current cur: TodoItem) {
        var lines: [ContentLine] = []
        for a in new.attachments {
            if let same = cur.attachments.first(where: { $0 == a }), let raw = same.raw {
                lines.append(raw)
            } else {
                var params: [ICalParam] = []
                if let m = a.mimeType { params.append(ICalParam(name: "FMTTYPE", value: m)) }
                if let f = a.filename { params.append(ICalParam(name: "FILENAME", value: f.contains(where: { ":;,".contains($0) }) ? "\"\(f)\"" : f)) }
                lines.append(ContentLine(name: "ATTACH", params: params, value: a.uri))
            }
        }
        replace("ATTACH", with: lines)
    }
}

// MARK: - CSS-Farbnamen (RFC 7986, Property COLOR)

enum CSSColorNames {
    private static let table: [(String, (Int, Int, Int))] = [
        ("black", (0, 0, 0)), ("white", (255, 255, 255)), ("red", (255, 0, 0)), ("orange", (255, 165, 0)),
        ("yellow", (255, 255, 0)), ("green", (0, 128, 0)), ("lime", (0, 255, 0)), ("teal", (0, 128, 128)),
        ("cyan", (0, 255, 255)), ("blue", (0, 0, 255)), ("navy", (0, 0, 128)), ("purple", (128, 0, 128)),
        ("magenta", (255, 0, 255)), ("pink", (255, 192, 203)), ("brown", (165, 42, 42)), ("gray", (128, 128, 128)),
        ("silver", (192, 192, 192)), ("gold", (255, 215, 0)), ("coral", (255, 127, 80)), ("salmon", (250, 128, 114)),
        ("turquoise", (64, 224, 208)), ("violet", (238, 130, 238)), ("indigo", (75, 0, 130)), ("crimson", (220, 20, 60)),
        ("olive", (128, 128, 0)), ("maroon", (128, 0, 0)), ("skyblue", (135, 206, 235)), ("tomato", (255, 99, 71)),
    ]

    static func nearestName(forHex hex: String) -> String? {
        guard let (r, g, b) = rgb(hex) else { return nil }
        return table.min { a, b2 in dist(a.1, (r, g, b)) < dist(b2.1, (r, g, b)) }?.0
    }

    static func rgb(_ hex: String) -> (Int, Int, Int)? {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = Int(s, radix: 16) else { return nil }
        return ((v >> 16) & 255, (v >> 8) & 255, v & 255)
    }

    private static func dist(_ a: (Int, Int, Int), _ b: (Int, Int, Int)) -> Int {
        (a.0 - b.0) * (a.0 - b.0) + (a.1 - b.1) * (a.1 - b.1) + (a.2 - b.2) * (a.2 - b.2)
    }
}
