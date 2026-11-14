package app.listwerk.core

import java.time.Instant

/** Fester Zeitpunkt für reproduzierbare Zeitstempel: 2026-11-11 12:00:00 UTC. */
val testNow: Instant = Instant.ofEpochSecond(1_794_398_400)

fun todo(title: String, uid: String? = null, configure: (TodoItem) -> TodoItem = { it }): TodoItem =
    configure(TodoItem(uid = uid ?: title.lowercase().replace(" ", "-"), title = title))
