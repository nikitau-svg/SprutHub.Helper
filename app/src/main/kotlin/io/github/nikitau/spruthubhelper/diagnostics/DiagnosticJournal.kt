package io.github.nikitau.spruthubhelper.diagnostics

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent bounded storage. The on-disk format is one sanitised JSON event per line. */
internal class DiagnosticJournalStorage(
    private val file: File,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val json: Json = DEFAULT_JSON,
) {
    init {
        require(maxEvents > 0)
        require(maxBytes > 0)
    }

    @Synchronized
    fun read(): List<DiagnosticEvent> {
        if (!file.isFile) return emptyList()
        val retained = ArrayDeque<DiagnosticEvent>(maxEvents)
        runCatching {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val event = runCatching { json.decodeFromString<DiagnosticEvent>(line) }.getOrNull()
                        ?: return@forEach
                    retained.addLast(DiagnosticRedactor.redact(event))
                    while (retained.size > maxEvents) retained.removeFirst()
                }
            }
        }
        return bounded(retained.toList())
    }

    @Synchronized
    fun append(event: DiagnosticEvent): List<DiagnosticEvent> {
        val retained = bounded(read() + DiagnosticRedactor.redact(event))
        replace(retained)
        return retained
    }

    @Synchronized
    fun compact(): List<DiagnosticEvent> = read().also(::replace)

    @Synchronized
    fun clear() {
        if (file.exists() && !file.delete()) {
            replace(emptyList())
        }
        file.parentFile?.listFiles()
            ?.filter { it.name.startsWith("${file.name}.") && it.name.endsWith(".tmp") }
            ?.forEach(File::delete)
    }

    internal fun encodedSize(events: List<DiagnosticEvent>): Int = events.sumOf { event ->
        json.encodeToString(DiagnosticRedactor.redact(event)).toByteArray(Charsets.UTF_8).size + 1
    }

    private fun bounded(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val retained = ArrayDeque(events.takeLast(maxEvents))
        var size = encodedSize(retained.toList())
        while (retained.isNotEmpty() && size > maxBytes) {
            val removed = retained.removeFirst()
            size -= json.encodeToString(removed).toByteArray(Charsets.UTF_8).size + 1
        }
        return retained.toList()
    }

    private fun replace(events: List<DiagnosticEvent>) {
        file.parentFile?.mkdirs()
        if (events.isEmpty()) {
            if (file.exists()) file.writeText("")
            return
        }
        val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        temporary.bufferedWriter().use { output ->
            events.forEach { event ->
                output.appendLine(json.encodeToString(DiagnosticRedactor.redact(event)))
            }
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { error ->
            temporary.delete()
            throw error
        }
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 400
        const val DEFAULT_MAX_BYTES = 384 * 1024
        val DEFAULT_JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

class DiagnosticJournal private constructor(
    private val storage: DiagnosticJournalStorage,
    private val scope: CoroutineScope,
) {
    private val writeMutex = Mutex()
    private val _events = MutableStateFlow(storage.compact().asReversed())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun record(
        category: DiagnosticCategory,
        event: String,
        outcome: DiagnosticOutcome = DiagnosticOutcome.STATE,
        channel: DiagnosticChannel = DiagnosticChannel.NONE,
        reason: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        record(
            DiagnosticEvent(
                category = category,
                event = event,
                outcome = outcome,
                channel = channel,
                reason = reason,
                details = details,
            ),
        )
    }

    fun record(event: DiagnosticEvent) {
        scope.launch(Dispatchers.IO) { recordNow(event) }
    }

    suspend fun recordNow(event: DiagnosticEvent) = writeMutex.withLock {
        _events.value = storage.append(event).asReversed()
    }

    fun clear() {
        scope.launch(Dispatchers.IO) { clearNow() }
    }

    suspend fun clearNow() = writeMutex.withLock {
        storage.clear()
        _events.value = emptyList()
    }

    companion object {
        private const val JOURNAL_DIRECTORY = "diagnostics"
        private const val JOURNAL_FILE = "events.jsonl"

        fun create(context: Context, scope: CoroutineScope): DiagnosticJournal = DiagnosticJournal(
            storage = DiagnosticJournalStorage(
                File(File(context.filesDir, JOURNAL_DIRECTORY), JOURNAL_FILE),
            ),
            scope = scope,
        )
    }
}
