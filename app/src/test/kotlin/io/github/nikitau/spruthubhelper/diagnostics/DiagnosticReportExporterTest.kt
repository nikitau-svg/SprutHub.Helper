package io.github.nikitau.spruthubhelper.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticReportExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `export contains support metadata and is redacted after assembly`() {
        val snapshot = DiagnosticSnapshot(
            generatedAtEpochMs = 1_788_118_400_000,
            appVersion = "0.3.0-beta.8",
            appVersionCode = 10,
            androidVersion = "16",
            androidSdk = 36,
            manufacturer = "Samsung",
            model = "SM-F966B",
            batteryOptimization = "оптимизация включена",
            backgroundRestriction = "явного ограничения нет",
            notificationState = "разрешены",
            networkState = "Wi-Fi, endpoint=192.168.1.44",
            permissions = listOf(
                DiagnosticPermissionStatus("Уведомления", "выдано"),
                DiagnosticPermissionStatus("Health Connect", "5 из 14 выдано"),
            ),
            events = listOf(
                DiagnosticEvent(
                    epochMs = 1_788_118_399_000,
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = "Фоновая синхронизация",
                    outcome = DiagnosticOutcome.FAILED,
                    channel = DiagnosticChannel.LOCAL,
                    reason = "owner@example.com token=my-token ws://user:pass@10.0.0.2/HUB-9988",
                    details = mapOf("heart_rate" to "82", "attempt" to "2"),
                ),
            ),
        )
        val exporter = DiagnosticReportExporter(temporaryFolder.newFolder("exports"))

        val file = exporter.export(snapshot)
        val report = file.readText()

        assertTrue(file.name.matches(Regex("spruthub-helper-diagnostics-\\d{8}-\\d{6}\\.txt")))
        assertTrue(report.contains("Версия приложения: 0.3.0-beta.8 (10)"))
        assertTrue(report.contains("Android: 16, API 36"))
        assertTrue(report.contains("Модель: Samsung SM-F966B"))
        assertTrue(report.contains("Разрешения"))
        assertTrue(report.contains("Последние фоновые запуски"))
        assertTrue(report.contains("Это структурированный ограниченный журнал, а не raw logcat"))
        listOf("owner@example.com", "my-token", "10.0.0.2", "HUB-9988", "82").forEach { secret ->
            assertFalse("Unexpected value in export: $secret", report.contains(secret))
        }
    }

    @Test
    fun `clipboard summary receives the same final redaction`() {
        val snapshot = minimalSnapshot().copy(
            model = "Model serial=PRIVATE-SERIAL",
            networkState = "host=owner-hub.local",
        )

        val summary = DiagnosticReportRenderer().renderSummary(snapshot)

        assertFalse(summary.contains("PRIVATE-SERIAL"))
        assertFalse(summary.contains("owner-hub.local"))
        assertTrue(summary.contains(DiagnosticRedactor.REDACTED))
    }

    private fun minimalSnapshot() = DiagnosticSnapshot(
        generatedAtEpochMs = 1,
        appVersion = "test",
        appVersionCode = 1,
        androidVersion = "test",
        androidSdk = 36,
        manufacturer = "test",
        model = "test",
        batteryOptimization = "test",
        backgroundRestriction = "test",
        notificationState = "test",
        networkState = "test",
        permissions = emptyList(),
        events = emptyList(),
    )
}
