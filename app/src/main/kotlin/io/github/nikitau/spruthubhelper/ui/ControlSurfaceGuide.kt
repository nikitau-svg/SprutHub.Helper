package io.github.nikitau.spruthubhelper.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitau.spruthubhelper.controls.DevicePanelSupport
import java.util.Locale
import kotlinx.coroutines.delay

internal enum class ControlSurfaceKind {
    HOME_WIDGET,
    QUICK_TILE,
    DEVICE_PANEL,
}

internal enum class SurfaceCompatibilityTone {
    VERIFIED,
    SHELL_DEPENDENT,
    LIMITED,
    UNAVAILABLE,
}

internal data class ControlSurfaceCompatibility(
    val kind: ControlSurfaceKind,
    val tone: SurfaceCompatibilityTone,
    val status: String,
    val detail: String,
)

internal data class ControlSurfaceCopy(
    val kind: ControlSurfaceKind,
    val shortTitle: String,
    val title: String,
    val location: String,
    val description: String,
    val steps: List<String>,
    val limitation: String,
)

private const val VERIFIED_FOLD_MODEL = "SM-F971B"

/** Pure compatibility policy shared by the UI and unit tests. */
internal fun buildControlSurfaceCompatibility(
    apiLevel: Int,
    manufacturer: String,
    model: String,
    hasSystemControls: Boolean,
    hasEmbeddedPanel: Boolean,
): List<ControlSurfaceCompatibility> {
    val testedFold = manufacturer.trim().equals("samsung", ignoreCase = true) &&
        model.trim().uppercase(Locale.ROOT) == VERIFIED_FOLD_MODEL

    val widget = when {
        apiLevel < Build.VERSION_CODES.R -> ControlSurfaceCompatibility(
            ControlSurfaceKind.HOME_WIDGET,
            SurfaceCompatibilityTone.UNAVAILABLE,
            "Недоступно",
            "Для Helper требуется Android 11 или новее.",
        )
        testedFold -> ControlSurfaceCompatibility(
            ControlSurfaceKind.HOME_WIDGET,
            SurfaceCompatibilityTone.VERIFIED,
            "Проверено",
            "Проверены добавление, растягивание, несколько устройств и восстановление на Samsung Fold с One UI.",
        )
        apiLevel == Build.VERSION_CODES.R -> ControlSurfaceCompatibility(
            ControlSurfaceKind.HOME_WIDGET,
            SurfaceCompatibilityTone.LIMITED,
            "Ограничено",
            "Виджет работает, но Android 11 не поддерживает точные responsive-размеры Android 12+. Итоговую сетку выбирает launcher.",
        )
        else -> ControlSurfaceCompatibility(
            ControlSurfaceKind.HOME_WIDGET,
            SurfaceCompatibilityTone.SHELL_DEPENDENT,
            "Зависит от launcher",
            "Android поддерживает изменение размера, но число ячеек, поля и доступные размеры задаёт домашний экран телефона.",
        )
    }

    val tile = when {
        apiLevel < Build.VERSION_CODES.R -> ControlSurfaceCompatibility(
            ControlSurfaceKind.QUICK_TILE,
            SurfaceCompatibilityTone.UNAVAILABLE,
            "Недоступно",
            "Для Helper требуется Android 11 или новее.",
        )
        testedFold -> ControlSurfaceCompatibility(
            ControlSurfaceKind.QUICK_TILE,
            SurfaceCompatibilityTone.VERIFIED,
            "Проверено",
            "Проверены добавление, удаление, read-only значения и восстановление связи в шторке One UI.",
        )
        apiLevel < Build.VERSION_CODES.TIRAMISU -> ControlSurfaceCompatibility(
            ControlSurfaceKind.QUICK_TILE,
            SurfaceCompatibilityTone.LIMITED,
            "Ограничено",
            "На Android 11–12 назначение работает, но плитку нужно вручную перетащить через редактирование шторки.",
        )
        else -> ControlSurfaceCompatibility(
            ControlSurfaceKind.QUICK_TILE,
            SurfaceCompatibilityTone.SHELL_DEPENDENT,
            "Зависит от оболочки",
            "Helper может открыть системный запрос добавления. Форму, подписи и место плитки окончательно выбирает оболочка Android.",
        )
    }

    val panel = when {
        apiLevel < Build.VERSION_CODES.R || !hasSystemControls -> ControlSurfaceCompatibility(
            ControlSurfaceKind.DEVICE_PANEL,
            SurfaceCompatibilityTone.UNAVAILABLE,
            "Недоступно",
            "Эта прошивка не объявляет системную функцию Android Device Controls. Внутренний предпросмотр Helper остаётся доступен.",
        )
        testedFold -> ControlSurfaceCompatibility(
            ControlSurfaceKind.DEVICE_PANEL,
            SurfaceCompatibilityTone.VERIFIED,
            "Проверено",
            "Проверены стандартные карточки и раскрытая панель из «Управления устройствами» на Samsung Fold с One UI.",
        )
        apiLevel < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !hasEmbeddedPanel ->
            ControlSurfaceCompatibility(
                ControlSurfaceKind.DEVICE_PANEL,
                SurfaceCompatibilityTone.LIMITED,
                "Ограничено",
                "Доступны стандартные карточки Android Device Controls. Собственная крупная панель внутри шторки требует Android 14+ и поддержки оболочки.",
            )
        else -> ControlSurfaceCompatibility(
            ControlSurfaceKind.DEVICE_PANEL,
            SurfaceCompatibilityTone.SHELL_DEPENDENT,
            "Зависит от оболочки",
            "Android 14+ позволяет встроить крупную панель Helper, но производитель может показать только стандартные карточки или спрятать вход в другое место.",
        )
    }

    return listOf(widget, tile, panel)
}

