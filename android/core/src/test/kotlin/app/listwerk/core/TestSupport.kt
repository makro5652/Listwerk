package app.listwerk.core

import java.time.Instant

object Fixture {
    fun text(name: String): String {
        val stream = Fixture::class.java.getResourceAsStream("/fixtures/$name.ics")
            ?: error("Fixture fehlt: $name")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    val all = listOf(
        "nextcloud-tasks", "apple-reminders", "thunderbird-vtimezone",
        "planwerk-linked", "long-unicode", "inline-attachment",
    )
}

fun unfoldedLines(s: String): List<String> = ICalText.unfold(s)

fun doc(text: String): ICalDocument = requireNotNull(ICalDocument.parse(text)) { "kein VTODO" }
