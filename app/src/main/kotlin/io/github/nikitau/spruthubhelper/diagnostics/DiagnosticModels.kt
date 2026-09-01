package io.github.nikitau.spruthubhelper.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticCategory(val title: String) {
    APP("Приложение"),
    SYNC("Синхронизация"),
    CONNECTION("Подключение"),
    WORK_MANAGER("WorkManager"),
    FOREGROUND_SERVICE("Foreground service"),
    NETWORK("Сеть"),
    PERMISSION("Разрешения"),
    BACKGROUND("Фоновая работа"),
    COMMAND("Команды"),
    SYSTEM("Система"),
}

@Serializable
enum class DiagnosticOutcome(val title: String) {
    STARTED("запущено"),
    SUCCESS("успешно"),
    SKIPPED("пропущено"),
    FAILED("ошибка"),
    STATE("состояние"),
}

@Serializable
enum class DiagnosticChannel(val title: String) {
    NONE("—"),
    LOCAL("локальный"),
    CLOUD("облачный"),
}

/**
 * One deliberately small, structured diagnostic record.
 *
 * Callers should pass only operational metadata. [DiagnosticRedactor] still
 * sanitises every field before it reaches disk, the UI, or an export file.
 */
@Serializable
data class DiagnosticEvent(
    val epochMs: Long = System.currentTimeMillis(),
    val category: DiagnosticCategory,
    val event: String,
    val outcome: DiagnosticOutcome = DiagnosticOutcome.STATE,
    val channel: DiagnosticChannel = DiagnosticChannel.NONE,
    val reason: String? = null,
    val details: Map<String, String> = emptyMap(),
)

data class DiagnosticPermissionStatus(
    val title: String,
    val state: String,
)

data class DiagnosticSnapshot(
    val generatedAtEpochMs: Long,
    val appVersion: String,
    val appVersionCode: Long,
    val androidVersion: String,
    val androidSdk: Int,
    val manufacturer: String,
    val model: String,
    val batteryOptimization: String,
    val backgroundRestriction: String,
    val notificationState: String,
    val networkState: String,
    val permissions: List<DiagnosticPermissionStatus>,
    val events: List<DiagnosticEvent>,
)