internal fun controlSurfaceCopy(
    kind: ControlSurfaceKind,
    apiLevel: Int,
): ControlSurfaceCopy = when (kind) {
    ControlSurfaceKind.HOME_WIDGET -> ControlSurfaceCopy(
        kind = kind,
        shortTitle = "Рабочий стол",
        title = "Виджет на рабочем столе",
        location = "Рядом с иконками приложений",
        description = "Один виджет показывает до восьми устройств, сценариев или датчиков и перестраивается при изменении размера.",
        steps = listOf(
            "Удерживайте свободное место рабочего стола.",
            "Откройте «Виджеты» и найдите SprutHub Helper.",
            "Выберите состав, шаблон и растяните виджет рамкой launcher.",
        ),
        limitation = if (apiLevel == Build.VERSION_CODES.R) {
            "На Android 11 размер и компоновка сильнее зависят от launcher."
        } else {
            "Сетка и минимальный размер отличаются у Samsung, Pixel, Xiaomi и других launcher."
        },
    )
    ControlSurfaceKind.QUICK_TILE -> ControlSurfaceCopy(
        kind = kind,
        shortTitle = "Шторка",
        title = "Быстрая кнопка в шторке",
        location = "Среди Wi‑Fi, Bluetooth и фонарика",
        description = "Одна кнопка управляет одним сервисом или показывает один выбранный показатель без открытия приложения.",
        steps = listOf(
            "У нужного сервиса нажмите «В шторку».",
            if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                "Подтвердите системное окно добавления Android."
            } else {
                "Откройте редактирование шторки и перетащите плитку SprutHub вручную."
            },
            "Проверяйте отметку «добавлена в шторку», а не только назначение слота.",
        ),
        limitation = "Android сам задаёт форму плитки и может скрывать вторую строку. Helper ставит главное состояние в первую строку.",
    )
    ControlSurfaceKind.DEVICE_PANEL -> ControlSurfaceCopy(
        kind = kind,
        shortTitle = "Панель",
        title = "Панель устройств из шторки",
        location = "Кнопка «Управление устройствами» или аналог",
        description = "Несколько карточек дома открываются на одном экране. Это отдельный системный раздел, а не ещё одна Quick Settings-плитка.",
        steps = listOf(
            "У нужных сервисов нажмите «В панель».",
            "Откройте системное «Управление устройствами» и выберите SprutHub Helper.",
            "Порядок, размер и показатели настраиваются в сводке назначений Helper.",
        ),
        limitation = "Android 11–13 обычно показывает стандартные карточки. Раскрытая панель Helper требует Android 14+ и согласия оболочки.",
    )
}

