package io.github.nikitau.spruthubhelper.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticJournalStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `storage persists only the newest configured number of events`() {
        val file = File(temporaryFolder.newFolder("count"), "events.jsonl")
        val storage = DiagnosticJournalStorage(file, maxEvents = 3, maxBytes = 64 * 1024)

        repeat(6) { index -> storage.append(event(index)) }

        assertEquals(listOf("event-3", "event-4", "event-5"), storage.read().map(DiagnosticEvent::event))
        val reopened = DiagnosticJournalStorage(file, maxEvents = 3, maxBytes = 64 * 1024)
        assertEquals(listOf("event-3", "event-4", "event-5"), reopened.read().map(DiagnosticEvent::event))
    }

    @Test
    fun `storage obeys utf8 byte limit and keeps newest events`() {
        val file = File(temporaryFolder.newFolder("bytes"), "events.jsonl")
        val byteLimit = 1_100
        val storage = DiagnosticJournalStorage(file, maxEvents = 100, maxBytes = byteLimit)

        repeat(30) { index ->
            storage.append(
                event(index).copy(reason = "Причина ${"ю".repeat(90)}"),
            )
        }

        val retained = storage.read()
        assertTrue(retained.isNotEmpty())
        assertTrue(retained.size < 30)
        assertEquals("event-29", retained.last().event)
        assertTrue("${file.length()} > $byteLimit", file.length() <= byteLimit)
        assertTrue(storage.encodedSize(retained) <= byteLimit)
    }

    @Test
    fun `storage redacts before writing to disk`() {
        val file = File(temporaryFolder.newFolder("redaction"), "events.jsonl")
        val storage = DiagnosticJournalStorage(file)

        storage.append(
            event(1).copy(
                reason = "token=my-token owner@example.com",
                details = mapOf("latitude" to "55.7558", "safe_attempt" to "1"),
            ),
        )

        val rawFile = file.readText()
        assertFalse(rawFile.contains("my-token"))
        assertFalse(rawFile.contains("owner@example.com"))
        assertFalse(rawFile.contains("55.7558"))
        assertTrue(rawFile.contains(DiagnosticRedactor.REDACTED))
    }

    private fun event(index: Int) = DiagnosticEvent(
        epochMs = index.toLong(),
        category = DiagnosticCategory.WORK_MANAGER,
        event = "event-$index",
        outcome = DiagnosticOutcome.SUCCESS,
    )
}
