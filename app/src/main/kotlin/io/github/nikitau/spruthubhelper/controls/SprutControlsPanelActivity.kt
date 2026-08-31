package io.github.nikitau.spruthubhelper.controls

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.controls.ControlsProviderService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.PanelItem
import io.github.nikitau.spruthubhelper.data.PanelItemSize
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.ui.MainActivity
import io.github.nikitau.spruthubhelper.ui.SprutHelperTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Custom Android 14+ activity embedded by SystemUI in the Device Controls surface. */
class SprutControlsPanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val allowTrivialOnLockScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            intent.getBooleanExtra(ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS, false)
        setContent {
            SprutHelperTheme {
                SprutDevicePanel(
                    allowTrivialOnLockScreen = allowTrivialOnLockScreen,
                    showBack = false,
                    onBack = {},
                    onOpenApp = ::openMainApp,
                )
            }
        }
    }

    private fun openMainApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}

/** In-app preview for devices whose SystemUI does not expose embedded panels. */
class SprutPanelPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SprutHelperTheme {
                SprutDevicePanel(
                    allowTrivialOnLockScreen = false,
                    showBack = true,
                    onBack = ::finish,
                    onOpenApp = ::finish,
                )
            }
        }
    }
}

internal object DevicePanelSupport {
    private const val FEATURE_CONTROLS = "android.software.controls"

    fun hasSystemControls(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FEATURE_CONTROLS)

    fun hasEmbeddedPanel(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasSystemControls(context)
}

@Composable
private fun SprutDevicePanel(
    allowTrivialOnLockScreen: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val repository = AppGraph.repository
    val catalog by repository.catalog.collectAsState()
    val panelItems by repository.panelItems.collectAsState()
    val connection by repository.connectionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busyCardId by remember { mutableStateOf<String?>(null) }
    var sentCardId by remember { mutableStateOf<String?>(null) }
    var rangeCard by remember { mutableStateOf<ServiceControlCard?>(null) }

    val cards = remember(catalog.controls) { buildServiceControlCards(catalog.controls) }
    val cardsById = remember(cards) { cards.associateBy(ServiceControlCard::id) }
    val controlsById = remember(catalog.controls) { catalog.controls.associateBy(SprutControl::id) }
    val resolvedItems = remember(panelItems, cardsById, controlsById) {
        panelItems.mapNotNull { item ->
            val card = cardsById[item.controlId]
                ?: controlsById[item.controlId]?.let { oldControl ->
                    cards.firstOrNull { candidate -> candidate.controls.any { it.id == oldControl.id } }
                }
            card?.let { item to it }
        }.distinctBy { (_, card) -> card.id }
    }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun authorize(control: SprutControl, action: () -> Unit) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        val canRunLocked = allowTrivialOnLockScreen && !control.requiresAuthentication()
        if (!keyguard.isKeyguardLocked || canRunLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
                override fun onDismissError() = showMessage("Разблокируйте телефон для управления")
                override fun onDismissCancelled() = showMessage("Действие отменено")
            },
        )
    }

    fun runCommand(card: ServiceControlCard, control: SprutControl, command: suspend () -> Result<Unit>) {
        authorize(control) {
            scope.launch {
                busyCardId = card.id
                val result = command()
                busyCardId = null
                result.onSuccess {
                    sentCardId = card.id
                    showMessage("${card.title}: команда отправлена")
                    scope.launch {
                        delay(1_600)
                        if (sentCardId == card.id) sentCardId = null
                    }
                }.onFailure { showMessage(it.message ?: "Не удалось выполнить команду") }
            }
        }
    }

    fun primaryAction(card: ServiceControlCard) {
        val control = card.primaryControl
        when (control.behavior) {
            ControlBehavior.TOGGLE,
            ControlBehavior.TOGGLE_RANGE -> runCommand(card, control) {
                repository.setBoolean(control.id, !control.value.asBoolean())
            }
            ControlBehavior.BUTTON -> runCommand(card, control) { repository.execute(control.id) }
            ControlBehavior.RANGE -> rangeCard = card
            ControlBehavior.SENSOR -> showMessage("${card.title}: ${card.headlineValue()}")
        }
    }

    LaunchedEffect(Unit) {
        repository.refreshIfStale(maxAgeMs = 10_000)
            .onFailure { snackbar.showSnackbar(it.message ?: "Не удалось обновить SprutHub") }
    }

    GlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            PanelHeader(
                showBack = showBack,
                onBack = onBack,
                connectionText = when (connection.phase) {
                    ConnectionPhase.CONNECTED_LOCAL -> "Подключено локально"
                    ConnectionPhase.CONNECTED_CLOUD -> "Подключено через облако"
                    ConnectionPhase.CONNECTING -> "Обновление…"
                    ConnectionPhase.ERROR -> connection.message
                    ConnectionPhase.IDLE -> "Ожидание подключения"
                },
                refreshing = connection.phase == ConnectionPhase.CONNECTING,
                onRefresh = {
                    scope.launch {
                        repository.refresh(forceConnection = true)
                            .onFailure { snackbar.showSnackbar(it.message ?: "Не удалось обновить SprutHub") }
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            if (resolvedItems.isEmpty()) {
                EmptyPanel(Modifier.weight(1f), onOpenApp)
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columnCount = when {
                        maxWidth >= 760.dp -> 4
                        maxWidth >= 540.dp -> 3
                        else -> 2
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = resolvedItems,
                            key = { (_, card) -> card.id },
                            span = { (item, _) ->
                                GridItemSpan(
                                    if (item.size == PanelItemSize.LARGE && maxLineSpan > 1) 2 else 1,
                                )
                            },
                        ) { (item, card) ->
                            ServiceGlassCard(
                                item = item,
                                card = card,
                                busy = busyCardId == card.id,
                                sent = sentCardId == card.id,
                                onClick = { primaryAction(card) },
                                onAdjust = { rangeCard = card },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.Launch, null)
                Spacer(Modifier.size(8.dp))
                Text(if (showBack) "Готово" else "Открыть SprutHub Helper")
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }

    rangeCard?.let { card ->
        val control = card.primaryControl
        RangeDialog(
            control = control,
            onDismiss = { rangeCard = null },
            onConfirm = { value ->
                rangeCard = null
                runCommand(card, control) { repository.setRange(control.id, value.toDouble()) }
            },
        )
    }
}

@Composable
private fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF071411), Color(0xFF10231E), Color(0xFF081714)),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x4D72DDB2), Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.18f),
                    radius = size.minDimension * 0.55f,
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.14f, size.height * 0.18f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x3D5FA889), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.72f),
                    radius = size.minDimension * 0.62f,
                ),
                radius = size.minDimension * 0.62f,
                center = Offset(size.width * 0.88f, size.height * 0.72f),
            )
        }
        content()
    }
}

