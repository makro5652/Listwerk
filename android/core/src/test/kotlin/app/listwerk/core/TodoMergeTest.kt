package app.listwerk.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoMergeTest {
    private val base = TodoItem(uid = "u", title = "Basis", priority = 0, lastModified = Instant.ofEpochSecond(100))

    @Test
    fun oneSidedChangesAreTakenWithoutConflict() {
        val local = base.copy(priority = 1, lastModified = Instant.ofEpochSecond(200))
        val remote = base.copy(title = "Server", lastModified = Instant.ofEpochSecond(150))
        val r = TodoMerge.merge(base, local, remote)
        assertEquals("Server", r.merged.title)
        assertEquals(1, r.merged.priority)
        assertEquals(emptyList(), r.conflicts)
    }

    @Test
    fun tieOnLastModifiedPrefersLocal() {
        val stamp = Instant.ofEpochSecond(300)
        val local = base.copy(title = "Lokal", lastModified = stamp)
        val remote = base.copy(title = "Server", lastModified = stamp)
        val r = TodoMerge.merge(base, local, remote)
        assertEquals("Lokal", r.merged.title)
        assertEquals(FieldConflict.Winner.LOCAL, r.conflicts.single().winner)
    }

    @Test
    fun withoutBaseDifferencesAreConflictsDecidedByNewerStamp() {
        val local = base.copy(notes = "lokal", lastModified = Instant.ofEpochSecond(100))
        val remote = base.copy(notes = "server", lastModified = Instant.ofEpochSecond(200))
        val r = TodoMerge.merge(null, local, remote)
        assertEquals("server", r.merged.notes)
        assertEquals(FieldConflict.Winner.REMOTE, r.conflicts.single().winner)
        assertEquals("notes", r.conflicts.single().field)
    }

    @Test
    fun completionStateMergesAsOneField() {
        val local = base.copy(isCompleted = true, percentComplete = 100, lastModified = Instant.ofEpochSecond(200))
        val remote = base.copy(lastModified = Instant.ofEpochSecond(150))
        val r = TodoMerge.merge(base, local, remote)
        assertEquals(true, r.merged.isCompleted)
        assertEquals(100, r.merged.percentComplete)
    }
}