@Composable
internal fun ControlSurfaceOverviewCard(
    panelCount: Int,
    tileAssignmentCount: Int,
    installedTileCount: Int,
    compatibility: List<ControlSurfaceCompatibility>,
    managementExpanded: Boolean,
    onToggleManagement: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SprutAccent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, SprutAccent.copy(alpha = 0.18f)),
                ) {
                    Icon(
                        Icons.Rounded.DevicesOther,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                        tint = SprutAccent,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Куда добавить управление", fontWeight = FontWeight.Bold)
                    Text(
                        "Три разных места Android — один каталог SprutHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SurfaceSummaryRow(
                icon = Icons.Rounded.Widgets,
                title = "На рабочий стол",
                detail = "Виджет · до 8 объектов",
                trailing = compatibility.statusFor(ControlSurfaceKind.HOME_WIDGET),
            )
            SurfaceSummaryRow(
                icon = Icons.Rounded.Tune,
                title = "В верхнюю шторку",
                detail = "Отдельная быстрая кнопка",
                trailing = when {
                    installedTileCount > 0 -> "$installedTileCount в шторке"
                    tileAssignmentCount > 0 -> "$tileAssignmentCount назначено"
                    else -> "Не добавлено"
                },
            )
            SurfaceSummaryRow(
                icon = Icons.Rounded.DashboardCustomize,
                title = "В панель из шторки",
                detail = "Несколько устройств на экране",
                trailing = if (panelCount > 0) "$panelCount выбрано"
                else compatibility.statusFor(ControlSurfaceKind.DEVICE_PANEL),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenGuide) { Text("Показать, где это") }
                if (panelCount > 0 || tileAssignmentCount > 0) {
                    TextButton(onClick = onToggleManagement) {
                        Text(if (managementExpanded) "Скрыть назначения" else "Назначения")
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            if (managementExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceSummaryRow(
    icon: ImageVector,
    title: String,
    detail: String,
    trailing: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(11.dp),
            color = Color.White.copy(alpha = 0.055f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = SprutText)
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
        }
        Text(
            trailing,
            style = MaterialTheme.typography.labelMedium,
            color = SprutTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OnboardingSurfaceGuideCard(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Выберите удобное место", color = SprutAccent, fontWeight = FontWeight.SemiBold)
                Text(
                    "Это три разных интерфейса Android",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Они используют один каталог и общие актуальные состояния, но добавляются по-разному.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SprutTextMuted,
                )
            }
            ControlSurfacePager(autoAdvance = true)
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Понятно, подключить SprutHub")
            }
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Пропустить обзор")
            }
        }
    }
}

@Composable
internal fun ControlSurfaceFaq(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val compatibility = remember {
        buildControlSurfaceCompatibility(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            hasSystemControls = DevicePanelSupport.hasSystemControls(context),
            hasEmbeddedPanel = DevicePanelSupport.hasEmbeddedPanel(context),
        )
    }
    var expandedQuestion by rememberSaveable { mutableStateOf<String?>(null) }
    val questions = remember {
        listOf(
            "Почему назначение есть, а кнопки в шторке нет?" to
                "Назначение выбирает устройство для слота Helper. Фактическое добавление подтверждает Android. На Android 13+ примите системное окно; на Android 11–12 откройте редактирование шторки и перетащите плитку вручную.",
            "Панель и плитка — это одно и то же?" to
                "Нет. Плитка — одна маленькая кнопка рядом с Wi‑Fi. Панель — отдельный экран сразу с несколькими устройствами, который открывается через системное «Управление устройствами» или аналогичный пункт оболочки.",
            "Откуда берётся актуальное состояние?" to
                "Все поверхности читают общий подтверждённый каталог и WebSocket-поток. При открытии плитки или панели Helper запрашивает свежесть и переподключается. При потере связи старое значение помечается как устаревшее или «Нет связи», а не выдаётся за живое.",
            "Что будет после принудительной остановки?" to
                "Android запрещает приложению самостоятельно запуститься после Force stop. Виджет или открытие системной поверхности может вернуть процесс на некоторых оболочках, но гарантировать это нельзя — откройте Helper вручную.",
            "Почему Android 11 ограничен?" to
                "Основные интерфейсы доступны, но точные responsive-размеры виджетов появились в Android 12, системный запрос добавления плитки — в Android 13, а встраиваемая крупная панель — в Android 14. На Android 11 используются совместимые системные варианты.",
            "Можно ли удалить пробные назначения?" to
                "Да. Раскройте «Назначения» на главном экране. Удаление плитки снимает назначение и отключает её системный слот; очистка панели не удаляет устройства из SprutHub.",
        )
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            shape = SprutTileShape,
            colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
            border = BorderStroke(1.dp, SprutGlassBorder),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Где живёт управление", fontWeight = FontWeight.Bold)
                Text(
                    "Выберите вкладку и посмотрите путь. Внешний вид — демонстрация: окончательную форму системных элементов задаёт телефон.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SprutTextMuted,
                )
                Spacer(Modifier.height(4.dp))
                ControlSurfacePager(autoAdvance = false)
            }
        }

        Text("Совместимость этого телефона", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        compatibility.forEach { item -> CompatibilityCard(item) }

        Card(
            shape = SprutTileShape,
            colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
            border = BorderStroke(1.dp, SprutGlassBorder),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Разные оболочки", fontWeight = FontWeight.Bold)
                Text(
                    "Samsung One UI, Pixel Launcher и Xiaomi/HyperOS могут по-разному называть разделы, задавать сетку виджетов и показывать Device Controls. Если крупной панели нет, используйте стандартные карточки Android или внутренний предпросмотр Helper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SprutTextMuted,
                )
            }
        }

        Text("Частые вопросы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        questions.forEach { (question, answer) ->
            val expanded = expandedQuestion == question
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    expandedQuestion = if (expanded) null else question
                },
                shape = SprutTileShape,
                color = SprutSurfaceLow,
                border = BorderStroke(1.dp, SprutGlassBorder),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(question, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = SprutTextMuted,
                        )
                    }
                    AnimatedVisibility(expanded) {
                        Text(answer, style = MaterialTheme.typography.bodySmall, color = SprutTextMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CompatibilityCard(item: ControlSurfaceCompatibility) {
    val color = item.tone.color()
    val copy = controlSurfaceCopy(item.kind, Build.VERSION.SDK_INT)
    Card(
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.kind.icon(), null, Modifier.size(22.dp), tint = color)
                }
            }
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.shortTitle, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    CompatibilityBadge(item.status, color)
                }
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = SprutTextMuted)
            }
        }
    }
}

