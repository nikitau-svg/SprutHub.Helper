package io.github.nikitau.spruthubhelper.diagnostics

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DiagnosticReportRenderer {
    fun renderFull(snapshot: DiagnosticSnapshot): String {
        val report = buildString {
            appendLine("SprutHub Helper — диагностика")
            appendLine("Создано: ${formatTime(snapshot.generatedAtEpochMs)}")
            appendLine("Это структурированный ограниченный журнал, а не raw logcat.")
            appendLine("Секреты и персональные значения автоматически скрыты.")
            appendLine()
            appendSystemInfo(snapshot)
            appendLine()
            appendPermissions(snapshot.permissions)
            appendLine()
            appendBackgroundRuns(snapshot.events)
            appendLine()
            appendLine("События (${snapshot.events.size}, сначала старые)")
            if (snapshot.events.isEmpty()) {
                appendLine("- событий пока нет")
            } else {
                snapshot.events.sortedBy(DiagnosticEvent::epochMs).forEach { event ->
                    appendLine(formatEvent(event))
                }
            }
        }
        return DiagnosticRedactor.redactText(report).trimEnd() + "\n"
    }

    fun renderSummary(snapshot: DiagnosticSnapshot): String {
        val summary = buildString {
            appendLine("SprutHub Helper ${snapshot.appVersion} (${snapshot.appVersionCode})")
            appendLine("Android ${snapshot.androidVersion} / API ${snapshot.androidSdk}")
            appendLine("Устройство: ${snapshot.manufacturer} ${snapshot.model}")
            appendLine("Battery optimization: ${snapshot.batteryOptimization}")
            appendLine("Фоновое ограничение: ${snapshot.backgroundRestriction}")
            appendLine("Уведомления: ${snapshot.notificationState}")
            appendLine("Сеть: ${snapshot.networkState}")
            appendLine("Разрешения: ${snapshot.permissions.joinToString { "${it.title} — ${it.state}" }}")
            appendLine("Последние события:")
            snapshot.events.take(8).forEach { event -> appendLine(formatEvent(event)) }
            if (snapshot.events.isEmpty()) appendLine("- событий пока нет")
        }
        return DiagnosticRedactor.redactText(summary).take(MAX_SUMMARY_LENGTH).trimEnd()
    }

    private fun StringBuilder.appendSystemInfo(snapshot: DiagnosticSnapshot) {
        appendLine("Система")
        appendLine("- Версия приложения: ${snapshot.appVersion} (${snapshot.appVersionCode})")
        appendLine("- Android: ${snapshot.androidVersion}, API ${snapshot.androidSdk}")
        appendLine("- Модель: ${snapshot.manufacturer} ${snapshot.model}")
        appendLine("- Battery optimization: ${snapshot.batteryOptimization}")
        appendLine("- Фоновое ограничение: ${snapshot.backgroundRestriction}")
        appendLine("- Уведомления: ${snapshot.notificationState}")
        appendLine("- Сеть: ${snapshot.networkState}")
    }

    private fun StringBuilder.appendPermissions(permissions: List<DiagnosticPermissionStatus>) {
        appendLine("Разрешения")
        if (permissions.isEmpty()) {
            appendLine("- нет запрашиваемых runtime-разрешений")
        } else {
            permissions.forEach { permission ->
                appendLine("- ${permission.title}: ${permission.state}")
            }
        }
    }

    private fun StringBuilder.appendBackgroundRuns(events: List<DiagnosticEvent>) {
        appendLine("Последние фоновые запуски")
        val backgroundEvents = events
            .asSequence()
            .filter { it.category in BACKGROUND_CATEGORIES }
            .take(MAX_BACKGROUND_EVENTS)
            .toList()
        if (backgroundEvents.isEmpty()) {
            appendLine("- записей пока нет")
        } else {
            backgroundEvents.forEach { event -> appendLine(formatEvent(event)) }
        }
    }

    private fun formatEvent(source: DiagnosticEvent): String {
        val event = DiagnosticRedactor.redact(source)
        val channel = event.channel.takeUnless { it == DiagnosticChannel.NONE }
            ?.let { ", канал: ${it.title}" }
            .orEmpty()
        val reason = event.reason?.takeIf(String::isNotBlank)
            ?.let { ", причина: $it" }
            .orEmpty()
        val details = event.details.takeIf(Map<String, String>::isNotEmpty)
            ?.entries
            ?.joinToString(prefix = ", ", separator = ", ") { (key, value) -> "$key=$value" }
            .orEmpty()
        return "- ${formatTime(event.epochMs)} [${event.category.title}] ${event.event}: " +
            "${event.outcome.title}$channel$reason$details"
    }

    private fun formatTime(epochMs: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMs))

    private companion object {
        const val MAX_BACKGROUND_EVENTS = 20
        const val MAX_SUMMARY_LENGTH = 6_000
        val BACKGROUND_CATEGORIES = setOf(
            DiagnosticCategory.SYNC,
            DiagnosticCategory.WORK_MANAGER,
            DiagnosticCategory.FOREGROUND_SERVICE,
            DiagnosticCategory.BACKGROUND,
        )
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC)
    }
}

internal class DiagnosticReportExporter(
    private val directory: File,
    private val renderer: DiagnosticReportRenderer = DiagnosticReportRenderer(),
) {
    fun export(snapshot: DiagnosticSnapshot): File {
        directory.mkdirs()
        val file = File(directory, fileName(snapshot.generatedAtEpochMs))
        val fullyRedactedReport = DiagnosticRedactor.redactText(renderer.renderFull(snapshot))
        file.writeText(fullyRedactedReport, Charsets.UTF_8)
        pruneOldExports(keep = file)
        return file
    }

    fun clearExports() {
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "txt" }
            ?.forEach(File::delete)
    }

    private fun pruneOldExports(keep: File) {
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "txt" && it != keep }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_OLD_EXPORTS)
            ?.forEach(File::delete)
    }

    private fun fileName(epochMs: Long): String = FILE_PREFIX + FILE_TIME_FORMAT.format(
        Instant.ofEpochMilli(epochMs),
    ) + ".txt"

    companion object {
        const val EXPORT_DIRECTORY = "diagnostic-exports"
        private const val FILE_PREFIX = "spruthub-helper-diagnostics-"
        private const val MAX_OLD_EXPORTS = 2
        private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
    }
}
