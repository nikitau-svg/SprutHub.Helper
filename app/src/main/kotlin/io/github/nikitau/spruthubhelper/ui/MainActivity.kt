package io.github.nikitau.spruthubhelper.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.controls.ControlsProviderService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.controls.ControlFactory
import io.github.nikitau.spruthubhelper.controls.SprutControlsProviderService
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSensorCategory
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.TileAssignment
import io.github.nikitau.spruthubhelper.tiles.TileComponents
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.tiles.TileInstallStateStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SprutHelperTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.phone.refreshRuntimeStatus()
        AppGraph.presence.refreshPermissionState()
    }

    companion object {
        const val EXTRA_CONTROL_ID = "control_id"
    }
}

private val SprutGreen = Color(0xFF72DDB2)
private val SprutDark = Color(0xFF0B1412)
private val SprutSurface = Color(0xFF111D1A)
private val SprutSurfaceElevated = Color(0xFF182824)
private val SprutField = Color(0xFF20322D)
private val SprutFieldFocused = Color(0xFF263D36)
private val SprutText = Color(0xFFF1F7F4)
private val SprutTextMuted = Color(0xFFB6C8C0)
private val SprutOutline = Color(0xFF748A81)
private val SprutError = Color(0xFFFFB4AB)

private data class CloudEndpointPreset(val label: String, val url: String)

private val CloudEndpointPresets = listOf(
    CloudEndpointPreset("web.ru", "wss://web.spruthub.ru/spruthub"),
    CloudEndpointPreset("beta.ru", "wss://beta.spruthub.ru/spruthub"),
    CloudEndpointPreset("web.com", "wss://web.spruthub.com/spruthub"),
    CloudEndpointPreset("beta.com", "wss://beta.spruthub.com/spruthub"),
)

