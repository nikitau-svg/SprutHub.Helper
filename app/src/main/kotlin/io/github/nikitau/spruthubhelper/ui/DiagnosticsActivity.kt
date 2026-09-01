package io.github.nikitau.spruthubhelper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticChannel
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticEvent
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticReportExporter
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticReportRenderer
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticSnapshot
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticSnapshotFactory
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SprutHelperTheme {
                DiagnosticsScreen(onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val events by AppGraph.diagnostics.events.collectAsState()
    val health by AppGraph.health.state.collectAsState()
    val snapshot = remember(events, health.available, health.grantedPermissions) {
        DiagnosticSnapshotFactory.capture(
            context = context,
            events = events,
            healthConnectAvailable = health.available,
            healthGrantedPermissions = health.grantedPermissions,
        )
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun exportAndShare() {
        scope.launch {
            busy = true
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val freshSnapshot = DiagnosticSnapshotFactory.capture(
                        context = context,
                        events = AppGraph.diagnostics.events.value,
                        healthConnectAvailable = AppGraph.health.state.value.available,
                        healthGrantedPermissions = AppGraph.health.state.value.grantedPermissions,
                    )
                    DiagnosticReportExporter(
                        File(context.cacheDir, DiagnosticReportExporter.EXPORT_DIRECTORY),
                    ).export(freshSnapshot)
                }
                shareReport(context, file)
            }.onFailure { error ->
                showMessage(error.message ?: "Не удалось подготовить файл")
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PrivacyExplanationCard() }
            item { SupportChecklistCard() }
            item {
                DiagnosticActions(
                    busy = busy,
                    onShare = { confirmShare = true },
                    onCopy = {
                        val summary = DiagnosticReportRenderer().renderSummary(snapshot)
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("SprutHub Helper — диагностика", summary))
                        showMessage("Сводка скопирована без секретов и персональных значений")
                    },
                    onClear = { confirmClear = true },
                )
            }
            item { SystemInfoCard(snapshot) }
            item { PermissionCard(snapshot) }
            item { BackgroundRunsCard(events) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Журнал", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Хранится не более 400 записей и 384 КиБ. Новые записи вытесняют старые.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (events.isEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Событий пока нет", Modifier.padding(16.dp))
                    }
                }
            } else {
                itemsIndexed(
                    items = events,
                    key = { index, event -> "${event.epochMs}-${event.category}-$index" },
                ) { _, event -> DiagnosticEventCard(event) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить диагностику?") },
            text = { Text("Будут удалены весь сохранённый журнал и временные файлы экспорта на этом телефоне.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) {
                                AppGraph.diagnostics.clearNow()
                                DiagnosticReportExporter(
                                    File(context.cacheDir, DiagnosticReportExporter.EXPORT_DIRECTORY),
                                ).clearExports()
                            }
                            busy = false
                            showMessage("Диагностика очищена")
                        }
                    },
                ) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text("Поделиться безопасным отчётом?") },
            text = {
                Text(
                    "В отчёте будут модель телефона, версия Android, состояния разрешений, тип сети, " +
                        "время и результаты событий Helper. Пароли, адреса, идентификаторы, координаты " +
                        "и значения здоровья скрываются. Перед публикацией файл всё равно можно открыть " +
                        "и проверить вручную.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmShare = false
                        exportAndShare()
                    },
                ) { Text("Подготовить файл") }
            },
            dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PrivacyExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Безопасный журнал приложения", fontWeight = FontWeight.SemiBold)
            Text(
                "Это не raw logcat. Записываются только структурированные события SprutHub Helper: " +
                    "запуски, результаты, каналы и понятные причины пропуска.",
            )
            Text(
                "Пароли, токены, адреса серверов, hub id и серийники, e-mail, точные координаты и " +
                    "значения здоровья скрываются при записи и ещё раз перед экспортом.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "После force-stop и при полной заморозке приложения прошивкой новые события появиться не могут.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportChecklistCard() {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Если что-то не работает", fontWeight = FontWeight.SemiBold)
            Text("1. Повторите проблему один раз и запомните примерное время.")
            Text("2. Не очищайте журнал до экспорта.")
            Text("3. Отправьте отчёт вместе с коротким описанием нажатия и ожидаемого результата.")
            Text(
                "Raw logcat, DevInfo хаба и снимки с адресами или серийниками обычно не нужны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticActions(
    busy: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Share, null)
                Text("Поделиться безопасным отчётом", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onCopy, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContentCopy, null)
                Text("Скопировать сводку", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = onClear, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                Text("Очистить", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SystemInfoCard(snapshot: DiagnosticSnapshot) {
    DiagnosticSectionCard("Приложение и Android") {
        DiagnosticRow("Версия", "${snapshot.appVersion} (${snapshot.appVersionCode})")
        DiagnosticRow("Android", "${snapshot.androidVersion}, API ${snapshot.androidSdk}")
        DiagnosticRow("Модель", "${snapshot.manufacturer} ${snapshot.model}")
        DiagnosticRow("Battery optimization", snapshot.batteryOptimization)
        DiagnosticRow("Фоновое ограничение", snapshot.backgroundRestriction)
        DiagnosticRow("Уведомления", snapshot.notificationState)
        DiagnosticRow("Сеть", snapshot.networkState)
    }
}

@Composable
private fun PermissionCard(snapshot: DiagnosticSnapshot) {
    DiagnosticSectionCard("Разрешения") {
        snapshot.permissions.forEach { permission ->
            DiagnosticRow(permission.title, permission.state)
        }
    }
}

@Composable
private fun BackgroundRunsCard(events: List<DiagnosticEvent>) {
    val recent = events.filter { it.category in BACKGROUND_CATEGORIES }.take(12)
    DiagnosticSectionCard("Последние фоновые запуски") {
        if (recent.isEmpty()) {
            Text("Записей пока нет", style = MaterialTheme.typography.bodySmall)
        } else {
            recent.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                EventSummary(event)
            }
        }
    }
}

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EventSummary(event)
            event.details.forEach { (key, value) -> DiagnosticRow(key, value) }
        }
    }
}

@Composable
private fun EventSummary(event: DiagnosticEvent) {
    Text(
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(event.epochMs)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(event.event, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = {}, label = { Text(event.category.title) })
        Text(
            event.outcome.title,
            style = MaterialTheme.typography.labelMedium,
            color = when (event.outcome) {
                DiagnosticOutcome.FAILED -> MaterialTheme.colorScheme.error
                DiagnosticOutcome.SKIPPED -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    if (event.channel != DiagnosticChannel.NONE) {
        Text("Канал: ${event.channel.title}", style = MaterialTheme.typography.bodySmall)
    }
    event.reason?.takeIf(String::isNotBlank)?.let { reason ->
        Text("Причина: $reason", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagnosticSectionCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.42f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.58f),
        )
    }
}

private fun shareReport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.diagnostics.files",
        file,
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "SprutHub Helper — диагностика")
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Диагностика SprutHub Helper", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Поделиться диагностикой"))
}

private val BACKGROUND_CATEGORIES = setOf(
    DiagnosticCategory.SYNC,
    DiagnosticCategory.WORK_MANAGER,
    DiagnosticCategory.FOREGROUND_SERVICE,
    DiagnosticCategory.BACKGROUND,
)
