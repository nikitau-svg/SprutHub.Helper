@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.nikitau.spruthubhelper.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.widget.DEFAULT_WIDGET_BLOCKS
import io.github.nikitau.spruthubhelper.widget.MAX_WIDGET_ITEMS
import io.github.nikitau.spruthubhelper.widget.MAX_WIDGET_SECONDARY_VALUES
import io.github.nikitau.spruthubhelper.widget.SprutAppWidgetProvider
import io.github.nikitau.spruthubhelper.widget.WidgetAssignmentStore
import io.github.nikitau.spruthubhelper.widget.WidgetContentBlock
import io.github.nikitau.spruthubhelper.widget.WidgetInformationDensity
import io.github.nikitau.spruthubhelper.widget.WidgetItemConfiguration
import io.github.nikitau.spruthubhelper.widget.WidgetLayoutConfiguration
import io.github.nikitau.spruthubhelper.widget.WidgetLayoutConfigurationCodec
import io.github.nikitau.spruthubhelper.widget.WidgetQuickTemplate
import io.github.nikitau.spruthubhelper.widget.WidgetSizeClass
import io.github.nikitau.spruthubhelper.widget.applyWidgetQuickTemplate
import io.github.nikitau.spruthubhelper.widget.compactWidgetValue
import io.github.nikitau.spruthubhelper.widget.matchesQuickTemplate
import io.github.nikitau.spruthubhelper.widget.normalized
import io.github.nikitau.spruthubhelper.widget.previewHostSize
import io.github.nikitau.spruthubhelper.widget.resolveWidgetContent
import io.github.nikitau.spruthubhelper.widget.recommendedWidgetTemplateDescription
import io.github.nikitau.spruthubhelper.widget.recommendedWidgetTemplateLabel
import io.github.nikitau.spruthubhelper.widget.visibleWidgetLines
import io.github.nikitau.spruthubhelper.widget.shouldShowWidgetRefresh
import io.github.nikitau.spruthubhelper.widget.widgetGridLayout
import io.github.nikitau.spruthubhelper.widget.widgetOverflowLabel
import kotlinx.coroutines.launch

class WidgetConfigureActivity : ComponentActivity() {
    private val appWidgetId: Int by lazy {
        intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        AppGraph.initialize(applicationContext)
        setContent {
            SprutHelperTheme {
                WidgetConfigureScreen(
                    appWidgetId = appWidgetId,
                    onCancel = ::finish,
                    onConfirm = ::finishConfiguration,
                )
            }
        }
    }