@Composable
private fun ControlSurfacePager(autoAdvance: Boolean) {
    val context = LocalContext.current
    val motionEnabled = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
    var selectedName by rememberSaveable { mutableStateOf(ControlSurfaceKind.HOME_WIDGET.name) }
    val selected = ControlSurfaceKind.entries.firstOrNull { it.name == selectedName }
        ?: ControlSurfaceKind.HOME_WIDGET

    LaunchedEffect(autoAdvance, motionEnabled, selected) {
        if (!autoAdvance || !motionEnabled) return@LaunchedEffect
        delay(3_200)
        val next = (ControlSurfaceKind.entries.indexOf(selected) + 1) % ControlSurfaceKind.entries.size
        selectedName = ControlSurfaceKind.entries[next].name
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ControlSurfaceKind.entries.forEach { kind ->
                val active = kind == selected
                Surface(
                    modifier = Modifier.weight(1f).clickable { selectedName = kind.name },
                    shape = RoundedCornerShape(13.dp),
                    color = if (active) SprutAccent.copy(alpha = 0.14f)
                    else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(
                        1.dp,
                        if (active) SprutAccent.copy(alpha = 0.52f) else SprutGlassBorder,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            kind.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (active) SprutAccent else SprutTextMuted,
                        )
                        Text(
                            controlSurfaceCopy(kind, Build.VERSION.SDK_INT).shortTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) SprutText else SprutTextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (motionEnabled) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "surface-guide",
            ) { kind ->
                SurfaceGuideBody(kind)
            }
        } else {
            SurfaceGuideBody(selected)
        }
    }
}

@Composable
private fun SurfaceGuideBody(kind: ControlSurfaceKind) {
    val copy = controlSurfaceCopy(kind, Build.VERSION.SDK_INT)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SurfacePreview(kind)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(copy.title, fontWeight = FontWeight.Bold)
            Text(copy.location, style = MaterialTheme.typography.labelMedium, color = SprutAccent)
            Text(copy.description, style = MaterialTheme.typography.bodySmall, color = SprutTextMuted)
        }
        copy.steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Surface(modifier = Modifier.size(22.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.07f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = SprutText)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(step, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(copy.limitation, style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
    }
}

@Composable
private fun SurfacePreview(kind: ControlSurfaceKind) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(158.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xB8121519),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) {
                    Surface(Modifier.size(4.dp), shape = CircleShape, color = SprutTextFaint) {}
                    Spacer(Modifier.size(3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("SprutHub", style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
            }
            when (kind) {
                ControlSurfaceKind.HOME_WIDGET -> WidgetPreview()
                ControlSurfaceKind.QUICK_TILE -> QuickTilePreview()
                ControlSurfaceKind.DEVICE_PANEL -> DevicePanelPreview()
            }
        }
    }
}