@Composable
private fun SprutHelperTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.darkColorScheme(
        primary = SprutGreen,
        onPrimary = Color(0xFF003827),
        primaryContainer = Color(0xFF15513D),
        onPrimaryContainer = Color(0xFFA7F2D1),
        secondary = Color(0xFFB4CCBF),
        onSecondary = Color(0xFF20352C),
        secondaryContainer = Color(0xFF374B42),
        onSecondaryContainer = Color(0xFFD0E8DA),
        background = SprutDark,
        surface = SprutSurface,
        surfaceVariant = SprutSurfaceElevated,
        onBackground = SprutText,
        onSurface = SprutText,
        onSurfaceVariant = SprutTextMuted,
        outline = SprutOutline,
        outlineVariant = Color(0xFF40544C),
        error = SprutError,
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun sprutTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SprutText,
    unfocusedTextColor = SprutText,
    disabledTextColor = SprutText.copy(alpha = 0.5f),
    errorTextColor = SprutText,
    focusedContainerColor = SprutFieldFocused,
    unfocusedContainerColor = SprutField,
    disabledContainerColor = SprutField.copy(alpha = 0.5f),
    errorContainerColor = SprutField,
    cursorColor = SprutGreen,
    errorCursorColor = SprutError,
    focusedBorderColor = SprutGreen,
    unfocusedBorderColor = SprutOutline,
    disabledBorderColor = SprutOutline.copy(alpha = 0.45f),
    errorBorderColor = SprutError,
    focusedLabelColor = SprutGreen,
    unfocusedLabelColor = SprutTextMuted,
    disabledLabelColor = SprutTextMuted.copy(alpha = 0.5f),
    errorLabelColor = SprutError,
    focusedLeadingIconColor = SprutGreen,
    unfocusedLeadingIconColor = SprutTextMuted,
    disabledLeadingIconColor = SprutTextMuted.copy(alpha = 0.5f),
    errorLeadingIconColor = SprutError,
    focusedSupportingTextColor = SprutTextMuted,
    unfocusedSupportingTextColor = SprutTextMuted,
    disabledSupportingTextColor = SprutTextMuted.copy(alpha = 0.5f),
    errorSupportingTextColor = SprutError,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val health by viewModel.healthState.collectAsState()
    val phone by viewModel.phoneState.collectAsState()
    val presence by viewModel.presenceState.collectAsState()
    val installedTileSlots by TileInstallStateStore.installedSlots.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    var iconTargetId by remember { mutableStateOf<String?>(null) }
    var iconRevision by remember { mutableStateOf(0) }
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.onHealthPermissionsChanged() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPhoneStatus()
        if (granted) viewModel.setPhoneSyncMode(PhoneSyncMode.LIVE)
        else viewModel.showNotice("Без разрешения на уведомления постоянный режим недоступен")
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onLocationPermissionsChanged()
    }
    val customIconLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val controlId = iconTargetId
        iconTargetId = null
        if (uri != null && controlId != null) {
            screenScope.launch {
                val result = withContext(Dispatchers.IO) {
                    CustomIconManager(context).save(controlId, uri)
                }
                result.onSuccess {
                    iconRevision += 1
                    TileComponents.syncEnabled(context, viewModel.uiState.value.assignments)
                    viewModel.showNotice("PNG-иконка сохранена для Android-панели и плитки")
                }.onFailure { error ->
                    viewModel.showNotice(error.message ?: "Не удалось прочитать изображение")
                }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.healthPermissionRequests.collect { permissions ->
            healthPermissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.tileAddRequests.collect { request ->
            val control = viewModel.uiState.value.catalog.controls.firstOrNull { it.id == request.controlId }
            val activity = context as? ComponentActivity
            if (control == null || activity == null) {
                viewModel.showNotice("Плитка настроена, но системное окно не удалось открыть")
            } else {
                TileComponents.enableSlot(context, request.slot)
                // PackageManager enables a previously hidden TileService
                // asynchronously on some Samsung builds.
                delay(350)
                requestSystemTile(activity, request.slot, control, viewModel)
            }
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SprutDark,
                    titleContentColor = SprutText,
                ),
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
            item {
                PhoneCard(
                    phone = phone,
                    ui = ui,
                    viewModel = viewModel,
                    onRequestLiveMode = {
                        if (
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.setPhoneSyncMode(PhoneSyncMode.LIVE)
                        } else {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
            item {
                PresenceCard(
                    presence = presence,
                    ui = ui,
                    viewModel = viewModel,
                    onRequestForegroundLocation = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    },
                    onOpenBackgroundLocationSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
            item {
                TileSummaryCard(
                    assignments = ui.assignments,
                    controls = ui.catalog.controls,
                    installedSlots = installedTileSlots,
                    viewModel = viewModel,
                )
            }
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
                        colors = sprutTextFieldColors(),
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
                    DeviceCard(
                        control = control,
                        assignments = ui.assignments,
                        viewModel = viewModel,
                        iconRevision = iconRevision,
                        onPickIcon = {
                            iconTargetId = control.id
                            customIconLauncher.launch("image/*")
                        },
                    )
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
    var confirmRecreate by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
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
                "Выбранные показатели передаются только на настроенный локальный адрес. Облачный fallback для здоровья отключён.",
                style = MaterialTheme.typography.bodySmall,
                color = SprutGreen,
            )
            if (health.binding != null && !health.configurationMatches) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Состав изменён. Чтобы снятые показатели исчезли из SprutHub, виртуальный аксессуар нужно пересоздать.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { confirmRecreate = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !health.syncing,
                    ) { Text("Применить новый состав в SprutHub") }
                }
            }
            when {
                !health.allSelectedPermissionsGranted -> Text(
                    "Сначала разрешите все отмеченные показатели Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                health.backgroundReadAvailable && !health.backgroundReadGranted -> Text(
                    "Для автообновления отдельно разрешите фоновое чтение Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                !health.backgroundReadAvailable -> Text(
                    "Эта версия Health Connect поддерживает только ручную синхронизацию.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (health.binding != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Фоновое обновление каждые ~15 минут", modifier = Modifier.weight(1f))
                    Switch(
                        checked = health.enabled,
                        onCheckedChange = viewModel::setHealthEnabled,
                        enabled = health.backgroundReadAvailable && health.backgroundReadGranted && !health.syncing,
                    )
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
                        Text(
                            if (health.allSelectedPermissionsGranted &&
                                (!health.backgroundReadAvailable || health.backgroundReadGranted)
                            ) {
                                "Изменить разрешения Health Connect"
                            } else {
                                "Разрешить выбранные данные Health Connect"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val manage = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                            runCatching { context.startActivity(manage) }
                                .onFailure {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:com.google.android.apps.healthdata"),
                                        ),
                                    )
                                }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = health.available,
                    ) { Text("Управлять доступом в Health Connect") }
                    TextButton(
                        onClick = { confirmRevoke = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = health.available,
                    ) { Text("Отключить синхронизацию и отозвать весь доступ") }
                    if (health.binding == null) {
                        Button(
                            onClick = { viewModel.createHealthDevice(selectedRoomId) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedRoomId.isNotBlank() && health.available &&
                                health.allSelectedPermissionsGranted && !health.syncing,
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
                        if (health.configurationMatches) {
                            TextButton(
                                onClick = { confirmRecreate = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !health.syncing,
                            ) { Text("Пересоздать и очистить поля старой версии") }
                        }
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
    if (confirmRecreate) {
        AlertDialog(
            onDismissRequest = { confirmRecreate = false },
            title = { Text("Пересоздать устройство здоровья?") },
            text = {
                Text(
                    "SprutHub не умеет удалять отдельные сервисы. Приложение удалит только созданный им виртуальный аксессуар здоровья и создаст новый с отмеченными показателями.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecreate = false
                    viewModel.recreateHealthDevice()
                }) { Text("Пересоздать") }
            },
            dismissButton = { TextButton(onClick = { confirmRecreate = false }) { Text("Отмена") } },
        )
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Отозвать весь доступ к здоровью?") },
            text = {
                Text("Фоновая синхронизация остановится, а все разрешения Health Connect для SprutHub Helper будут отозваны. Созданный аксессуар в SprutHub останется, но обновляться не будет.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    viewModel.revokeAllHealthPermissions()
                }) { Text("Отозвать") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PhoneCard(
    phone: PhoneUiState,
    ui: MainUiState,
    viewModel: MainViewModel,
    onRequestLiveMode: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var roomMenu by remember { mutableStateOf(false) }
    var confirmRecreate by remember { mutableStateOf(false) }
    var selectedRoomId by remember(phone.binding?.roomId, ui.catalog.rooms) {
        mutableStateOf(phone.binding?.roomId ?: ui.catalog.rooms.firstOrNull()?.id.orEmpty())
    }
    var selectedSensors by remember(phone.selectedSensors) { mutableStateOf(phone.selectedSensors) }
    val selectedRoom = ui.catalog.rooms.firstOrNull { it.id == selectedRoomId }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFFDCE9FF)) {
                    Icon(Icons.Rounded.Smartphone, null, Modifier.padding(9.dp), tint = Color(0xFF315DA8))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Телефон → SprutHub", fontWeight = FontWeight.Bold)
                    Text(
                        if (phone.binding == null) "Не настроено" else phone.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }

            Text(
                "Передаются только отмеченные показатели. В режиме «Авто» вне дома приложение может использовать настроенное облако SprutHub.",
                style = MaterialTheme.typography.bodySmall,
                color = SprutGreen,
            )

            if (phone.binding != null && !phone.configurationMatches) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Выбор изменён. Для полного удаления снятых показателей примените новый состав устройства.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { confirmRecreate = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !phone.syncing,
                    ) { Text("Применить новый состав в SprutHub") }
                }
            }

            if (phone.binding != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Фоновая синхронизация")
                        Text(
                            if (phone.syncSettings.mode == PhoneSyncMode.LIVE) {
                                if (phone.monitorRunning) "Постоянное подключение работает" else "Постоянное подключение остановлено"
                            } else {
                                "Контрольный запуск примерно раз в 15 минут"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = phone.syncSettings.enabled,
                        onCheckedChange = viewModel::setPhoneEnabled,
                        enabled = !phone.syncing,
                    )
                }
                phone.lastSyncEpochMs?.let {
                    Text(
                        "Последняя синхронизация: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Режим обновления", fontWeight = FontWeight.SemiBold)
                    PhoneSyncMode.entries.forEach { mode ->
                        FilterChip(
                            selected = phone.syncSettings.mode == mode,
                            onClick = {
                                if (mode == PhoneSyncMode.LIVE) onRequestLiveMode()
                                else viewModel.setPhoneSyncMode(mode)
                            },
                            label = { Text(mode.title) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (phone.syncSettings.mode == PhoneSyncMode.LIVE) {
                        Text("Контрольный опрос", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhonePollInterval.entries.forEach { interval ->
                                FilterChip(
                                    selected = phone.syncSettings.pollInterval == interval,
                                    onClick = { viewModel.setPhonePollInterval(interval) },
                                    label = { Text(interval.title) },
                                )
                            }
                        }
                        Text(
                            "Зарядка, экран, энергосбережение и смена сети отправляются по событию; интервал нужен как страховочный опрос.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()
                    Text("Что передавать", fontWeight = FontWeight.SemiBold)
                    PhoneSensorCategory.entries.forEach { category ->
                        Text(category.title, color = SprutGreen, fontWeight = FontWeight.SemiBold)
                        PhoneSensor.entries.filter { it.category == category }.forEach { sensor ->
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(
                                    checked = sensor in selectedSensors,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) selectedSensors + sensor else selectedSensors - sensor
                                        if (next.isNotEmpty()) {
                                            selectedSensors = next
                                            viewModel.savePhoneSensors(next)
                                        }
                                    },
                                    enabled = !phone.syncing,
                                )
                                Column(Modifier.weight(1f).padding(top = 9.dp)) {
                                    Text(sensor.title)
                                    Text(
                                        buildString {
                                            append(sensor.description)
                                            if (sensor.unit.isNotBlank()) append(" · ").append(sensor.unit)
                                            append(" · ").append(sensor.updateKind.title)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("Разрешения и надёжность", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Текущий набор показателей не требует геолокации, доступа к звонкам, Bluetooth или файлам. Такие датчики будут добавляться отдельными выключенными группами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReliabilityRow(
                        title = "Уведомления",
                        ready = phone.notificationPermissionGranted,
                        readyText = "разрешены",
                        missingText = "нужны для постоянного режима",
                    )
                    ReliabilityRow(
                        title = "Оптимизация батареи",
                        ready = phone.batteryOptimizationIgnored,
                        readyText = "без ограничений",
                        missingText = "Android может откладывать фон",
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Настроить уведомления") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Проверить оптимизацию батареи") }
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                        OutlinedButton(
                            onClick = {
                                val samsungSettings = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                                    .setPackage("com.samsung.android.lool")
                                    .putExtra("activity_type", 2)
                                runCatching { context.startActivity(samsungSettings) }
                                    .onFailure {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Samsung · добавить в «Не переводить в спящий режим»") }
                    }

                    HorizontalDivider()
                    Box {
                        OutlinedButton(
                            onClick = { roomMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = phone.binding == null && ui.catalog.rooms.isNotEmpty(),
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
                    if (phone.binding == null) {
                        Button(
                            onClick = { viewModel.createPhoneDevice(selectedRoomId) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedRoomId.isNotBlank() && !phone.syncing,
                        ) {
                            if (phone.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("Создать устройство телефона в SprutHub")
                        }
                    } else {
                        Button(
                            onClick = viewModel::syncPhone,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !phone.syncing,
                        ) { Text(if (phone.syncing) "Синхронизация…" else "Синхронизировать сейчас") }
                    }
                }
            }
        }
    }
    if (confirmRecreate) {
        AlertDialog(
            onDismissRequest = { confirmRecreate = false },
            title = { Text("Пересоздать устройство телефона?") },
            text = {
                Text(
                    "Приложение удалит только созданный им виртуальный аксессуар телефона и создаст новый с отмеченными показателями. Обычные устройства SprutHub не затрагиваются.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRecreate = false
                    viewModel.recreatePhoneDevice()
                }) { Text("Пересоздать") }
            },
            dismissButton = { TextButton(onClick = { confirmRecreate = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ReliabilityRow(
    title: String,
    ready: Boolean,
    readyText: String,
    missingText: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Text(
            if (ready) "✓ $readyText" else "• $missingText",
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) SprutGreen else MaterialTheme.colorScheme.error,
        )
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
    var localPassword by rememberSaveable { mutableStateOf("") }
    var cloudPassword by rememberSaveable { mutableStateOf("") }
    var showLocalPassword by rememberSaveable { mutableStateOf(false) }
    var showCloudPassword by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceElevated),
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
                        colors = sprutTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("E-mail SprutHub") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        colors = sprutTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = localUrl,
                        onValueChange = { localUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Локальный адрес") },
                        supportingText = { Text("IP, имя .local, http(s):// или полный ws(s):// адрес") },
                        singleLine = true,
                        colors = sprutTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = localPassword,
                        onValueChange = { localPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (ui.config.hasLocalPassword) "Новый локальный пароль (необязательно)" else "Локальный пароль")
                        },
                        supportingText = { if (ui.config.hasLocalPassword) Text("Локальный пароль уже сохранён") },
                        visualTransformation = if (showLocalPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showLocalPassword = !showLocalPassword }) {
                                Icon(if (showLocalPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                            }
                        },
                        singleLine = true,
                        colors = sprutTextFieldColors(),
                    )
                    Text("Облачный сервер", fontWeight = FontWeight.SemiBold)
                    CloudEndpointPresets.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowPresets.forEach { preset ->
                                FilterChip(
                                    selected = cloudUrl.equals(preset.url, ignoreCase = true),
                                    onClick = { cloudUrl = preset.url },
                                    label = { Text(preset.label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    FilterChip(
                        selected = CloudEndpointPresets.none { cloudUrl.equals(it.url, ignoreCase = true) },
                        onClick = { if (CloudEndpointPresets.any { cloudUrl.equals(it.url, true) }) cloudUrl = "" },
                        label = { Text("Свой адрес") },
                    )
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = { cloudUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Облачный адрес") },
                        supportingText = { Text("Можно выбрать пресет выше или ввести свой https/wss адрес") },
                        singleLine = true,
                        colors = sprutTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = cloudPassword,
                        onValueChange = { cloudPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (ui.config.hasCloudPassword) "Новый облачный пароль (необязательно)" else "Облачный пароль")
                        },
                        supportingText = { if (ui.config.hasCloudPassword) Text("Облачный пароль уже сохранён") },
                        visualTransformation = if (showCloudPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showCloudPassword = !showCloudPassword }) {
                                Icon(if (showCloudPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                            }
                        },
                        singleLine = true,
                        colors = sprutTextFieldColors(),
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.saveSettings(
                                mode,
                                localUrl,
                                cloudUrl,
                                serial,
                                email,
                                localPassword,
                                cloudPassword,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Icon(Icons.Rounded.Settings, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Сохранить настройки")
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.saveAndTestSettings(
                        mode,
                        localUrl,
                        cloudUrl,
                        serial,
                        email,
                        localPassword,
                        cloudPassword,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(8.dp))
                Text("Сохранить, проверить и загрузить")
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
private fun TileSummaryCard(
    assignments: List<TileAssignment>,
    controls: List<SprutControl>,
    installedSlots: Set<Int>,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                Text("Пока не назначены. Нажмите «Добавить плитку» у нужного устройства.", style = MaterialTheme.typography.bodySmall)
            } else {
                assignments.forEach { assignment ->
                    val control = names[assignment.controlId]
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${assignment.slot}", fontWeight = FontWeight.Bold, color = SprutGreen)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    control?.title ?: "Недоступное устройство",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (assignment.slot in installedSlots) "✓ Добавлена в системную шторку"
                                    else "Только назначена внутри приложения",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (assignment.slot in installedSlots) SprutGreen
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                            if (assignment.slot !in installedSlots && control != null) {
                                TextButton(onClick = {
                                    scope.launch {
                                        TileComponents.enableSlot(context, assignment.slot)
                                        delay(350)
                                        requestSystemTile(
                                            context as ComponentActivity,
                                            assignment.slot,
                                            control,
                                            viewModel,
                                        )
                                    }
                                }) { Text("Добавить") }
                            }
                            TextButton(onClick = { viewModel.clearTile(assignment.slot) }) { Text("Освободить") }
                        }
                    }
                }
            }
            Text(
                "Назначение и добавление в Android — два отдельных шага. После системного подтверждения статус станет зелёным.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    control: SprutControl,
    assignments: List<TileAssignment>,
    viewModel: MainViewModel,
    iconRevision: Int,
    onPickIcon: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val iconManager = remember { CustomIconManager(context) }
    var hasCustomIcon by remember(control.id, iconRevision) {
        mutableStateOf(iconManager.hasIcon(control.id))
    }
    val assignedSlot = assignments.firstOrNull { it.controlId == control.id }?.slot
    val firstFreeSlot = (1..12).firstOrNull { slot -> assignments.none { it.slot == slot } }
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
                    Button(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(assignedSlot?.let { "Плитка $it" } ?: "Добавить плитку")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (assignedSlot == null && firstFreeSlot != null) {
                            DropdownMenuItem(
                                text = { Text("Добавить новой · слот $firstFreeSlot") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.assignTile(firstFreeSlot, control.id)
                                },
                            )
                            HorizontalDivider()
                        } else if (assignedSlot != null) {
                            DropdownMenuItem(
                                text = { Text("Повторно открыть добавление плитки $assignedSlot") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.assignTile(assignedSlot, control.id)
                                },
                            )
                            HorizontalDivider()
                        }
                        (1..12).forEach { slot ->
                            val currentId = assignments.firstOrNull { it.slot == slot }?.controlId
                            if (slot != firstFreeSlot || assignedSlot != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                currentId == control.id -> "Плитка $slot · уже назначена"
                                                currentId == null -> "Выбрать слот $slot · свободен"
                                                else -> "Заменить занятую плитку $slot"
                                            },
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.assignTile(slot, control.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickIcon, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Image, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (hasCustomIcon) "Заменить PNG" else "Своя иконка")
                }
                if (hasCustomIcon) {
                    TextButton(
                        onClick = {
                            if (iconManager.remove(control.id)) {
                                hasCustomIcon = false
                                TileComponents.syncEnabled(context, assignments)
                                viewModel.showNotice("Пользовательская иконка удалена")
                            }
                        },
                    ) { Text("Сбросить") }
                }
            }
            Text(
                "Лучше квадратный PNG с прозрачным фоном. Иконка применяется в Android; веб-интерфейс SprutHub не публикует поле для своей картинки.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        CustomIconManager(activity).loadIcon(control.id) ?: TileIconResolver.icon(activity, control.kind),
        activity.mainExecutor,
    ) { result ->
        when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                TileInstallStateStore.markAdded(activity, slot)
                viewModel.showNotice("Плитка $slot добавлена в системную шторку")
            }
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                TileInstallStateStore.markAdded(activity, slot)
                viewModel.showNotice("Плитка $slot уже была в шторке; назначение обновлено")
            }
            else -> {
                viewModel.showNotice(
                    "Плитка $slot назначена, но Android не добавил её (код $result). Нажмите «Добавить» в разделе плиток ещё раз.",
                )
            }
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