    private fun finishConfiguration(configuration: WidgetLayoutConfiguration) {
        val normalized = configuration.normalized()
        val primaryControlId = normalized.items.firstOrNull()?.controlId ?: return
        WidgetAssignmentStore.save(this, appWidgetId, primaryControlId, normalized)
        SprutAppWidgetProvider.updateWidget(this, appWidgetId)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}

private enum class WidgetConfigurePage {
    PICKER,
    EDITOR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigureScreen(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onConfirm: (WidgetLayoutConfiguration) -> Unit,
) {
    val context = LocalContext.current
    val catalog by AppGraph.repository.catalog.collectAsState()
    val connection by AppGraph.repository.connectionStatus.collectAsState()
    val servicePreferences by AppGraph.repository.servicePresentations.collectAsState()
    val cards = remember(catalog.controls) { buildServiceControlCards(catalog.controls) }
    val storedAssignment = remember(appWidgetId) { WidgetAssignmentStore.assignment(context, appWidgetId) }
    val initialConfiguration = remember(appWidgetId) {
        storedAssignment?.layout ?: WidgetLayoutConfiguration(
            items = listOfNotNull(storedAssignment?.controlId?.let(::WidgetItemConfiguration)),
        )
    }
    val configurationSaver = remember {
        Saver<WidgetLayoutConfiguration, String>(
            save = { WidgetLayoutConfigurationCodec.encode(it) },
            restore = { WidgetLayoutConfigurationCodec.decode(it) },
        )
    }
    var configuration by rememberSaveable(appWidgetId, stateSaver = configurationSaver) {
        mutableStateOf(initialConfiguration)
    }
    var pageName by rememberSaveable(appWidgetId) {
        mutableStateOf(
            if (initialConfiguration.items.isEmpty()) WidgetConfigurePage.PICKER.name
            else WidgetConfigurePage.EDITOR.name,
        )
    }
    var activeControlId by rememberSaveable(appWidgetId) {
        mutableStateOf(initialConfiguration.items.firstOrNull()?.controlId.orEmpty())
    }
    val page = runCatching { WidgetConfigurePage.valueOf(pageName) }
        .getOrDefault(WidgetConfigurePage.PICKER)

    fun updateConfiguration(candidate: WidgetLayoutConfiguration) {
        configuration = candidate.normalized()
        if (activeControlId !in configuration.items.map(WidgetItemConfiguration::controlId)) {
            activeControlId = configuration.items.firstOrNull()?.controlId.orEmpty()
        }
    }

    BackHandler(enabled = page == WidgetConfigurePage.EDITOR) {
        pageName = WidgetConfigurePage.PICKER.name
    }

    LaunchedEffect(appWidgetId) {
        AppGraph.repository.refreshIfStale(maxAgeMs = 0)
    }

    SprutBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = SprutText,
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (page == WidgetConfigurePage.PICKER) "Состав виджета" else "Настройка виджета")
                    },
                    navigationIcon = {
                        SprutHeaderIconButton(
                            icon = if (page == WidgetConfigurePage.PICKER) {
                                Icons.Rounded.Close
                            } else {
                                Icons.AutoMirrored.Rounded.ArrowBack
                            },
                            contentDescription = if (page == WidgetConfigurePage.PICKER) "Отмена" else "Назад к составу",
                            onClick = {
                                if (page == WidgetConfigurePage.PICKER) onCancel()
                                else pageName = WidgetConfigurePage.PICKER.name
                            },
                        )
                    },
                    actions = {
                        SprutHeaderIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Обновить каталог",
                            onClick = { AppGraph.applicationScope.launch { AppGraph.repository.refresh(true) } },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = SprutBackground.copy(alpha = 0.92f),
                    ),
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SprutBackground.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = {
                            if (page == WidgetConfigurePage.PICKER) {
                                pageName = WidgetConfigurePage.EDITOR.name
                                activeControlId = configuration.items.firstOrNull()?.controlId.orEmpty()
                            } else {
                                onConfirm(configuration)
                            }
                        },
                        enabled = configuration.items.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (page == WidgetConfigurePage.PICKER) {
                                "Настроить · ${configuration.items.size}"
                            } else if (storedAssignment == null) {
                                "Добавить на рабочий стол"
                            } else {
                                "Сохранить виджет"
                            },
                        )
                    }
                }
            },
        ) { padding ->
            when (page) {
                WidgetConfigurePage.PICKER -> WidgetItemPicker(
                    cards = cards,
                    connectionPhase = connection.phase,
                    connectionMessage = connection.message,
                    configuration = configuration,
                    onConfigurationChange = ::updateConfiguration,
                    modifier = Modifier.padding(padding),
                )

                WidgetConfigurePage.EDITOR -> WidgetLayoutEditor(
                    cards = cards,
                    servicePreferences = servicePreferences,
                    configuration = configuration,
                    activeControlId = activeControlId,
                    onActiveControlChange = { activeControlId = it },
                    onConfigurationChange = ::updateConfiguration,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun WidgetItemPicker(
    cards: List<ServiceControlCard>,
    connectionPhase: ConnectionPhase,
    connectionMessage: String,
    configuration: WidgetLayoutConfiguration,
    onConfigurationChange: (WidgetLayoutConfiguration) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(cards, query) {
        val needle = query.trim()
        cards.filter { card ->
            needle.isBlank() || listOf(card.title, card.room, card.displayServiceName())
                .any { it.contains(needle, ignoreCase = true) } || card.characteristicValues().any {
                it.label.contains(needle, ignoreCase = true) || it.value.contains(needle, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Выберите до $MAX_WIDGET_ITEMS устройств, сценариев или датчиков. Первый объект останется главным, когда виджет уменьшен.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            label = { Text("Поиск по устройствам и комнатам") },
            singleLine = true,
            shape = SprutControlShape,
            colors = sprutTextFieldColors(),
        )
        Spacer(Modifier.height(12.dp))

        if (connectionPhase == ConnectionPhase.CONNECTING && filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (filtered.isEmpty()) {
            Text(
                if (query.isNotBlank()) "Ничего не найдено" else connectionMessage.ifBlank {
                    "Каталог пуст. Сначала проверьте подключение в приложении."
                },
                modifier = Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                filtered.groupBy { it.room.ifBlank { "Без комнаты" } }.forEach { (room, roomCards) ->
                    item(key = "room:$room") {
                        Text(
                            room,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            fontWeight = FontWeight.SemiBold,
                            color = SprutAccent,
                        )
                    }
                    items(roomCards, key = ServiceControlCard::id) { card ->
                        val selectedItem = configuration.items.firstOrNull { item ->
                            card.controls.any { it.id == item.controlId }
                        }
                        val selectedIndex = selectedItem?.let(configuration.items::indexOf)?.plus(1)
                        WidgetServiceChoice(
                            card = card,
                            selectedIndex = selectedIndex,
                            enabled = selectedItem != null || configuration.items.size < MAX_WIDGET_ITEMS,
                            onClick = {
                                val updated = if (selectedItem != null) {
                                    configuration.items.filterNot { it.controlId == selectedItem.controlId }
                                } else {
                                    configuration.items + WidgetItemConfiguration(card.primaryControl.id)
                                }
                                onConfigurationChange(configuration.copy(items = updated))
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun WidgetServiceChoice(
    card: ServiceControlCard,
    selectedIndex: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selected = selectedIndex != null
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) SprutAccent.copy(alpha = 0.11f) else SprutSurfaceLow,
            disabledContainerColor = SprutSurfaceLow.copy(alpha = 0.5f),
        ),
        border = BorderStroke(
            1.dp,
            if (selected) SprutAccent.copy(alpha = 0.66f) else SprutGlassBorder,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(TileIconResolver.resource(card)),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    tint = if (selected) SprutAccent else SprutText,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(card.displayServiceName(), card.headlineValue(), card.room)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectedIndex != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(SprutAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$selectedIndex", color = Color(0xFF211500), fontWeight = FontWeight.Bold)
                }
            } else {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить", tint = SprutTextMuted)
            }
        }
    }
}

@Composable
private fun WidgetLayoutEditor(
    cards: List<ServiceControlCard>,
    servicePreferences: List<ServicePresentationPreference>,
    configuration: WidgetLayoutConfiguration,
    activeControlId: String,
    onActiveControlChange: (String) -> Unit,
    onConfigurationChange: (WidgetLayoutConfiguration) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewSizeName by rememberSaveable { mutableStateOf(WidgetSizeClass.COMPACT.name) }
    val previewSize = runCatching { WidgetSizeClass.valueOf(previewSizeName) }
        .getOrDefault(WidgetSizeClass.COMPACT)
    val activeItem = configuration.items.firstOrNull { it.controlId == activeControlId }
        ?: configuration.items.firstOrNull()
    val activeCard = activeItem?.let { item -> cards.cardForControl(item.controlId) }
    val preferenceByCard = servicePreferences.associateBy(ServicePresentationPreference::cardId)
    val selectedCards = configuration.items.mapNotNull { item -> cards.cardForControl(item.controlId) }
    val matchedTemplate = WidgetQuickTemplate.entries.firstOrNull { template ->
        configuration.matchesQuickTemplate(template, selectedCards)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Предпросмотр",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            WidgetLivePreview(
                cards = cards,
                preferenceByCard = preferenceByCard,
                configuration = configuration,
                sizeClass = previewSize,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    WidgetSizeClass.STRIP to "Низкий",
                    WidgetSizeClass.ICON to "1×1",
                    WidgetSizeClass.COMPACT to "2×1",
                    WidgetSizeClass.WIDE to "Широкий",
                    WidgetSizeClass.TALL to "2 ряда",
                ).forEach { (sizeClass, label) ->
                    FilterChip(
                        selected = previewSize == sizeClass,
                        onClick = { previewSizeName = sizeClass.name },
                        label = { Text(label) },
                        leadingIcon = if (previewSize == sizeClass) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        colors = sprutFilterChipColors(),
                    )
                }
            }
            Text(
                "Размер меняется рамкой уже на рабочем столе. Предпросмотр показывает, как состав перестроится автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = SprutTextMuted,
            )
        }

        item {
            WidgetEditorSection(
                title = "Готовая компоновка",
                subtitle = "Начните с подходящего шаблона и при желании измените любой параметр ниже.",
            ) {
                Text(
                    "Рекомендуется: ${recommendedWidgetTemplateLabel(selectedCards)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = SprutAccent,
                )
                Text(
                    recommendedWidgetTemplateDescription(selectedCards),
                    style = MaterialTheme.typography.bodySmall,
                    color = SprutTextMuted,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetQuickTemplate.entries.forEach { template ->
                        val label = when (template) {
                            WidgetQuickTemplate.RECOMMENDED -> "Авто · ${recommendedWidgetTemplateLabel(selectedCards)}"
                            WidgetQuickTemplate.COMPACT -> "Компактно"
                            WidgetQuickTemplate.INFORMATIVE -> "Больше данных"
                        }
                        FilterChip(
                            selected = matchedTemplate == template,
                            onClick = {
                                onConfigurationChange(
                                    applyWidgetQuickTemplate(configuration, template, selectedCards),
                                )
                            },
                            label = { Text(label) },
                            leadingIcon = if (matchedTemplate == template) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            colors = sprutFilterChipColors(),
                        )
                    }
                }
            }
        }

        item {
            WidgetEditorSection(
                title = "Состав и порядок",
                subtitle = "Первый объект сохраняется при уменьшении. Нажмите объект, чтобы настроить его показатели.",
            ) {
                configuration.items.forEachIndexed { index, item ->
                    val card = cards.cardForControl(item.controlId)
                    WidgetOrderRow(
                        title = card?.title ?: "Устройство временно недоступно",
                        subtitle = card?.displayServiceName().orEmpty(),
                        iconResource = card?.let(TileIconResolver::resource),
                        selected = item.controlId == activeItem?.controlId,
                        canMoveUp = index > 0,
                        canMoveDown = index < configuration.items.lastIndex,
                        onSelect = { onActiveControlChange(item.controlId) },
                        onMoveUp = {
                            onConfigurationChange(
                                configuration.copy(items = configuration.items.moved(index, index - 1)),
                            )
                        },
                        onMoveDown = {
                            onConfigurationChange(
                                configuration.copy(items = configuration.items.moved(index, index + 1)),
                            )
                        },
                        onRemove = {
                            onConfigurationChange(
                                configuration.copy(items = configuration.items.filterIndexed { itemIndex, _ ->
                                    itemIndex != index
                                }),
                            )
                        },
                    )
                }
            }
        }

        item {
            WidgetEditorSection(
                title = "Плотность",
                subtitle = "Ограничивает количество строк или мини-карточек. Реальный размер рабочего стола остаётся главным.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetInformationDensity.entries.forEach { density ->
                        FilterChip(
                            selected = configuration.density == density,
                            onClick = { onConfigurationChange(configuration.copy(density = density)) },
                            label = { Text(density.label()) },
                            colors = sprutFilterChipColors(),
                        )
                    }
                }
            }
        }

        if (activeItem != null && activeCard != null) {
            item {
                WidgetMetricEditor(
                    card = activeCard,
                    item = activeItem,
                    sharedPreference = preferenceByCard[activeCard.id],
                    onItemChange = { updated ->
                        onConfigurationChange(
                            configuration.copy(items = configuration.items.map { current ->
                                if (current.controlId == activeItem.controlId) updated else current
                            }),
                        )
                    },
                )
            }
        }

        item {
            WidgetEditorSection(
                title = "Блоки одиночной карточки",
                subtitle = if (configuration.items.size > 1) {
                    "Используются, когда панель сжата до одного объекта. В сетке всегда остаются название и состояние."
                } else {
                    "Верхние блоки имеют приоритет, когда места мало. Главное значение скрыть нельзя."
                },
            ) {
                configuration.orderedBlocks.forEachIndexed { index, block ->
                    WidgetBlockOrderRow(
                        block = block,
                        canMoveUp = index > 0,
                        canMoveDown = index < configuration.orderedBlocks.lastIndex,
                        canRemove = block != WidgetContentBlock.PRIMARY_VALUE,
                        onMoveUp = {
                            onConfigurationChange(
                                configuration.copy(
                                    orderedBlocks = configuration.orderedBlocks.moved(index, index - 1),
                                ),
                            )
                        },
                        onMoveDown = {
                            onConfigurationChange(
                                configuration.copy(
                                    orderedBlocks = configuration.orderedBlocks.moved(index, index + 1),
                                ),
                            )
                        },
                        onRemove = {
                            onConfigurationChange(
                                configuration.copy(
                                    orderedBlocks = configuration.orderedBlocks.filterNot { it == block },
                                ),
                            )
                        },
                    )
                }
                val hidden = DEFAULT_WIDGET_BLOCKS.filterNot(configuration.orderedBlocks::contains)
                if (hidden.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        hidden.forEach { block ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onConfigurationChange(
                                        configuration.copy(orderedBlocks = configuration.orderedBlocks + block),
                                    )
                                },
                                label = { Text("+ ${block.label()}") },
                                colors = sprutFilterChipColors(),
                            )
                        }
                    }
                }
            }
        }

        item {
            WidgetEditorSection(
                title = "Ручное обновление",
                subtitle = "Realtime и фоновое обновление работают независимо. На тесных многокарточных размерах кнопка скрывается автоматически.",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Показывать кнопку обновления", Modifier.weight(1f))
                    Switch(
                        checked = configuration.showRefresh,
                        onCheckedChange = {
                            onConfigurationChange(configuration.copy(showRefresh = it))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetMetricEditor(
    card: ServiceControlCard,
    item: WidgetItemConfiguration,
    sharedPreference: ServicePresentationPreference?,
    onItemChange: (WidgetItemConfiguration) -> Unit,
) {
    val values = card.characteristicValues()
    val automaticHeadline = card.headlineDisplayValue(sharedPreference)
    val effectiveHeadlineKey = item.headlineValueKey ?: automaticHeadline.key
    WidgetEditorSection(
        title = "Показатели · ${card.title}",
        subtitle = "Это влияет только на текст. Действие по нажатию остаётся привязано к управляющей характеристике сервиса.",
    ) {
        Text("Главное значение", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = item.headlineValueKey == null,
                onClick = { onItemChange(item.copy(headlineValueKey = null)) },
                label = { Text("Авто · ${automaticHeadline.label}") },
                colors = sprutFilterChipColors(),
            )
            values.forEach { value ->
                FilterChip(
                    selected = item.headlineValueKey == value.key,
                    onClick = {
                        onItemChange(
                            item.copy(
                                headlineValueKey = value.key,
                                secondaryValueKeys = item.secondaryValueKeys?.filterNot { it == value.key },
                            ),
                        )
                    },
                    label = { Text(value.label) },
                    colors = sprutFilterChipColors(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Дополнительные · до $MAX_WIDGET_SECONDARY_VALUES", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = item.secondaryValueKeys == null,
                onClick = { onItemChange(item.copy(secondaryValueKeys = null)) },
                label = { Text("Авто") },
                colors = sprutFilterChipColors(),
            )
            values.filterNot { it.key == effectiveHeadlineKey }.forEach { value ->
                val selected = value.key in item.secondaryValueKeys.orEmpty()
                FilterChip(
                    selected = selected,
                    onClick = {
                        val current = item.secondaryValueKeys.orEmpty()
                        val updated = if (selected) {
                            current - value.key
                        } else if (current.size < MAX_WIDGET_SECONDARY_VALUES) {
                            current + value.key
                        } else {
                            current
                        }
                        onItemChange(item.copy(secondaryValueKeys = updated))
                    },
                    label = { Text(value.label) },
                    colors = sprutFilterChipColors(),
                )
            }
        }
    }
}

@Composable
private fun WidgetEditorSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SprutTextMuted)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun WidgetOrderRow(
    title: String,
    subtitle: String,
    iconResource: Int?,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SprutControlShape)
            .background(if (selected) SprutAccent.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) SprutAccent.copy(alpha = 0.45f) else Color.Transparent, SprutControlShape)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconResource != null) {
            Icon(
                painterResource(iconResource),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) SprutAccent else SprutText,
            )
        } else {
            Icon(Icons.Rounded.Widgets, null, Modifier.size(24.dp), tint = SprutWarning)
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SprutTextMuted)
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Выше")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Ниже")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, "Убрать", tint = SprutError)
        }
    }
}