@Composable
private fun WidgetPreview() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(
            modifier = Modifier.weight(1f).height(103.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.075f),
            border = BorderStroke(1.dp, SprutAccent.copy(alpha = 0.32f)),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Thermostat, null, Modifier.size(25.dp), tint = SprutAccent)
                    Spacer(Modifier.size(8.dp))
                    Text("Климат", fontWeight = FontWeight.SemiBold)
                }
                Text("22 °C", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Сейчас 23,4 °C · 46 %", style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
            }
        }
        Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniDeviceTile(
                Icons.Rounded.Lightbulb,
                "Свет",
                active = true,
                modifier = Modifier.weight(1f),
            )
            MiniDeviceTile(
                Icons.Rounded.PlayArrow,
                "Вечер",
                active = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickTilePreview() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Icons.Rounded.Home, Icons.Rounded.Tune, Icons.Rounded.Settings, Icons.Rounded.DevicesOther)
                .forEachIndexed { index, icon ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(50.dp),
                            shape = CircleShape,
                            color = if (index == 1) SprutAccent.copy(alpha = 0.92f)
                            else Color.White.copy(alpha = 0.075f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    icon,
                                    null,
                                    Modifier.size(24.dp),
                                    tint = if (index == 1) Color(0xFF211500) else SprutText,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (index == 1) "Свет · Вкл" else listOf("Дом", "Настр.", "Панель")[
                                if (index == 0) 0 else index - 1
                            ],
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == 1) SprutText else SprutTextMuted,
                            maxLines = 1,
                        )
                    }
                }
        }
        Text("Одна плитка — одно быстрое действие или значение", style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
    }
}

@Composable
private fun DevicePanelPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
            color = Color.White.copy(alpha = 0.07f),
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DashboardCustomize, null, Modifier.size(20.dp), tint = SprutAccent)
                Spacer(Modifier.size(8.dp))
                Text("Управление устройствами", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = SprutSuccess)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniPanelCard(Icons.Rounded.Thermostat, "Климат", "22 °C", true, Modifier.weight(1f))
            MiniPanelCard(Icons.Rounded.Lightbulb, "Торшер", "Включён", true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniDeviceTile(
    icon: ImageVector,
    title: String,
    active: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = if (active) SprutAccent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, if (active) SprutAccent.copy(alpha = 0.30f) else SprutGlassBorder),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (active) SprutAccent else SprutText)
            Spacer(Modifier.size(6.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun MiniPanelCard(
    icon: ImageVector,
    title: String,
    value: String,
    active: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.height(66.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, if (active) SprutAccent.copy(alpha = 0.34f) else SprutGlassBorder),
    ) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (active) SprutAccent else SprutText)
            Spacer(Modifier.size(7.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.labelSmall, color = SprutTextMuted)
            }
        }
    }
}

@Composable
private fun CompatibilityBadge(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

private fun List<ControlSurfaceCompatibility>.statusFor(kind: ControlSurfaceKind): String =
    firstOrNull { it.kind == kind }?.status ?: "Зависит от телефона"

private fun ControlSurfaceKind.icon(): ImageVector = when (this) {
    ControlSurfaceKind.HOME_WIDGET -> Icons.Rounded.Widgets
    ControlSurfaceKind.QUICK_TILE -> Icons.Rounded.Tune
    ControlSurfaceKind.DEVICE_PANEL -> Icons.Rounded.DashboardCustomize
}

@Composable
private fun SurfaceCompatibilityTone.color(): Color = when (this) {
    SurfaceCompatibilityTone.VERIFIED -> SprutSuccess
    SurfaceCompatibilityTone.SHELL_DEPENDENT -> SprutInfo
    SurfaceCompatibilityTone.LIMITED -> SprutWarning
    SurfaceCompatibilityTone.UNAVAILABLE -> SprutError
}
