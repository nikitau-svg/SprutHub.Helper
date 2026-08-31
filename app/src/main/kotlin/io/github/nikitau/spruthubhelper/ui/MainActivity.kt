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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Menu
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
import io.github.nikitau.spruthubhelper.controls.DevicePanelSupport
import io.github.nikitau.spruthubhelper.controls.SprutPanelPreviewActivity
import io.github.nikitau.spruthubhelper.controls.SprutControlsProviderService
import io.github.nikitau.spruthubhelper.data.AccessoryControlGroup
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.PanelItem
import io.github.nikitau.spruthubhelper.data.PanelItemSize
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSensorCategory
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.TileAssignment
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.data.groupControlsByAccessory
import io.github.nikitau.spruthubhelper.tiles.TileComponents
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.tiles.TileInstallStateStore
import io.github.nikitau.spruthubhelper.widget.SprutAppWidgetProvider
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
        AppGraph.health.refreshRuntimeStatus()
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
internal fun SprutHelperTheme(content: @Composable () -> Unit) {
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
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsSection = SettingsSection.entries.firstOrNull { it.name == settingsSectionName }
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
    val watchdogNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPhoneStatus()
        if (granted) viewModel.setPhoneWatchdogEnabled(true)
        else viewModel.showNotice("Без разрешения Android не покажет предупреждение о синхронизации")
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
                    SprutAppWidgetProvider.updateAll(context)
                    viewModel.showNotice("PNG-иконка сохранена для виджета, Android-панели и плитки")
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

    LaunchedEffect(viewModel) {
        viewModel.panelAddRequests.collect { cardId ->
            val controls = viewModel.uiState.value.catalog.controls
            val cards = buildServiceControlCards(controls)
            val control = cards.firstOrNull { it.id == cardId }?.primaryControl
                ?: controls.firstOrNull { it.id == cardId }
            if (control != null && DevicePanelSupport.hasSystemControls(context)) {
                ControlsProviderService.requestAddControl(
                    context,
                    ComponentName(context, SprutControlsProviderService::class.java),
                    ControlFactory.stateless(context, control),
                )
            }
        }
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    BackHandler(enabled = settingsOpen) {
        if (settingsSection != null) settingsSectionName = null
        else settingsOpen = false
    }

    val requestLiveMode = {
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
    }
    val requestWatchdog = {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.setPhoneWatchdogEnabled(true)
        } else {
            watchdogNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val requestForegroundLocation = {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }
    val openBackgroundLocationSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                !settingsOpen -> "SprutHub Helper"
                                settingsSection != null -> settingsSection.title
                                else -> "Настройки"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                !settingsOpen -> "Устройства Android"
                                settingsSection != null -> settingsSection.description
                                else -> "Подключение, данные и надёжность"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (settingsOpen) {
                        IconButton(
                            onClick = {
                                if (settingsSection != null) settingsSectionName = null
                                else settingsOpen = false
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (!settingsOpen) {
                        IconButton(onClick = {
                            settingsSectionName = null
                            settingsOpen = true
                        }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Открыть настройки")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SprutDark,
                    titleContentColor = SprutText,
                ),
            )
        },
    ) { padding ->
        if (!settingsOpen) {
            HomeContent(
                ui = ui,
                health = health,
                phone = phone,
                presence = presence,
                installedTileSlots = installedTileSlots,
                iconRevision = iconRevision,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(padding),
                onOpenSettings = { section ->
                    settingsSectionName = section.name
                    settingsOpen = true
                },
                onPickIcon = { controlId ->
                    iconTargetId = controlId
                    customIconLauncher.launch("image/*")
                },
            )
        } else {
            SettingsContent(
                selectedSection = settingsSection,
                ui = ui,
                busy = busy,
                health = health,
                phone = phone,
                presence = presence,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSelectSection = { settingsSectionName = it.name },
                onRequestLiveMode = requestLiveMode,
                onRequestWatchdog = requestWatchdog,
                onRequestForegroundLocation = requestForegroundLocation,
                onOpenBackgroundLocationSettings = openBackgroundLocationSettings,
            )
        }
    }
}

@Composable
private fun HomeContent(
    ui: MainUiState,
    health: HealthUiState,
    phone: PhoneUiState,
    presence: PresenceUiState,
    installedTileSlots: Set<Int>,
    iconRevision: Int,
    viewModel: MainViewModel,
    modifier: Modifier,
    onOpenSettings: (SettingsSection) -> Unit,
    onPickIcon: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(ui.catalog.controls, search) {
        groupControlsByAccessory(ui.catalog.controls).filter { it.matches(search) }
    }
    val setupItems = buildSetupOverview(ui, health, phone, presence)

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SetupOverviewCard(
                items = setupItems,
                onOpenSettings = onOpenSettings,
            )
        }
        item {
            PanelSummaryCard(
                items = ui.panelItems,
                controls = ui.catalog.controls,
                viewModel = viewModel,
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
                    "Добавьте сервисы в панель устройств или назначьте отдельные плитки шторки.",
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
            items(filtered, key = AccessoryControlGroup::key) { group ->
                AccessoryCard(
                    group = group,
                    assignments = ui.assignments,
                    panelItems = ui.panelItems,
                    viewModel = viewModel,
                    iconRevision = iconRevision,
                    onPickIcon = { onPickIcon(it) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsContent(
    selectedSection: SettingsSection?,
    ui: MainUiState,
    busy: Boolean,
    health: HealthUiState,
    phone: PhoneUiState,
    presence: PresenceUiState,
    viewModel: MainViewModel,
    modifier: Modifier,
    onSelectSection: (SettingsSection) -> Unit,
    onRequestLiveMode: () -> Unit,
    onRequestWatchdog: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onOpenBackgroundLocationSettings: () -> Unit,
) {
    if (selectedSection == null) {
        SettingsHub(
            ui = ui,
            health = health,
            phone = phone,
            presence = presence,
            modifier = modifier,
            onSelectSection = onSelectSection,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            when (selectedSection) {
                SettingsSection.CONNECTION -> ConnectionCard(ui, busy, viewModel, expandedByDefault = true)
                SettingsSection.HEALTH -> HealthCard(health, ui, viewModel, expandedByDefault = true)
                SettingsSection.PHONE -> PhoneCard(
                    phone = phone,
                    ui = ui,
                    viewModel = viewModel,
                    onRequestLiveMode = onRequestLiveMode,
                    onRequestWatchdog = onRequestWatchdog,
                    expandedByDefault = true,
                )
                SettingsSection.PRESENCE -> PresenceCard(
                    presence = presence,
                    ui = ui,
                    viewModel = viewModel,
                    onRequestForegroundLocation = onRequestForegroundLocation,
                    onOpenBackgroundLocationSettings = onOpenBackgroundLocationSettings,
                    expandedByDefault = true,
                )
                SettingsSection.DIAGNOSTICS -> DiagnosticsCard(ui, expandedByDefault = true)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SetupOverviewCard(
    items: List<SetupOverviewItem>,
    onOpenSettings: (SettingsSection) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceElevated),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Готовность", fontWeight = FontWeight.Bold)
                    Text(
                        "Для плиток и панели обязателен только SprutHub. Остальные источники подключаются по желанию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = SprutGreen)
            }
            Spacer(Modifier.height(8.dp))
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SetupOverviewRow(item = item, onClick = { onOpenSettings(item.section) })
            }
        }
    }
}

@Composable
private fun SetupOverviewRow(item: SetupOverviewItem, onClick: () -> Unit) {
    val statusColor = setupToneColor(item.tone)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = statusColor.copy(alpha = 0.16f),
        ) {
            Icon(
                item.section.icon(),
                contentDescription = null,
                modifier = Modifier.padding(8.dp).size(20.dp),
                tint = statusColor,
            )
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.section.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(item.status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(4.dp))
        Icon(Icons.Rounded.ChevronRight, contentDescription = "Открыть")
    }
}

@Composable
private fun SettingsHub(
    ui: MainUiState,
    health: HealthUiState,
    phone: PhoneUiState,
    presence: PresenceUiState,
    modifier: Modifier,
    onSelectSection: (SettingsSection) -> Unit,
) {
    val setupItems = buildSetupOverview(ui, health, phone, presence)
    val hasDiagnosticErrors = ui.diagnostics.any { it.isError }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SprutSurfaceElevated),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Всё служебное — здесь", fontWeight = FontWeight.Bold)
                    Text(
                        "Подключение, разрешения, фоновые режимы и восстановление. Управление устройствами остаётся на главном экране.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(setupItems, key = { it.section.name }) { item ->
            SettingsEntryCard(
                section = item.section,
                status = item.status,
                statusTone = item.tone,
                onClick = { onSelectSection(item.section) },
            )
        }
        item {
            SettingsEntryCard(
                section = SettingsSection.DIAGNOSTICS,
                status = if (hasDiagnosticErrors) "Есть ошибки в журнале" else "Журнал и проверка состояния",
                statusTone = if (hasDiagnosticErrors) SetupTone.ATTENTION else SetupTone.READY,
                onClick = { onSelectSection(SettingsSection.DIAGNOSTICS) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsEntryCard(
    section: SettingsSection,
    status: String,
    statusTone: SetupTone,
    onClick: () -> Unit,
) {
    val statusColor = setupToneColor(statusTone)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = statusColor.copy(alpha = 0.14f)) {
                Icon(
                    section.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = statusColor,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, fontWeight = FontWeight.SemiBold)
                Text(
                    section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Открыть")
        }
    }
}

@Composable
private fun setupToneColor(tone: SetupTone): Color = when (tone) {
    SetupTone.READY -> SprutGreen
    SetupTone.ATTENTION -> MaterialTheme.colorScheme.error
    SetupTone.OPTIONAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.CONNECTION -> Icons.Rounded.Settings
    SettingsSection.HEALTH -> Icons.Rounded.Favorite
    SettingsSection.PHONE -> Icons.Rounded.Smartphone
    SettingsSection.PRESENCE -> Icons.Rounded.LocationOn
    SettingsSection.DIAGNOSTICS -> Icons.Rounded.BugReport
}

@Composable
internal fun NextActionCard(
    guidance: SectionGuidance,
    actionEnabled: Boolean = true,
    darkSurface: Boolean = false,
    onAction: (GuidanceAction) -> Unit,
) {
    val accent = when (guidance.tone) {
        SetupTone.READY -> SprutGreen
        SetupTone.ATTENTION -> Color(0xFFFFC857)
        SetupTone.OPTIONAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = if (darkSurface) 0.14f else 0.09f),
            contentColor = if (darkSurface) Color.White else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                guidance.progress,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(guidance.title, fontWeight = FontWeight.Bold)
            Text(
                guidance.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (darkSurface) Color.White.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val action = guidance.action
            val label = guidance.actionLabel
            if (action != null && label != null) {
                Spacer(Modifier.height(2.dp))
                if (guidance.tone == SetupTone.READY) {
                    OutlinedButton(
                        onClick = { onAction(action) },
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                } else {
                    Button(
                        onClick = { onAction(action) },
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Обслуживание и сброс", fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Скрыть обслуживание" else "Открыть обслуживание",
        )
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun HealthCard(
    health: HealthUiState,
    ui: MainUiState,
    viewModel: MainViewModel,
    expandedByDefault: Boolean = false,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(expandedByDefault) { mutableStateOf(expandedByDefault) }
    var roomMenu by remember { mutableStateOf(false) }
    var confirmRecreate by remember { mutableStateOf(false) }
    var maintenanceExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var selectedRoomId by remember(health.binding?.roomId, ui.catalog.rooms) {
        mutableStateOf(health.binding?.roomId ?: ui.catalog.rooms.firstOrNull()?.id.orEmpty())
    }
    var selectedMetrics by remember(health.selectedMetrics) { mutableStateOf(health.selectedMetrics) }
    val selectedRoom = ui.catalog.rooms.firstOrNull { it.id == selectedRoomId }
    val selectionMatchesDevice = health.binding == null ||
        health.binding.targets.map { it.key }.toSet() == selectedMetrics.map(HealthMetric::name).toSet()
    val guidance = healthGuidance(health, selectionMatchesDevice)
    val openHealthConnectSettings: () -> Unit = {
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
        Unit
    }

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
            NextActionCard(
                guidance = guidance,
                actionEnabled = !health.syncing && when (guidance.action) {
                    GuidanceAction.CREATE_HEALTH_DEVICE -> selectedRoomId.isNotBlank()
                    else -> true
                },
                onAction = { action ->
                    when (action) {
                        GuidanceAction.OPEN_HEALTH_CONNECT -> openHealthConnectSettings()
                        GuidanceAction.REQUEST_HEALTH_PERMISSIONS -> viewModel.requestHealthPermissions()
                        GuidanceAction.CREATE_HEALTH_DEVICE -> viewModel.createHealthDevice(selectedRoomId)
                        GuidanceAction.RECREATE_HEALTH_DEVICE -> confirmRecreate = true
                        GuidanceAction.ENABLE_HEALTH_BACKGROUND -> viewModel.setHealthEnabled(true)
                        GuidanceAction.SYNC_HEALTH -> viewModel.syncHealth()
                        else -> Unit
                    }
                },
            )
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
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Открыть настройки батареи · выбрать «Без ограничений»") }
                    MaintenanceSection(
                        expanded = maintenanceExpanded,
                        onExpandedChange = { maintenanceExpanded = it },
                        description = "Ручное управление доступом и восстановление виртуального устройства",
                    ) {
                        OutlinedButton(
                            onClick = openHealthConnectSettings,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = health.available,
                        ) { Text("Управлять доступом в Health Connect") }
                        if (health.binding != null) {
                            OutlinedButton(
                                onClick = { confirmRecreate = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !health.syncing,
                            ) { Text("Пересоздать устройство и очистить старые поля") }
                        }
                        TextButton(
                            onClick = { confirmRevoke = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = health.available,
                        ) {
                            Text(
                                "Отключить синхронизацию и отозвать весь доступ",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
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
                    viewModel.recreateHealthDevice(selectedMetrics)
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
    onRequestWatchdog: () -> Unit,
    expandedByDefault: Boolean = false,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(expandedByDefault) { mutableStateOf(expandedByDefault) }
    var roomMenu by remember { mutableStateOf(false) }
    var confirmRecreate by remember { mutableStateOf(false) }
    var maintenanceExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedRoomId by remember(phone.binding?.roomId, ui.catalog.rooms) {
        mutableStateOf(phone.binding?.roomId ?: ui.catalog.rooms.firstOrNull()?.id.orEmpty())
    }
    var selectedSensors by remember(phone.selectedSensors) { mutableStateOf(phone.selectedSensors) }
    val selectedRoom = ui.catalog.rooms.firstOrNull { it.id == selectedRoomId }
    val selectionMatchesDevice = phone.binding == null ||
        phone.binding.targets.map { it.key }.toSet() == selectedSensors.map(PhoneSensor::name).toSet()
    val guidance = phoneGuidance(phone, selectionMatchesDevice)

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
            NextActionCard(
                guidance = guidance,
                actionEnabled = !phone.syncing && when (guidance.action) {
                    GuidanceAction.CREATE_PHONE_DEVICE -> selectedRoomId.isNotBlank()
                    else -> true
                },
                onAction = { action ->
                    when (action) {
                        GuidanceAction.CREATE_PHONE_DEVICE -> viewModel.createPhoneDevice(selectedRoomId)
                        GuidanceAction.RECREATE_PHONE_DEVICE -> confirmRecreate = true
                        GuidanceAction.ENABLE_PHONE_BACKGROUND -> viewModel.setPhoneEnabled(true)
                        GuidanceAction.REQUEST_PHONE_LIVE_MODE -> onRequestLiveMode()
                        GuidanceAction.REQUEST_PHONE_WATCHDOG -> onRequestWatchdog()
                        GuidanceAction.OPEN_BATTERY_SETTINGS -> {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                        GuidanceAction.SYNC_PHONE -> viewModel.syncPhone()
                        else -> Unit
                    }
                },
            )

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Предупреждать о застывшей синхронизации")
                            Text(
                                "Локальная проверка примерно каждые 15 минут; предупреждение после 45 минут без успеха",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = phone.syncSettings.watchdogEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) onRequestWatchdog()
                                else viewModel.setPhoneWatchdogEnabled(false)
                            },
                            enabled = !phone.syncing,
                        )
                    }
                    Text(
                        "Это локальная страховка без внешнего сервера. Android может отложить проверку; после force-stop в настройках или полной заморозки приложения прошивкой уведомления не будет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Текущий набор показателей не требует геолокации, доступа к звонкам, Bluetooth или файлам. Такие датчики будут добавляться отдельными выключенными группами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReliabilityRow(
                        title = "Уведомления",
                        ready = phone.notificationPermissionGranted,
                        readyText = "разрешены",
                        missingText = "нужны для постоянного режима и watchdog",
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
                    if (phone.binding != null) {
                        MaintenanceSection(
                            expanded = maintenanceExpanded,
                            onExpandedChange = { maintenanceExpanded = it },
                            description = "Восстановление виртуального устройства после изменений или ручного удаления",
                        ) {
                            OutlinedButton(
                                onClick = { confirmRecreate = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !phone.syncing,
                            ) { Text("Пересоздать устройство телефона") }
                        }
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
                    viewModel.recreatePhoneDevice(selectedSensors)
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
private fun ConnectionCard(
    ui: MainUiState,
    busy: Boolean,
    viewModel: MainViewModel,
    expandedByDefault: Boolean = false,
) {
    var expanded by rememberSaveable(expandedByDefault) { mutableStateOf(expandedByDefault) }
    var mode by remember(ui.config.mode) { mutableStateOf(ui.config.mode) }
    var localUrl by remember(ui.config.localUrl) { mutableStateOf(ui.config.localUrl) }
    var cloudUrl by remember(ui.config.cloudUrl) { mutableStateOf(ui.config.cloudUrl) }
    var serial by remember(ui.config.serial) { mutableStateOf(ui.config.serial) }
    var email by remember(ui.config.email) { mutableStateOf(ui.config.email) }
    var localPassword by rememberSaveable { mutableStateOf("") }
    var cloudPassword by rememberSaveable { mutableStateOf("") }
    var showLocalPassword by rememberSaveable { mutableStateOf(false) }
    var showCloudPassword by rememberSaveable { mutableStateOf(false) }
    val connectionFormChanged = mode != ui.config.mode ||
        localUrl != ui.config.localUrl ||
        cloudUrl != ui.config.cloudUrl ||
        serial != ui.config.serial ||
        email != ui.config.email ||
        localPassword.isNotEmpty() ||
        cloudPassword.isNotEmpty()
    val guidance = connectionGuidance(ui, connectionFormChanged)

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
            NextActionCard(
                guidance = guidance,
                actionEnabled = !busy,
                darkSurface = true,
                onAction = { action ->
                    when (action) {
                        GuidanceAction.SAVE_AND_TEST_CONNECTION -> {
                            viewModel.saveAndTestSettings(
                                mode,
                                localUrl,
                                cloudUrl,
                                serial,
                                email,
                                localPassword,
                                cloudPassword,
                            )
                        }
                        GuidanceAction.REFRESH_CATALOG -> viewModel.testConnection()
                        else -> Unit
                    }
                },
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelSummaryCard(
    items: List<PanelItem>,
    controls: List<SprutControl>,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val cards = remember(controls) { buildServiceControlCards(controls) }
    val cardsById = remember(cards) { cards.associateBy(ServiceControlCard::id) }
    val controlsById = remember(controls) { controls.associateBy(SprutControl::id) }
    fun resolveCard(item: PanelItem): ServiceControlCard? = cardsById[item.controlId]
        ?: controlsById[item.controlId]?.let { oldControl ->
            cards.firstOrNull { card -> card.controls.any { it.id == oldControl.id } }
        }
    val hasEmbeddedPanel = remember { DevicePanelSupport.hasEmbeddedPanel(context) }
    val hasSystemControls = remember { DevicePanelSupport.hasSystemControls(context) }
    var expanded by rememberSaveable { mutableStateOf(items.size <= 4) }
    var confirmClear by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.DashboardCustomize, null, Modifier.padding(9.dp), tint = SprutGreen)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Панель устройств", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            hasEmbeddedPanel -> "Раскрытая Device Controls с нашим glassmorphism-интерфейсом"
                            hasSystemControls -> "Стандартная Device Controls этой версии Android"
                            else -> "Прошивка не сообщает о поддержке Device Controls"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${items.size}/48", style = MaterialTheme.typography.labelLarge)
                if (items.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                }
            }

            Text(
                if (hasEmbeddedPanel) {
                    "Android 14+ сможет встроить наш экран. Одна карточка здесь соответствует одному сервису SprutHub, а его показатели собираются внутри."
                } else {
                    "Точный вид и место панели зависят от производителя. Предпросмотр работает даже если оболочка не показывает панель в шторке."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { context.startActivity(Intent(context, SprutPanelPreviewActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Visibility, null)
                Spacer(Modifier.size(8.dp))
                Text("Открыть предпросмотр")
            }

            if (items.isEmpty()) {
                Text(
                    "Пока пусто. Нажмите «В панель устройств» у нужных сервисов ниже.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            AnimatedVisibility(expanded && items.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.forEachIndexed { index, item ->
                        val card = resolveCard(item)
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    card?.title ?: "Недоступное устройство",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        card?.serviceName?.takeIf(String::isNotBlank) ?: card?.serviceType,
                                        card?.room,
                                        if (item.size == PanelItemSize.LARGE) "широкая" else "компактная",
                                    ).filter(String::isNotBlank).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.movePanelItem(item.controlId, -1) },
                                enabled = index > 0,
                            ) { Icon(Icons.Rounded.ArrowUpward, "Выше") }
                            IconButton(
                                onClick = { viewModel.movePanelItem(item.controlId, 1) },
                                enabled = index < items.lastIndex,
                            ) { Icon(Icons.Rounded.ArrowDownward, "Ниже") }
                        }
                        if (card != null && card.availableAttributes().isNotEmpty()) {
                            val available = card.availableAttributes()
                            val availableIds = available.mapTo(mutableSetOf(), SprutControl::id)
                            val selectedIds = (
                                item.attributeControlIds
                                    ?: card.defaultAttributes().map(SprutControl::id)
                                ).filter(availableIds::contains)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Показатели · до 2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.attributeControlIds != null) {
                                    TextButton(
                                        onClick = { viewModel.setPanelItemAttributes(card.id, null) },
                                    ) { Text("Авто") }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                available.forEach { attribute ->
                                    val selected = attribute.id in selectedIds
                                    FilterChip(
                                        selected = selected,
                                        enabled = selected || selectedIds.size < 2,
                                        onClick = {
                                            val next = if (selected) {
                                                selectedIds - attribute.id
                                            } else {
                                                selectedIds + attribute.id
                                            }
                                            viewModel.setPanelItemAttributes(card.id, next)
                                        },
                                        label = { Text(card.attributeLabel(attribute)) },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    viewModel.setPanelItemSize(
                                        item.controlId,
                                        if (item.size == PanelItemSize.LARGE) {
                                            PanelItemSize.COMPACT
                                        } else {
                                            PanelItemSize.LARGE
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (item.size == PanelItemSize.LARGE) "Сделать компактной" else "Сделать широкой")
                            }
                            TextButton(
                                onClick = { viewModel.removePanelItem(item.controlId) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Убрать") }
                        }
                    }
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить панель устройств")
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить панель устройств?") },
            text = { Text("Устройства SprutHub и отдельные плитки шторки не затрагиваются.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearPanelItems()
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
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
    var confirmClearAll by remember { mutableStateOf(false) }
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
                            TextButton(onClick = { viewModel.clearTile(assignment.slot) }) { Text("Удалить") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { confirmClearAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Удалить все плитки") }
            }
            Text(
                "Удаление снимает назначение и отключает системный слот, поэтому он исчезает и из списка выбора Android.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Удалить все плитки?") },
            text = {
                Text("Все плитки SprutHub Helper будут убраны из шторки и из списка доступных плиток Android. Устройства SprutHub не затрагиваются.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    viewModel.clearAllTiles()
                }) { Text("Удалить все") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AccessoryCard(
    group: AccessoryControlGroup,
    assignments: List<TileAssignment>,
    panelItems: List<PanelItem>,
    viewModel: MainViewModel,
    iconRevision: Int,
    onPickIcon: (String) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(group.controls.first().icon(), null, Modifier.padding(10.dp), tint = SprutGreen)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            group.room,
                            if (group.serviceCards.size > 1) {
                                "${group.serviceCards.size} сервиса"
                            } else {
                                group.serviceCards.single().headlineValue()
                            },
                        ).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            group.serviceCards.forEachIndexed { index, card ->
                val control = card.primaryControl
                if (index > 0 || group.serviceCards.size > 1) HorizontalDivider()
                ControlActions(
                    card = card,
                    control = control,
                    serviceLabel = group.serviceLabel(card),
                    showServiceLabel = group.serviceCards.size > 1 || card.serviceName.isNotBlank(),
                    assignments = assignments,
                    panelItems = panelItems,
                    viewModel = viewModel,
                    iconRevision = iconRevision,
                    onPickIcon = { onPickIcon(control.id) },
                )
            }
        }
    }
}

@Composable
private fun ControlActions(
    card: ServiceControlCard,
    control: SprutControl,
    serviceLabel: String,
    showServiceLabel: Boolean,
    assignments: List<TileAssignment>,
    panelItems: List<PanelItem>,
    viewModel: MainViewModel,
    iconRevision: Int,
    onPickIcon: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember(control.id) { mutableStateOf(false) }
    val iconManager = remember { CustomIconManager(context) }
    var hasCustomIcon by remember(control.id, iconRevision) {
        mutableStateOf(iconManager.hasIcon(control.id))
    }
    val assignedSlot = assignments.firstOrNull { it.controlId == control.id }?.slot
    val selectedPanelItem = panelItems.firstOrNull { item ->
        item.controlId == card.id || card.controls.any { it.id == item.controlId }
    }
    val inPanel = selectedPanelItem != null
    val firstFreeSlot = (1..12).firstOrNull { slot -> assignments.none { it.slot == slot } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showServiceLabel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(serviceLabel, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    card.headlineValue(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (selectedPanelItem != null) viewModel.removePanelItem(selectedPanelItem.controlId)
                        else viewModel.addPanelItem(card.id)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (inPanel) "В панели устройств" else "В панель устройств") }
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
                                SprutAppWidgetProvider.updateAll(context)
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
private fun DiagnosticsCard(ui: MainUiState, expandedByDefault: Boolean = false) {
    val context = LocalContext.current
    val events by AppGraph.diagnostics.events.collectAsState()
    var expanded by rememberSaveable(expandedByDefault) { mutableStateOf(expandedByDefault) }
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
                    Text(
                        "Структурированный ограниченный журнал без raw logcat. Секреты и персональные значения скрываются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (events.isEmpty()) Text("Событий пока нет", style = MaterialTheme.typography.bodySmall)
                    events.take(3).forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                        Text(
                            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.epochMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${event.event}: ${event.outcome.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (event.outcome == io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть диагностику")
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