@Composable
private fun WidgetBlockOrderRow(
    block: WidgetContentBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(block.label(), Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Выше")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Ниже")
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(Icons.Rounded.DeleteOutline, "Скрыть")
        }
    }
}

@Composable
private fun WidgetLivePreview(
    cards: List<ServiceControlCard>,
    preferenceByCard: Map<String, ServicePresentationPreference>,
    configuration: WidgetLayoutConfiguration,
    sizeClass: WidgetSizeClass,
) {
    val resolvedItems = configuration.items.mapNotNull { item ->
        cards.cardForControl(item.controlId)?.let { card -> PreviewItem(item, card) }
    }
    val hostSize = previewHostSize(sizeClass)
    val previewWidth = when (sizeClass) {
        WidgetSizeClass.STRIP -> hostSize.widthDp.dp
        WidgetSizeClass.ICON -> 92.dp
        WidgetSizeClass.COMPACT -> 226.dp
        WidgetSizeClass.WIDE -> null
        WidgetSizeClass.TALL -> 226.dp
    }
    val height = hostSize.heightDp.dp
    val minimal = sizeClass == WidgetSizeClass.ICON || sizeClass == WidgetSizeClass.STRIP
    val singleCard = resolvedItems.size == 1 || minimal
    val activeSingleCard = singleCard && resolvedItems.firstOrNull()?.card?.isActive == true
    val widgetBackground = if (activeSingleCard) {
        listOf(Color(0xE026231D), Color(0xE026231D))
    } else {
        listOf(Color(0xE326292D), Color(0xD91B1D20))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthModifier = previewWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()
        Box(
            modifier = widthModifier
                .height(height)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(widgetBackground))
                .border(
                    1.dp,
                    if (activeSingleCard) SprutAccent.copy(alpha = 0.8f) else SprutGlassBorder,
                    RoundedCornerShape(20.dp),
                )
                .padding(8.dp),
        ) {
            if (resolvedItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Устройство недоступно", color = SprutWarning)
                }
            } else if (singleCard) {
                WidgetSinglePreview(
                    previewItem = resolvedItems.first(),
                    preference = preferenceByCard[resolvedItems.first().card.id],
                    configuration = configuration,
                    sizeClass = sizeClass,
                )
            } else {
                WidgetGridPreview(
                    items = resolvedItems,
                    preferenceByCard = preferenceByCard,
                    configuration = configuration,
                    sizeClass = sizeClass,
                )
            }
        }
    }
}

