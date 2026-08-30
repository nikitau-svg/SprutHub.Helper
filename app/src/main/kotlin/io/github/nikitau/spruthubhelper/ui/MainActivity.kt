package io.github.nikitau.spruthubhelper.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.controls.ControlsProviderService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.health.connect.client.PermissionController
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.controls.ControlFactory
import io.github.nikitau.spruthubhelper.controls.SprutControlsProviderService
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.TileAssignment
import io.github.nikitau.spruthubhelper.tiles.TileComponents
import io.github.nikitau.spruthubhelper.health.HealthUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SprutHelperTheme { MainScreen() } }
    }

    companion object {
        const val EXTRA_CONTROL_ID = "control_id"
    }
}

private val SprutGreen = Color(0xFF1F7A5B)
private val SprutDark = Color(0xFF10251E)
private val SprutCream = Color(0xFFF6F7F1)

@Composable
private fun SprutHelperTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.lightColorScheme(
        primary = SprutGreen,
        onPrimary = Color.White,
        secondary = Color(0xFF4B6358),
        background = SprutCream,
        surface = Color.White,
        onBackground = SprutDark,
        onSurface = SprutDark,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val health by viewModel.healthState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.onHealthPermissionsChanged() }

    LaunchedEffect(viewModel) {
        viewModel.healthPermissionRequests.collect { permissions ->
            healthPermissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SprutHub Helper", fontWeight = FontWeight.SemiBold)
                        Text("Устройства Android", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SprutCream),
            )
        },
    ) { padding ->
        var search by rememberSaveable { mutableStateOf("") }
        val filtered = remember(ui.catalog.controls, search) {
            ui.catalog.controls.filter {
                search.isBlank() || listOf(it.title, it.subtitle, it.room).any { field -> field.contains(search, true) }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionCard(ui, busy, viewModel) }
            item { HealthCard(health, ui, viewModel) }
            item { TileSummaryCard(ui.assignments, ui.catalog.controls, viewModel) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Устройства", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Добавьте нужные элементы в системную панель Samsung или назначьте на плитку шторки.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Поиск") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    )
                }
            }
            if (filtered.isEmpty()) {
                item {
                    EmptyCatalogCard(
                        hasCache = ui.catalog.controls.isNotEmpty(),
                        onRefresh = viewModel::testConnection,
                    )
                }
            } else {
                items(filtered, key = SprutControl::id) { control ->
                    DeviceCard(control, ui.assignments, viewModel)
                }
            }
            item { DiagnosticsCard(ui) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HealthCard(health: HealthUiState, ui: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var roomMenu by remember { mutableStateOf(false) }
    var selectedRoomId by remember(health.binding?.roomId, ui.catalog.rooms) {
        mutableStateOf(health.binding?.roomId ?: ui.catalog.rooms.firstOrNull()?.id.orEmpty())
    }
    var selectedMetrics by remember(health.selectedMetrics) { mutableStateOf(health.selectedMetrics) }
    val selectedRoom = ui.catalog.rooms.firstOrNull { it.id == selectedRoomId }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFFFFE0E6)) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.padding(9.dp), tint = Color(0xFFB3264C))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Здоровье → SprutHub", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            !health.available -> "Health Connect недоступен"
                            health.binding != null -> health.message
                            else -> "Не настроено"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            Text(
                "Выбранные показатели передаются только по домашней сети на 192.168.1.135. Облачный fallback для здоровья отключён.",
                style = MaterialTheme.typography.bodySmall,
                color = SprutGreen,
            )
            if (health.binding != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Фоновое обновление каждые ~15 минут", modifier = Modifier.weight(1f))
                    Switch(checked = health.enabled, onCheckedChange = viewModel::setHealthEnabled)
                }
                health.lastSyncEpochMs?.let {
                    Text(
                        "Последняя синхронизация: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Показатели", fontWeight = FontWeight.SemiBold)
                    HealthMetric.entries.forEach { metric ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = metric in selectedMetrics,
                                onCheckedChange = { checked ->
                                    val next = if (checked) selectedMetrics + metric else selectedMetrics - metric
                                    if (next.isNotEmpty()) {
                                        selectedMetrics = next
                                        viewModel.saveHealthMetrics(next)
                                    }
                                },
                            )
                            Column {
                                Text(metric.title)
                                Text(metric.unit, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                    Box {
                        OutlinedButton(
                            onClick = { roomMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = health.binding == null && ui.catalog.rooms.isNotEmpty(),
                        ) {
                            Text(selectedRoom?.name ?: "Сначала загрузите комнаты SprutHub")
                        }
                        DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                            ui.catalog.rooms.forEach { room ->
                                DropdownMenuItem(
                                    text = { Text(room.name) },
                                    onClick = {
                                        selectedRoomId = room.id
                                        roomMenu = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::requestHealthPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = health.available && !health.syncing,
                    ) {
                        Text("Разрешить выбранные данные Health Connect")
                    }
                    if (health.binding == null) {
                        Button(
                            onClick = { viewModel.createHealthDevice(selectedRoomId) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedRoomId.isNotBlank() && health.available && !health.syncing,
                        ) {
                            if (health.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Создать устройство здоровья в SprutHub")
                        }
                    } else {
                        Button(
                            onClick = viewModel::syncHealth,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !health.syncing,
                        ) { Text(if (health.syncing) "Синхронизация…" else "Синхронизировать сейчас") }
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Открыть настройки батареи · выбрать «Без ограничений»") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(ui: MainUiState, busy: Boolean, viewModel: MainViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var mode by remember(ui.config.mode) { mutableStateOf(ui.config.mode) }
    var localUrl by remember(ui.config.localUrl) { mutableStateOf(ui.config.localUrl) }
    var cloudUrl by remember(ui.config.cloudUrl) { mutableStateOf(ui.config.cloudUrl) }
    var serial by remember(ui.config.serial) { mutableStateOf(ui.config.serial) }
    var email by remember(ui.config.email) { mutableStateOf(ui.config.email) }
    var password by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SprutDark),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ui.connection.phase)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Подключение", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        ui.connection.message,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Color.White)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { mode = item },
                        label = { Text(when (item) {
                            ConnectionMode.AUTO -> "Авто"
                            ConnectionMode.LOCAL -> "Дом"
                            ConnectionMode.CLOUD -> "Облако"
                        }) },
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serial,
                        onValueChange = { serial = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Серийный номер хаба") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("E-mail SprutHub") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (ui.config.password.isBlank()) "Пароль" else "Новый пароль (необязательно)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = localUrl,
                        onValueChange = { localUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Локальный WebSocket") },
                        supportingText = { Text("Незашифрованный доступ ограничен вашим IP 192.168.1.135") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Облачный WebSocket") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { viewModel.saveSettings(mode, localUrl, cloudUrl, serial, email, password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Icon(Icons.Rounded.Settings, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Сохранить настройки")
                    }
                }
            }
            Button(onClick = viewModel::testConnection, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(8.dp))
                Text("Проверить и загрузить устройства")
            }
        }
    }
}

@Composable
private fun StatusDot(phase: ConnectionPhase) {
    val color = when (phase) {
        ConnectionPhase.CONNECTED_LOCAL -> Color(0xFF63D6A5)
        ConnectionPhase.CONNECTED_CLOUD -> Color(0xFF76B8FF)
        ConnectionPhase.CONNECTING -> Color(0xFFFFC857)
        ConnectionPhase.ERROR -> Color(0xFFFF7B7B)
        ConnectionPhase.IDLE -> Color(0xFF9AA8A1)
    }
    Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun TileSummaryCard(assignments: List<TileAssignment>, controls: List<SprutControl>, viewModel: MainViewModel) {
    val names = controls.associateBy(SprutControl::id)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = SprutGreen)
                Spacer(Modifier.size(8.dp))
                Text("Плитки шторки", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${assignments.size}/12", style = MaterialTheme.typography.labelLarge)
            }
            if (assignments.isEmpty()) {
                Text("Пока не назначены. Выберите «Плитка» у нужного устройства.", style = MaterialTheme.typography.bodySmall)
            } else {
                assignments.forEach { assignment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${assignment.slot}", fontWeight = FontWeight.Bold, color = SprutGreen)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            names[assignment.controlId]?.title ?: "Недоступное устройство",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { viewModel.clearTile(assignment.slot) }) { Text("Убрать") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(control: SprutControl, assignments: List<TileAssignment>, viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(control.icon(), null, Modifier.padding(10.dp), tint = SprutGreen)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(control.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(control.room, control.displayValue).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        ControlsProviderService.requestAddControl(
                            context,
                            ComponentName(context, SprutControlsProviderService::class.java),
                            ControlFactory.stateless(context, control),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("В панель") }
                Box(Modifier.weight(1f)) {
                    Button(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Плитка") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        (1..12).forEach { slot ->
                            val currentId = assignments.firstOrNull { it.slot == slot }?.controlId
                            DropdownMenuItem(
                                text = {
                                    Text(if (currentId == null) "Плитка $slot · свободна" else "Плитка $slot · занята")
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.assignTile(slot, control.id)
                                    val next = assignments.filterNot { it.slot == slot || it.controlId == control.id } +
                                        TileAssignment(slot, control.id)
                                    TileComponents.syncEnabled(context, next)
                                    scope.launch { requestSystemTile(context as ComponentActivity, slot, control, viewModel) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun requestSystemTile(activity: ComponentActivity, slot: Int, control: SprutControl, viewModel: MainViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        viewModel.showNotice("Плитка настроена. Добавьте SprutHub $slot через редактирование шторки.")
        return
    }
    val manager = activity.getSystemService(StatusBarManager::class.java)
    manager.requestAddTileService(
        TileComponents.component(activity, slot),
        control.title,
        AndroidIcon.createWithResource(activity, R.drawable.ic_tile),
        activity.mainExecutor,
    ) { result ->
        if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
            result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        ) {
            viewModel.showNotice("Плитка настроена; при необходимости добавьте её через редактирование шторки.")
        }
    }
}

@Composable
private fun EmptyCatalogCard(hasCache: Boolean, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.DevicesOther, null, Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text(if (hasCache) "Ничего не найдено по запросу" else "Сначала загрузите устройства SprutHub")
            if (!hasCache) TextButton(onClick = onRefresh) { Text("Загрузить") }
        }
    }
}

@Composable
private fun DiagnosticsCard(ui: MainUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Диагностика", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (ui.catalog.hubVersion.isNotBlank()) AssistChip(onClick = {}, label = { Text(ui.catalog.hubVersion) })
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    if (ui.diagnostics.isEmpty()) Text("Событий пока нет", style = MaterialTheme.typography.bodySmall)
                    ui.diagnostics.take(12).forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                        Text(
                            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.epochMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            event.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (event.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun SprutControl.icon(): ImageVector = when (kind) {
    DeviceKind.LIGHT -> Icons.Rounded.Lightbulb
    DeviceKind.LOCK, DeviceKind.SECURITY -> Icons.Rounded.Lock
    DeviceKind.THERMOSTAT -> Icons.Rounded.Thermostat
    DeviceKind.SCENE -> Icons.Rounded.PlayArrow
    DeviceKind.SWITCH, DeviceKind.OUTLET -> Icons.Rounded.PowerSettingsNew
    else -> Icons.Rounded.DevicesOther
}
