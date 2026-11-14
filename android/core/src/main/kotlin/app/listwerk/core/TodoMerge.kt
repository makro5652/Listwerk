package app.listwerk.core

data class FieldConflict(
    val field: String,
    val local: String,
    val remote: String,
    val winner: Winner,
) {
    enum class Winner { LOCAL, REMOTE }
}

/**
 * Feldweiser Dreiwege-Abgleich.
 *
 * - Hat sich ein Feld nur auf einer Seite gegenüber `base` geändert, gilt diese Änderung.
 * - Haben beide Seiten dasselbe Feld unterschiedlich geändert, gewinnt die Fassung mit dem neueren
 *   `LAST-MODIFIED` (bei Gleichstand die lokale). Die unterlegene Fassung wird als Konflikt gemeldet.
 */
object TodoMerge {
    class Result(val merged: TodoItem, val conflicts: List<FieldConflict>)

    private data class CompletionState(val isCompleted: Boolean, val percent: Int)

    fun merge(base: TodoItem?, local: TodoItem, remote: TodoItem): Result {
        var merged = remote
        val conflicts = ArrayList<FieldConflict>()
        val localWins = (local.lastModified ?: java.time.Instant.MIN) >= (remote.lastModified ?: java.time.Instant.MIN)

        fun <V> field(
            name: String,
            get: (TodoItem) -> V,
            set: (TodoItem, V) -> TodoItem,
            describe: (V) -> String = { it.toString() },
        ) {
            val l = get(local)
            val r = get(remote)
            if (l == r) return
            if (base != null) {
                val b = get(base)
                if (l == b) return
                if (r == b) { merged = set(merged, l); return }
            }
            if (localWins) merged = set(merged, l)
            conflicts.add(FieldConflict(name, describe(l), describe(r), if (localWins) FieldConflict.Winner.LOCAL else FieldConflict.Winner.REMOTE))
        }

        fun day(d: ICalDate?) = d?.valueString ?: "–"

        field("title", { it.title }, { t, v -> t.copy(title = v) })
        field("notes", { it.notes }, { t, v -> t.copy(notes = v) })
        field("url", { it.url }, { t, v -> t.copy(url = v) }, { it ?: "–" })
        field("start", { it.start }, { t, v -> t.copy(start = v) }, ::day)
        field("due", { it.due }, { t, v -> t.copy(due = v) }, ::day)
        field(
            "completion", { CompletionState(it.isCompleted, it.percentComplete) },
            { t, v -> t.copy(isCompleted = v.isCompleted, percentComplete = v.percent) },
            { if (it.isCompleted) "erledigt" else "offen (${it.percent} %)" },
        )
        field("priority", { it.priority }, { t, v -> t.copy(priority = v) })
        field("tags", { it.tags }, { t, v -> t.copy(tags = v) }, { it.joinToString(", ") })
        field("parent", { it.parentUID }, { t, v -> t.copy(parentUID = v) }, { it ?: "–" })
        field("sortOrder", { it.sortOrder }, { t, v -> t.copy(sortOrder = v) }, { it?.toString() ?: "–" })
        field("color", { it.color }, { t, v -> t.copy(color = v) }, { it ?: "–" })
        field("reminders", { it.reminders }, { t, v -> t.copy(reminders = v) }, { "${it.size} Erinnerung(en)" })
        field("attachments", { it.attachments }, { t, v -> t.copy(attachments = v) }, { "${it.size} Anhang/Anhänge" })
        field("recurrence", { it.recurrence }, { t, v -> t.copy(recurrence = v) }, { it ?: "–" })
        field(
            "planwerk", { it.planwerk }, { t, v -> t.copy(planwerk = v) },
            { m -> m?.let { "${it.kind.raw}/${it.group ?: "–"}/${it.assignee ?: "–"}" } ?: "–" },
        )
        return Result(merged, conflicts)
    }
}
