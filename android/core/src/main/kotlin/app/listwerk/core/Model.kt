package app.listwerk.core

import java.time.Instant
import java.util.UUID

object ListwerkCoreInfo {
    const val VERSION = "0.1.0"
}

enum class PlanwerkKind(val raw: String) {
    TASK("task"), MILESTONE("milestone"), EXPERIMENT("experiment");

    /** Tag, den Planwerk zusätzlich in CATEGORIES führt. */
    val categoryTag: String?
        get() = when (this) {
            TASK -> null
            MILESTONE -> "Meilenstein"
            EXPERIMENT -> "Experiment"
        }

    companion object {
        fun fromRaw(s: String): PlanwerkKind? = entries.firstOrNull { it.raw == s }
    }
}

/** Felder, die Planwerk in verknüpften Listen verwaltet (Art, Gruppe, Zugewiesen). */
data class PlanwerkMeta(
    val kind: PlanwerkKind = PlanwerkKind.TASK,
    val group: String? = null,
    val assignee: String? = null,
)

sealed interface ReminderTrigger {
    data class Absolute(val at: Instant) : ReminderTrigger

    /** Sekunden relativ zu Beginn bzw. Fälligkeit; negativ = davor. */
    data class Relative(val seconds: Double, val toEnd: Boolean) : ReminderTrigger
}

class Reminder(
    val trigger: ReminderTrigger,
    val message: String? = null,
    val action: String = "DISPLAY",
    /** Originalzeilen des VALARM, solange die Erinnerung unverändert ist. Erhält fremde Felder. */
    internal val rawLines: List<String>? = null,
) {
    val id: String = UUID.randomUUID().toString()

    fun copy(trigger: ReminderTrigger = this.trigger, message: String? = this.message, action: String = this.action) =
        Reminder(trigger, message, action, null)

    override fun equals(other: Any?): Boolean =
        other is Reminder && trigger == other.trigger && message == other.message && action == other.action

    override fun hashCode(): Int = 31 * (31 * trigger.hashCode() + (message?.hashCode() ?: 0)) + action.hashCode()

    override fun toString(): String = "Reminder($trigger, $message, $action)"

    /** Wird nur lokal angezeigt (DISPLAY/AUDIO). E-Mail-Erinnerungen bleiben erhalten, lösen aber nichts aus. */
    val firesLocally: Boolean get() = action.uppercase() != "EMAIL"
}

class AttachmentRef(
    val uri: String,
    val mimeType: String? = null,
    val filename: String? = null,
    /** Eingebettete Base64-Anhänge anderer Clients: werden angezeigt und erhalten, nie selbst erzeugt. */
    val isInline: Boolean = false,
    internal val raw: ContentLine? = null,
) {
    val id: String get() = if (uri.isEmpty()) (filename ?: "inline") else uri

    override fun equals(other: Any?): Boolean =
        other is AttachmentRef && uri == other.uri && mimeType == other.mimeType &&
            filename == other.filename && isInline == other.isInline

    override fun hashCode(): Int = listOf(uri, mimeType, filename, isInline).hashCode()

    override fun toString(): String = "AttachmentRef($uri, $mimeType, $filename, inline=$isInline)"
}

/** Ein Eintrag der To-do-Liste, unabhängig vom Dateiformat. */
data class TodoItem(
    val uid: String = UUID.randomUUID().toString().lowercase(),
    val title: String = "",
    val notes: String = "",
    val url: String? = null,
    val start: ICalDate? = null,
    val due: ICalDate? = null,
    val isCompleted: Boolean = false,
    val completedAt: Instant? = null,
    val percentComplete: Int = 0,
    /** iCalendar-Priorität: 0 = keine, 1–4 hoch, 5 mittel, 6–9 niedrig. */
    val priority: Int = 0,
    val tags: List<String> = emptyList(),
    val parentUID: String? = null,
    val sortOrder: Int? = null,
    /** `#RRGGBB` */
    val color: String? = null,
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    /** Wert von RRULE, unverändert durchgereicht. */
    val recurrence: String? = null,
    val planwerk: PlanwerkMeta? = null,
    val created: Instant? = null,
    val lastModified: Instant? = null,
) {
    val id: String get() = uid
}

enum class TodoPriority(val value: Int) {
    NONE(0), HIGH(1), MEDIUM(5), LOW(9);

    companion object {
        fun fromIcal(v: Int): TodoPriority = when (v) {
            in 1..4 -> HIGH
            5 -> MEDIUM
            in 6..9 -> LOW
            else -> NONE
        }
    }
}