private data class PreviewItem(
    val configuration: WidgetItemConfiguration,
    val card: ServiceControlCard,
)

@Composable
private fun WidgetSinglePreview(
    previewItem: PreviewItem,
    preference: ServicePresentationPreference?,
    configuration: WidgetLayoutConfiguration,
    sizeClass: WidgetSizeClass,
) {
    val card = previewItem.card
    val content = resolveWidgetContent(card, configuration, previewItem.configuration, preference)
    val lines = visibleWidgetLines(
        content = content,
        configuration = configuration,
        sizeClass = sizeClass,
        fontScale = LocalDensity.current.fontScale,
    )
    if (sizeClass == WidgetSizeClass.ICON) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PreviewDeviceIcon(card, Modifier.size(38.dp))
            Text(
                compactWidgetValue(content.headline.value, configuration.items.size - 1),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    if (sizeClass == WidgetSizeClass.STRIP) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewDeviceIcon(card, Modifier.size(30.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                compactWidgetValue(content.headline.value, configuration.items.size - 1),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val hostSize = previewHostSize(sizeClass)
    val showRefresh = shouldShowWidgetRefresh(
        hostSize = hostSize,
        requested = configuration.showRefresh,
        fontScale = LocalDensity.current.fontScale,
    )
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        PreviewDeviceIcon(card, Modifier.size(42.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            lines.forEach { line ->
                Text(
                    line.text,
                    style = if (line.block == WidgetContentBlock.PRIMARY_VALUE) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (
                        line.block == WidgetContentBlock.TITLE ||
                        line.block == WidgetContentBlock.PRIMARY_VALUE
                    ) SprutText else SprutTextMuted,
                    fontWeight = if (line.block == WidgetContentBlock.PRIMARY_VALUE) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    maxLines = if (sizeClass == WidgetSizeClass.TALL) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showRefresh) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(22.dp), tint = SprutTextMuted)
        }
    }
}

@Composable
private fun WidgetGridPreview(
    items: List<PreviewItem>,
    preferenceByCard: Map<String, ServicePresentationPreference>,
    configuration: WidgetLayoutConfiguration,
    sizeClass: WidgetSizeClass,
) {
    val hostSize = previewHostSize(sizeClass)
    val grid = widgetGridLayout(
        hostSize = hostSize,
        itemCount = items.size,
        density = configuration.density,
        fontScale = LocalDensity.current.fontScale,
    )
    val visible = items.take(grid.visibleItemCount)
    val showRefresh = configuration.showRefresh && hostSize.widthDp >= 440f
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            visible.chunked(grid.columns).forEachIndexed { rowIndex, rowItems ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    rowItems.forEachIndexed { columnIndex, item ->
                        val itemIndex = rowIndex * grid.columns + columnIndex
                        val overflow = widgetOverflowLabel(
                            if (itemIndex == visible.lastIndex) grid.hiddenItemCount else 0,
                        )
                        val content = resolveWidgetContent(
                            item.card,
                            configuration,
                            item.configuration,
                            preferenceByCard[item.card.id],
                        )
                        val duplicateTitle = items.count { candidate ->
                            candidate.card.title.equals(item.card.title, true)
                        } > 1
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(3.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    if (item.card.isActive == true) SprutAccent.copy(alpha = 0.16f)
                                    else Color.White.copy(alpha = 0.045f),
                                )
                                .border(
                                    1.dp,
                                    if (item.card.isActive == true) SprutAccent.copy(alpha = 0.55f)
                                    else SprutGlassBorder,
                                    RoundedCornerShape(13.dp),
                                )
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            PreviewDeviceIcon(item.card, Modifier.size(26.dp), framed = false)
                            Text(
                                if (duplicateTitle) {
                                    "${item.card.title} · ${item.card.displayServiceName()}"
                                } else {
                                    item.card.title
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                content.headline.value,
                                style = MaterialTheme.typography.labelSmall,
                                color = SprutTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (
                                sizeClass == WidgetSizeClass.TALL &&
                                configuration.density == WidgetInformationDensity.DETAILED
                            ) {
                                content.secondary.firstOrNull()?.let { secondary ->
                                    Text(
                                        "${secondary.label} ${secondary.value}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SprutTextFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (overflow.isNotBlank()) {
                                Text(
                                    overflow,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SprutAccent,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showRefresh) {
            Spacer(Modifier.size(4.dp))
            Icon(Icons.Rounded.Refresh, null, Modifier.size(22.dp), tint = SprutTextMuted)
        }
    }
}

@Composable
private fun PreviewDeviceIcon(
    card: ServiceControlCard,
    modifier: Modifier,
    framed: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (framed) 13.dp else 8.dp))
            .background(if (framed) Color.White.copy(alpha = 0.07f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(TileIconResolver.resource(card)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(if (framed) 8.dp else 2.dp),
            tint = if (card.isActive == true) SprutAccent else SprutText,
        )
    }
}

private fun WidgetContentBlock.label(): String = when (this) {
    WidgetContentBlock.TITLE -> "Название"
    WidgetContentBlock.PRIMARY_VALUE -> "Главное значение"
    WidgetContentBlock.SECONDARY_VALUES -> "Дополнительные показатели"
    WidgetContentBlock.CONTEXT -> "Тип сервиса и комната"
}

private fun WidgetInformationDensity.label(): String = when (this) {
    WidgetInformationDensity.COMPACT -> "Компактно"
    WidgetInformationDensity.BALANCED -> "Баланс"
    WidgetInformationDensity.DETAILED -> "Подробно"
}

private fun List<ServiceControlCard>.cardForControl(controlId: String): ServiceControlCard? =
    firstOrNull { card -> card.controls.any { it.id == controlId } }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply {
        add(to, removeAt(from))
    }
}
