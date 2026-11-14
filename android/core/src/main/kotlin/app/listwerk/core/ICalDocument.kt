package app.listwerk.core

import java.time.Instant

/**
 * Ein iCalendar-Dokument mit genau einem Haupt-VTODO.
 *
 * Grundprinzip: Das Dokument hält alle Zeilen, auch die unbekannten. [apply] ändert nur die Properties,
 * deren Wert sich gegenüber dem aktuellen Stand geändert hat. Alles andere, etwa fremde `X-`-Properties,
 * Zeitzonendefinitionen, Alarme anderer Art und weitere Komponenten, bleibt unverändert erhalten.
 * Ein unverändertes Dokument wird byte-identisch zurückgegeben.
 */
class ICalDocument private constructor(
    internal var head: List<String>,
    internal val items: MutableList<Item>,
    internal var tail: List<String>,
    private var pristine: String?,
    isModified: Boolean,
) {
    sealed interface Item {
        data class Prop(val line: ContentLine) : Item
        data class Component(val name: String, val lines: List<String>) : Item
        data class Raw(val line: String) : Item
    }

    var isModified: Boolean = isModified
        private set

    companion object {
        const val PROD_ID = "-//Listwerk//DE"

        /** Liest das erste VTODO. Null, wenn der Text kein VTODO enthält. */
        fun parse(text: String): ICalDocument? {
            val lines = ICalText.unfold(text)
            val begin = lines.indexOfFirst { it.uppercase() == "BEGIN:VTODO" }
            if (begin < 0) return null
            val end = matchingEnd(lines, begin) ?: return null

            val items = ArrayList<Item>()
            var i = begin + 1
            while (i < end) {
                val line = lines[i]
                if (line.uppercase().startsWith("BEGIN:")) {
                    val name = line.substring(6).uppercase()
                    val close = matchingEnd(lines, i) ?: i
                    items.add(Item.Component(name, lines.subList(i, close + 1).toList()))
                    i = close + 1
                    continue
                }
                val cl = ContentLine.parse(line)
                if (cl != null) items.add(Item.Prop(cl)) else if (line.isNotEmpty()) items.add(Item.Raw(line))
                i++
            }
            return ICalDocument(
                head = lines.subList(0, begin).toList(),
                items = items,
                tail = if (end + 1 < lines.size) lines.subList(end + 1, lines.size).toList() else emptyList(),
                pristine = text,
                isModified = false,
            )
        }

        /** Leeres Dokument für einen neuen Eintrag. Mit [apply] füllen. */
        fun create(uid: String): ICalDocument = ICalDocument(
            head = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:$PROD_ID"),
            items = mutableListOf(Item.Prop(ContentLine("UID", value = uid))),
            tail = listOf("END:VCALENDAR"),
            pristine = null,
            isModified = true,
        )

        private fun matchingEnd(lines: List<String>, begin: Int): Int? {
            var depth = 0
            for (i in begin until lines.size) {
                val u = lines[i].uppercase()
                if (u.startsWith("BEGIN:")) depth++
                else if (u.startsWith("END:")) {
                    depth--
                    if (depth == 0) return i
                }
            }
            return null
        }

        private fun isParentRelation(p: ContentLine): Boolean {
            val t = p.param("RELTYPE")?.uppercase()
            return t == null || t == "PARENT"
        }

        private fun parseAlarm(lines: List<String>): Reminder {
            var action = "DISPLAY"
            var message: String? = null
            var trigger: ReminderTrigger = ReminderTrigger.Relative(0.0, false)
            for (l in lines.drop(1).dropLast(1)) {
                val cl = ContentLine.parse(l) ?: continue
                when (cl.name) {
                    "ACTION" -> action = cl.value.uppercase()
                    "DESCRIPTION" -> message = ICalText.unescape(cl.value)
                    "TRIGGER" ->
                        if (cl.param("VALUE")?.uppercase() == "DATE-TIME") {
                            ICalStamp.parse(cl.value)?.let { trigger = ReminderTrigger.Absolute(it) }
                        } else {
                            ICalDuration.parse(cl.value)?.let {
                                trigger = ReminderTrigger.Relative(it, cl.param("RELATED")?.uppercase() == "END")
                            }
                        }
                }
            }
            return Reminder(trigger, message, action, lines)
        }

        private fun parseAttachment(p: ContentLine): AttachmentRef {
            val inline = p.param("ENCODING")?.uppercase() == "BASE64" || p.param("VALUE")?.uppercase() == "BINARY"
            return AttachmentRef(
                uri = if (inline) "" else p.value,
                mimeType = p.param("FMTTYPE"),
                filename = p.param("FILENAME"),
                isInline = inline,
                raw = p,
            )
        }

        fun alarmLines(r: Reminder, fallbackMessage: String): List<String> {
            val lines = arrayListOf("BEGIN:VALARM", "ACTION:" + r.action)
            lines.add("DESCRIPTION:" + ICalText.escape(r.message ?: fallbackMessage.ifEmpty { "Erinnerung" }))
            when (val t = r.trigger) {
                is ReminderTrigger.Absolute -> lines.add("TRIGGER;VALUE=DATE-TIME:" + ICalStamp.string(t.at))
                is ReminderTrigger.Relative ->
                    lines.add("TRIGGER" + (if (t.toEnd) ";RELATED=END" else "") + ":" + ICalDuration.format(t.seconds))
            }
            lines.add("END:VALARM")
            return lines
        }
    }

    // MARK: Ausgeben

    fun serialized(): String {
        val p = pristine
        if (!isModified && p != null) return p
        val out = StringBuilder()
        for (l in head) out.append(ICalText.fold(l))
        out.append(ICalText.fold("BEGIN:VTODO"))
        for (it in items) {
            when (it) {
                is Item.Prop -> out.append(ICalText.fold(it.line.unfolded))
                is Item.Component -> for (l in it.lines) out.append(ICalText.fold(l))
                is Item.Raw -> out.append(ICalText.fold(it.line))
            }
        }
        out.append(ICalText.fold("END:VTODO"))
        for (l in tail) out.append(ICalText.fold(l))
        return out.toString()
    }

    // MARK: Zugriff auf Properties

    internal fun props(name: String): List<ContentLine> =
        items.mapNotNull { if (it is Item.Prop && it.line.name == name) it.line else null }

    internal fun prop(name: String): ContentLine? = props(name).firstOrNull()

    internal fun text(name: String): String? = prop(name)?.let { ICalText.unescape(it.value) }

    internal fun date(name: String): ICalDate? = prop(name)?.let { ICalDate.parse(it.value, it.params) }

    internal fun components(name: String): List<List<String>> =
        items.mapNotNull { if (it is Item.Component && it.name == name) it.lines else null }

    /** Ersetzt alle passenden Properties durch [lines]. Ändert nichts, wenn der Inhalt schon gleich ist. */
    internal fun replace(name: String, lines: List<ContentLine>, match: ((ContentLine) -> Boolean)? = null) {
        val indices = ArrayList<Int>()
        val existing = ArrayList<ContentLine>()
        for ((i, it) in items.withIndex()) {
            if (it is Item.Prop && it.line.name == name && (match?.invoke(it.line) ?: true)) {
                indices.add(i); existing.add(it.line)
            }
        }
        if (existing == lines) return
        val insertAt = indices.firstOrNull() ?: items.size
        for (i in indices.reversed()) items.removeAt(i)
        items.addAll(minOf(insertAt, items.size), lines.map { Item.Prop(it) })
    }

    internal fun setText(name: String, value: String?) {
        if (!value.isNullOrEmpty()) replace(name, listOf(ContentLine(name, value = ICalText.escape(value)))) else replace(name, emptyList())
    }

    internal fun setDate(name: String, value: ICalDate?) {
        replace(name, value?.let { listOf(it.line(name)) } ?: emptyList())
    }

    // MARK: Lesen

    /** Das Modell zum aktuellen Stand des Dokuments. */
    fun makeItem(): TodoItem {
        val title = text("SUMMARY") ?: ""
        val status = prop("STATUS")?.value?.uppercase()
        val completedDate = prop("COMPLETED")?.let { ICalStamp.parse(it.value) }
        val isCompleted = status == "COMPLETED" || completedDate != null

        val categories = rawCategories()
        val planwerk = planwerkMeta()
        val tags = if (planwerk != null) {
            val derived = HashSet<String>()
            planwerk.group?.let { derived.add(it) }
            planwerk.kind.categoryTag?.let { derived.add(it) }
            categories.filter { it !in derived && !it.startsWith("@") }
        } else categories

        val reminders = components("VALARM").map(::parseAlarm).map { r ->
            // Die App schreibt den Titel als Beschreibung, wenn keine eigene Nachricht gesetzt ist.
            if (r.message == title || (title.isEmpty() && r.message == "Erinnerung")) {
                Reminder(r.trigger, null, r.action, r.rawLines)
            } else r
        }

        return TodoItem(
            uid = prop("UID")?.value ?: "",
            title = title,
            notes = text("DESCRIPTION") ?: "",
            url = prop("URL")?.value,
            start = date("DTSTART"),
            due = date("DUE"),
            isCompleted = isCompleted,
            completedAt = completedDate,
            percentComplete = prop("PERCENT-COMPLETE")?.value?.trim()?.toIntOrNull() ?: if (isCompleted) 100 else 0,
            priority = prop("PRIORITY")?.value?.trim()?.toIntOrNull() ?: 0,
            tags = tags,
            parentUID = props("RELATED-TO").firstOrNull(::isParentRelation)?.value,
            sortOrder = prop("X-APPLE-SORT-ORDER")?.value?.trim()?.toIntOrNull(),
            color = prop("X-LISTWERK-COLOR")?.value,
            reminders = reminders,
            attachments = props("ATTACH").map(::parseAttachment),
            recurrence = prop("RRULE")?.value,
            planwerk = planwerk,
            created = prop("CREATED")?.let { ICalStamp.parse(it.value) },
            lastModified = prop("LAST-MODIFIED")?.let { ICalStamp.parse(it.value) },
        )
    }

    private fun rawCategories(): List<String> = props("CATEGORIES").flatMap { ICalText.splitList(it.value) }

    private fun planwerkMeta(): PlanwerkMeta? {
        val kind = prop("X-PLANWERK-KIND")
        val group = prop("X-PLANWERK-GROUP")
        val assignee = prop("X-PLANWERK-ASSIGNEE")
        if (kind == null && group == null && assignee == null) return null
        return PlanwerkMeta(
            kind = kind?.let { PlanwerkKind.fromRaw(it.value.lowercase()) } ?: PlanwerkKind.TASK,
            group = group?.let { ICalText.unescape(it.value) },
            assignee = assignee?.let { ICalText.unescape(it.value) },
        )
    }

    // MARK: Schreiben

    /**
     * Überträgt das Modell ins Dokument. Nur geänderte Felder werden angefasst. Bei einer Änderung
     * werden `LAST-MODIFIED`, `DTSTAMP` und `SEQUENCE` fortgeschrieben.
     */
    fun apply(new: TodoItem, now: Instant = Instant.now()) {
        val cur = makeItem()
        val before = items.toList()

        if (new.title != cur.title) setText("SUMMARY", new.title)
        if (new.notes != cur.notes) setText("DESCRIPTION", new.notes)
        if (new.url != cur.url) replace("URL", new.url?.let { listOf(ContentLine("URL", value = it)) } ?: emptyList())
        if (new.start != cur.start) setDate("DTSTART", new.start)
        if (new.due != cur.due) setDate("DUE", new.due)

        if (new.isCompleted != cur.isCompleted || new.percentComplete != cur.percentComplete) {
            if (new.isCompleted) {
                replace("STATUS", listOf(ContentLine("STATUS", value = "COMPLETED")))
                val stamp = new.completedAt ?: cur.completedAt ?: now
                replace("COMPLETED", listOf(ContentLine("COMPLETED", value = ICalStamp.string(stamp))))
                replace("PERCENT-COMPLETE", listOf(ContentLine("PERCENT-COMPLETE", value = "100")))
            } else {
                val status = if (new.percentComplete > 0) "IN-PROCESS" else "NEEDS-ACTION"
                replace("STATUS", listOf(ContentLine("STATUS", value = status)))
                replace("COMPLETED", emptyList())
                replace(
                    "PERCENT-COMPLETE",
                    if (new.percentComplete > 0) listOf(ContentLine("PERCENT-COMPLETE", value = new.percentComplete.toString())) else emptyList(),
                )
            }
        }

        if (new.priority != cur.priority) {
            replace("PRIORITY", if (new.priority > 0) listOf(ContentLine("PRIORITY", value = new.priority.toString())) else emptyList())
        }

        applyCategories(new)

        if (new.parentUID != cur.parentUID) {
            replace(
                "RELATED-TO",
                new.parentUID?.let { listOf(ContentLine("RELATED-TO", listOf(ICalParam("RELTYPE", "PARENT")), it)) } ?: emptyList(),
                ::isParentRelation,
            )
        }

        if (new.sortOrder != cur.sortOrder) {
            replace("X-APPLE-SORT-ORDER", new.sortOrder?.let { listOf(ContentLine("X-APPLE-SORT-ORDER", value = it.toString())) } ?: emptyList())
        }

        if (new.color != cur.color) {
            replace("X-LISTWERK-COLOR", new.color?.let { listOf(ContentLine("X-LISTWERK-COLOR", value = it)) } ?: emptyList())
            replace(
                "COLOR",
                new.color?.let { CSSColorNames.nearestName(it) }?.let { listOf(ContentLine("COLOR", value = it)) } ?: emptyList(),
            )
        }

        if (new.planwerk != cur.planwerk) {
            val m = new.planwerk
            replace("X-PLANWERK-KIND", m?.let { listOf(ContentLine("X-PLANWERK-KIND", value = it.kind.raw)) } ?: emptyList())
            replace("X-PLANWERK-GROUP", m?.group?.let { listOf(ContentLine("X-PLANWERK-GROUP", value = ICalText.escape(it))) } ?: emptyList())
            replace("X-PLANWERK-ASSIGNEE", m?.assignee?.let { listOf(ContentLine("X-PLANWERK-ASSIGNEE", value = ICalText.escape(it))) } ?: emptyList())
        }

        if (new.reminders != cur.reminders) rebuildAlarms(new, cur)
        if (new.attachments != cur.attachments) rebuildAttachments(new, cur)
        if (new.recurrence != cur.recurrence) {
            replace("RRULE", new.recurrence?.let { listOf(ContentLine("RRULE", value = it)) } ?: emptyList())
        }

        if (items != before) {
            isModified = true
            val stamp = ICalStamp.string(now)
            replace("LAST-MODIFIED", listOf(ContentLine("LAST-MODIFIED", value = stamp)))
            replace("DTSTAMP", listOf(ContentLine("DTSTAMP", value = stamp)))
            val seq = prop("SEQUENCE")?.value?.toIntOrNull() ?: -1
            replace("SEQUENCE", listOf(ContentLine("SEQUENCE", value = (seq + 1).toString())))
            if (prop("CREATED") == null) replace("CREATED", listOf(ContentLine("CREATED", value = stamp)))
        }
    }

    private fun applyCategories(new: TodoItem) {
        val existing = rawCategories()
        val desired: List<String> = new.planwerk?.let { meta ->
            // Planwerk liest die Kategorien als Gruppe, Art und @Person. Freie Tags sind hier nicht möglich.
            buildList {
                meta.group?.let { add(it) }
                meta.kind.categoryTag?.let { add(it) }
                meta.assignee?.let { add("@$it") }
            }
        } ?: new.tags
        if (existing.toSet() == desired.toSet() && existing.size == desired.size) return
        val line = if (desired.isEmpty()) emptyList() else listOf(ContentLine("CATEGORIES", value = desired.joinToString(",") { ICalText.escape(it) }))
        replace("CATEGORIES", line)
    }

    private fun rebuildAlarms(new: TodoItem, cur: TodoItem) {
        val blocks = new.reminders.map { r ->
            val same = cur.reminders.firstOrNull { it == r }
            same?.rawLines ?: alarmLines(r, new.title)
        }
        items.removeAll { it is Item.Component && it.name == "VALARM" }
        items.addAll(blocks.map { Item.Component("VALARM", it) })
    }

    private fun rebuildAttachments(new: TodoItem, cur: TodoItem) {
        val lines = new.attachments.map { a ->
            val same = cur.attachments.firstOrNull { it == a }
            same?.raw ?: run {
                val params = ArrayList<ICalParam>()
                a.mimeType?.let { params.add(ICalParam("FMTTYPE", it)) }
                a.filename?.let { f ->
                    params.add(ICalParam("FILENAME", if (f.any { it in ":;," }) "\"$f\"" else f))
                }
                ContentLine("ATTACH", params, a.uri)
            }
        }
        replace("ATTACH", lines)
    }
}