@Composable
private fun PanelHeader(
    showBack: Boolean,
    onBack: () -> Unit,
    connectionText: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        }
        Column(Modifier.weight(1f)) {
            Text("Панель устройств", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                connectionText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Обновить") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServiceGlassCard(
    item: PanelItem,
    card: ServiceControlCard,
    busy: Boolean,
    sent: Boolean,
    onClick: () -> Unit,
    onAdjust: () -> Unit,
) {
    val context = LocalContext.current
    val control = card.primaryControl
    val active = card.isActive == true
    val attributes = card.selectedAttributes(item)
    val customBitmap = remember(card.id, control.id) {
        val icons = CustomIconManager(context)
        icons.loadBitmap(card.id) ?: icons.loadBitmap(control.id)
    }
    val shape = RoundedCornerShape(if (item.size == PanelItemSize.LARGE) 30.dp else 26.dp)
    val subtitle = listOf(card.serviceName, card.room)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (item.size == PanelItemSize.LARGE) 156.dp else 148.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = if (active) {
                        listOf(Color(0x73346756), Color(0x4D284B40), Color(0x383C4B46))
                    } else {
                        listOf(Color(0x24FFFFFF), Color(0x14FFFFFF), Color(0x2424332F))
                    },
                ),
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = if (active) 0.34f else 0.16f)),
                shape,
            )
            .combinedClickable(
                enabled = !busy,
                onClick = onClick,
                onLongClick = if (card.supportsRange) onAdjust else null,
            )
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (active) 0.14f else 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else if (customBitmap != null) {
                        Image(
                            bitmap = customBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            painter = painterResource(TileIconResolver.resource(control.kind)),
                            contentDescription = null,
                            modifier = Modifier.size(27.dp),
                            tint = if (active) Color(0xFF9BF2CF) else Color.White,
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (sent) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        "Команда отправлена",
                        modifier = Modifier.size(19.dp),
                        tint = Color(0xFF9BF2CF),
                    )
                }
                if (card.supportsRange) {
                    Icon(
                        Icons.Rounded.Tune,
                        "Удерживайте для регулировки",
                        modifier = Modifier.padding(start = 5.dp).size(18.dp),
                        tint = Color.White.copy(alpha = 0.68f),
                    )
                }
                if (control.requiresAuthentication()) {
                    Icon(
                        Icons.Rounded.Lock,
                        "Требуется разблокировка",
                        modifier = Modifier.padding(start = 5.dp).size(17.dp),
                        tint = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (busy) "Отправляем…" else card.headlineValue(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) Color(0xFFB8F6DC) else Color.White,
            )
            if (attributes.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attributes.forEach { attribute ->
                        AttributePill(
                            label = card.attributeLabel(attribute),
                            value = card.attributeValue(attribute),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttributePill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.065f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyPanel(modifier: Modifier, onOpenApp: () -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Панель пока пустая", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Добавьте сервисы в разделе «Панель устройств» приложения.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenApp) { Text("Настроить") }
        }
    }
}

@Composable
private fun RangeDialog(
    control: SprutControl,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val minimum = control.minimum.toFloat()
    val maximum = control.maximum.toFloat().coerceAtLeast(minimum + 0.1f)
    var value by remember(control.id) {
        mutableStateOf(control.value.asDouble().toFloat().coerceIn(minimum, maximum))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(control.title) },
        text = {
            Column {
                Text("${value.toInt()}${control.unit.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}")
                Slider(value = value, onValueChange = { value = it }, valueRange = minimum..maximum)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
