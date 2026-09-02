package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CharacteristicDisplayValue
import io.github.nikitau.spruthubhelper.data.ServiceCardTemplate
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class WidgetInformationDensity {
    COMPACT,
    BALANCED,
    DETAILED,
}

@Serializable
enum class WidgetContentBlock {
    TITLE,
    PRIMARY_VALUE,
    SECONDARY_VALUES,
    CONTEXT,
}

internal enum class WidgetQuickTemplate {
    RECOMMENDED,
    COMPACT,
    INFORMATIVE,
}

@Serializable
data class WidgetItemConfiguration(
    val controlId: String,
    val headlineValueKey: String? = null,
    val secondaryValueKeys: List<String>? = null,
)

/**
 * Versioned, per-widget visual settings.
 *
 * The command target deliberately lives outside this class. Selecting a
 * different displayed characteristic must never redirect a tap to another
 * SprutHub characteristic.
 */
@Serializable
data class WidgetLayoutConfiguration(
    val schemaVersion: Int = CURRENT_WIDGET_LAYOUT_SCHEMA,
    val density: WidgetInformationDensity = WidgetInformationDensity.BALANCED,
    val orderedBlocks: List<WidgetContentBlock> = DEFAULT_WIDGET_BLOCKS,
    val items: List<WidgetItemConfiguration> = emptyList(),
    val showRefresh: Boolean = true,
)

internal const val CURRENT_WIDGET_LAYOUT_SCHEMA = 1
internal const val MAX_WIDGET_SECONDARY_VALUES = 3
internal const val MAX_WIDGET_ITEMS = 8

internal val DEFAULT_WIDGET_BLOCKS = listOf(
    WidgetContentBlock.TITLE,
    WidgetContentBlock.PRIMARY_VALUE,
    WidgetContentBlock.SECONDARY_VALUES,
    WidgetContentBlock.CONTEXT,
)

internal fun recommendedWidgetTemplateLabel(cards: List<ServiceControlCard>): String {
    val template = cards.singleOrNull()?.template
        ?: cards.map(ServiceControlCard::template).distinct().singleOrNull()
    return when (template) {
        ServiceCardTemplate.CLIMATE -> "Климат"
        ServiceCardTemplate.LIGHT -> "Свет"
        ServiceCardTemplate.SENSOR -> "Датчик"
        ServiceCardTemplate.SCENE -> "Сценарий"
        ServiceCardTemplate.OUTLET -> "Розетка"
        ServiceCardTemplate.COVER -> "Шторы и приводы"
        ServiceCardTemplate.LOCK,
        ServiceCardTemplate.SECURITY,
        -> "Безопасность"
        ServiceCardTemplate.FAN -> "Вентиляция"
        ServiceCardTemplate.VACUUM -> "Уборка"
        ServiceCardTemplate.MEDIA -> "Медиа"
        ServiceCardTemplate.SWITCH,
        ServiceCardTemplate.RANGE,
        ServiceCardTemplate.GENERIC,
        null,
        -> if (cards.size > 1) "Несколько устройств" else "Устройство"
    }
}

internal fun recommendedWidgetTemplateDescription(cards: List<ServiceControlCard>): String {
    val template = cards.singleOrNull()?.template
        ?: cards.map(ServiceControlCard::template).distinct().singleOrNull()
    return when (template) {
        ServiceCardTemplate.CLIMATE -> "Температура и связанные показатели без лишних подписей"
        ServiceCardTemplate.LIGHT -> "Название, состояние и яркость, если она доступна"
        ServiceCardTemplate.SENSOR -> "Главное измерение первым и дополнительные показатели ниже"
        ServiceCardTemplate.SCENE -> "Компактный запуск без пустых служебных строк"
        ServiceCardTemplate.OUTLET -> "Состояние и электрические показатели"
        ServiceCardTemplate.COVER -> "Текущее положение и состояние движения"
        ServiceCardTemplate.LOCK,
        ServiceCardTemplate.SECURITY,
        -> "Главное состояние и важные связанные датчики"
        ServiceCardTemplate.FAN -> "Состояние и скорость вентилятора"
        ServiceCardTemplate.VACUUM -> "Текущий режим и состояние уборки"
        ServiceCardTemplate.MEDIA -> "Состояние и выбранный режим устройства"
        ServiceCardTemplate.SWITCH,
        ServiceCardTemplate.RANGE,
        ServiceCardTemplate.GENERIC,
        null,
        -> if (cards.size > 1) {
            "Сбалансированная сетка; при большом составе доступны все восемь объектов"
        } else {
            "Универсальная карточка с названием и главным состоянием"
        }
    }
}

internal fun applyWidgetQuickTemplate(
    configuration: WidgetLayoutConfiguration,
    template: WidgetQuickTemplate,
    cards: List<ServiceControlCard>,
): WidgetLayoutConfiguration {
    val cardTemplate = cards.singleOrNull()?.template
        ?: cards.map(ServiceControlCard::template).distinct().singleOrNull()
    val (density, blocks) = when (template) {
        WidgetQuickTemplate.COMPACT -> WidgetInformationDensity.COMPACT to listOf(
            WidgetContentBlock.TITLE,
            WidgetContentBlock.PRIMARY_VALUE,
        )
        WidgetQuickTemplate.INFORMATIVE -> WidgetInformationDensity.DETAILED to DEFAULT_WIDGET_BLOCKS
        WidgetQuickTemplate.RECOMMENDED -> when {
            cards.size > 4 -> WidgetInformationDensity.DETAILED to listOf(
                WidgetContentBlock.TITLE,
                WidgetContentBlock.PRIMARY_VALUE,
                WidgetContentBlock.SECONDARY_VALUES,
            )
            cardTemplate == ServiceCardTemplate.SENSOR -> WidgetInformationDensity.DETAILED to listOf(
                WidgetContentBlock.PRIMARY_VALUE,
                WidgetContentBlock.TITLE,
                WidgetContentBlock.SECONDARY_VALUES,
                WidgetContentBlock.CONTEXT,
            )
            cardTemplate == ServiceCardTemplate.CLIMATE -> WidgetInformationDensity.DETAILED to
                DEFAULT_WIDGET_BLOCKS
            cardTemplate == ServiceCardTemplate.SCENE -> WidgetInformationDensity.COMPACT to listOf(
                WidgetContentBlock.TITLE,
                WidgetContentBlock.PRIMARY_VALUE,
            )
            else -> WidgetInformationDensity.BALANCED to listOf(
                WidgetContentBlock.TITLE,
                WidgetContentBlock.PRIMARY_VALUE,
                WidgetContentBlock.SECONDARY_VALUES,
            )
        }
    }
    return configuration.copy(density = density, orderedBlocks = blocks).normalized()
}

internal fun WidgetLayoutConfiguration.matchesQuickTemplate(
    template: WidgetQuickTemplate,
    cards: List<ServiceControlCard>,
): Boolean {
    val candidate = applyWidgetQuickTemplate(this, template, cards)
    return density == candidate.density && orderedBlocks == candidate.orderedBlocks
}

internal fun WidgetItemConfiguration.normalized(): WidgetItemConfiguration {
    val headline = headlineValueKey?.trim()?.takeIf(String::isNotBlank)
    return copy(
        controlId = controlId.trim(),
        headlineValueKey = headline,
        secondaryValueKeys = secondaryValueKeys
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.filterNot { it == headline }
            ?.take(MAX_WIDGET_SECONDARY_VALUES),
    )
}

internal fun WidgetLayoutConfiguration.normalized(
    fallbackPrimaryControlId: String? = null,
): WidgetLayoutConfiguration {
    val blocks = orderedBlocks
        .distinct()
        .let { ordered ->
            if (WidgetContentBlock.PRIMARY_VALUE in ordered) ordered
            else listOf(WidgetContentBlock.PRIMARY_VALUE) + ordered
        }
    val normalizedItems = items
        .map(WidgetItemConfiguration::normalized)
        .filter { it.controlId.isNotBlank() }
        .distinctBy(WidgetItemConfiguration::controlId)
        .toMutableList()
    fallbackPrimaryControlId?.trim()?.takeIf(String::isNotBlank)?.let { primaryId ->
        val existingIndex = normalizedItems.indexOfFirst { it.controlId == primaryId }
        when {
            existingIndex > 0 -> normalizedItems.add(0, normalizedItems.removeAt(existingIndex))
            existingIndex < 0 -> normalizedItems.add(0, WidgetItemConfiguration(primaryId))
        }
    }
    return copy(
        schemaVersion = CURRENT_WIDGET_LAYOUT_SCHEMA,
        orderedBlocks = blocks,
        items = normalizedItems.take(MAX_WIDGET_ITEMS),
    )
}

internal object WidgetLayoutConfigurationCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(configuration: WidgetLayoutConfiguration): String =
        json.encodeToString(configuration.normalized())

    fun decode(raw: String?): WidgetLayoutConfiguration? = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded ->
            runCatching { json.decodeFromString<WidgetLayoutConfiguration>(encoded).normalized() }
                .getOrNull()
        }
}

internal enum class WidgetSizeClass {
    /** A one-cell landscape slot whose height cannot fit the regular card. */
    STRIP,
    ICON,
    COMPACT,
    WIDE,
    TALL,
}

internal data class WidgetHostSize(
    val widthDp: Float,
    val heightDp: Float,
)

internal data class WidgetGridLayout(
    val columns: Int,
    val rows: Int,
    val visibleItemCount: Int,
    val hiddenItemCount: Int,
)

internal const val MAX_RESPONSIVE_WIDGET_SIZES = 16

internal fun WidgetHostSize.sizeClass(): WidgetSizeClass = when {
    heightDp < 72f -> WidgetSizeClass.STRIP
    widthDp < 120f -> WidgetSizeClass.ICON
    heightDp >= 150f -> WidgetSizeClass.TALL
    widthDp >= 320f -> WidgetSizeClass.WIDE
    else -> WidgetSizeClass.COMPACT
}

/**
 * Launcher supplied sizes are external input. Keep only finite, useful values
 * and respect RemoteViews.MAX_INIT_VIEW_COUNT before constructing a responsive
 * RemoteViews map. The original order is retained because some OEM launchers
 * put their currently active size first.
 */
internal fun boundedWidgetHostSizes(sizes: List<WidgetHostSize>): List<WidgetHostSize> = sizes
    .filter { size ->
        size.widthDp.isFinite() &&
            size.heightDp.isFinite() &&
            size.widthDp > 0f &&
            size.heightDp > 0f &&
            size.widthDp <= 4_096f &&
            size.heightDp <= 4_096f
    }
    .distinct()
    .take(MAX_RESPONSIVE_WIDGET_SIZES)

internal fun safeWidgetHostSize(
    widthDp: Float,
    heightDp: Float,
    fallback: WidgetHostSize = WidgetHostSize(widthDp = 226f, heightDp = 102f),
): WidgetHostSize = WidgetHostSize(
    widthDp = widthDp.takeIf { it.isFinite() && it > 0f && it <= 4_096f } ?: fallback.widthDp,
    heightDp = heightDp.takeIf { it.isFinite() && it > 0f && it <= 4_096f } ?: fallback.heightDp,
)

internal fun shouldShowWidgetRefresh(
    hostSize: WidgetHostSize,
    requested: Boolean,
    fontScale: Float = 1f,
): Boolean {
    if (!requested || hostSize.sizeClass() == WidgetSizeClass.ICON || hostSize.sizeClass() == WidgetSizeClass.STRIP) {
        return false
    }
    val safeFontScale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 2f) ?: 1f
    return hostSize.widthDp >= 180f + ((safeFontScale - 1f) * 40f)
}

internal fun widgetGridLayout(
    hostSize: WidgetHostSize,
    itemCount: Int,
    density: WidgetInformationDensity,
    fontScale: Float = 1f,
): WidgetGridLayout {
    val safeHostSize = safeWidgetHostSize(hostSize.widthDp, hostSize.heightDp)
    val safeFontScale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 2f) ?: 1f
    val minimumColumnWidth = 86f + ((safeFontScale - 1f) * 40f)
    val minimumRowHeight = 84f + ((safeFontScale - 1f) * 30f)
    val maximumRows = (safeHostSize.heightDp / minimumRowHeight)
        .toInt()
        .coerceIn(1, 2)
    val maximumColumns = (safeHostSize.widthDp / minimumColumnWidth)
        .toInt()
        .coerceIn(1, 4)
    val densityLimit = when (density) {
        WidgetInformationDensity.COMPACT -> 2
        WidgetInformationDensity.BALANCED -> 4
        WidgetInformationDensity.DETAILED -> MAX_WIDGET_ITEMS
    }
    val visibleCount = minOf(
        itemCount.coerceAtLeast(0),
        maximumRows * maximumColumns,
        densityLimit,
    )
    val columns = when {
        visibleCount <= 0 -> 1
        maximumRows > 1 && visibleCount > 2 -> minOf(maximumColumns, (visibleCount + 1) / 2)
        visibleCount <= maximumColumns -> visibleCount
        else -> minOf(maximumColumns, (visibleCount + 1) / 2)
    }
    val rows = if (visibleCount <= 0) 1 else (visibleCount + columns - 1) / columns
    return WidgetGridLayout(
        columns = columns,
        rows = rows,
        visibleItemCount = visibleCount,
        hiddenItemCount = (itemCount - visibleCount).coerceAtLeast(0),
    )
}

internal fun previewHostSize(sizeClass: WidgetSizeClass): WidgetHostSize = when (sizeClass) {
    WidgetSizeClass.STRIP -> WidgetHostSize(widthDp = 269f, heightDp = 51f)
    WidgetSizeClass.ICON -> WidgetHostSize(widthDp = 92f, heightDp = 102f)
    WidgetSizeClass.COMPACT -> WidgetHostSize(widthDp = 226f, heightDp = 102f)
    WidgetSizeClass.WIDE -> WidgetHostSize(widthDp = 496f, heightDp = 102f)
    WidgetSizeClass.TALL -> WidgetHostSize(widthDp = 226f, heightDp = 220f)
}

internal fun widgetOverflowLabel(hiddenItemCount: Int): String =
    hiddenItemCount.takeIf { it > 0 }?.let { "+$it ещё" }.orEmpty()

internal fun compactWidgetValue(
    value: String,
    hiddenItemCount: Int,
    narrow: Boolean = false,
): String =
    listOf(
        if (narrow) narrowWidgetValue(value) else value,
        hiddenItemCount.takeIf { it > 0 }?.let { "+$it" }.orEmpty(),
    )
        .filter(String::isNotBlank)
        .joinToString(if (narrow) " " else " · ")

private fun narrowWidgetValue(value: String): String {
    val normalized = value.trim()
    return when (normalized.lowercase()) {
        "включено" -> "Вкл."
        "выключено" -> "Выкл."
        "открыто" -> "Откр."
        "закрыто" -> "Закр."
        "ожидаем spruthub", "ожидание…", "подтверждаем…" -> "…"
        "команда недоступна" -> "—"
        else -> normalized.substringAfter("Последнее: ", normalized)
    }
}

internal data class WidgetContentLine(
    val block: WidgetContentBlock,
    val text: String,
)

internal data class WidgetResolvedContent(
    val headline: CharacteristicDisplayValue,
    val secondary: List<CharacteristicDisplayValue>,
    val lines: List<WidgetContentLine>,
)

/** Resolves the same text model for the live Compose preview and RemoteViews. */
internal fun resolveWidgetContent(
    card: ServiceControlCard,
    configuration: WidgetLayoutConfiguration,
    item: WidgetItemConfiguration,
    sharedPreference: ServicePresentationPreference? = null,
    primaryValueOverride: String? = null,
): WidgetResolvedContent {
    val normalized = configuration.normalized()
    val normalizedItem = item.normalized()
    val values = card.characteristicValues()
    val valuesByKey = values.associateBy(CharacteristicDisplayValue::key)
    val sharedHeadline = card.headlineDisplayValue(sharedPreference)
    val headline = normalizedItem.headlineValueKey
        ?.let(valuesByKey::get)
        ?: sharedHeadline
    val secondary = normalizedItem.secondaryValueKeys?.mapNotNull(valuesByKey::get)
        ?: card.secondaryDisplayValues(sharedPreference)
    val distinctContext = listOf(card.displayServiceName(), card.room)
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.equals(card.title.trim(), ignoreCase = true) }
        .distinctBy(String::lowercase)
        .joinToString(" · ")
    val primaryValue = primaryValueOverride ?: headline.value
    val blockText = mapOf(
        WidgetContentBlock.TITLE to card.title.trim(),
        WidgetContentBlock.PRIMARY_VALUE to listOf(headline.label.trim(), primaryValue.trim())
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .joinToString(" · "),
        WidgetContentBlock.SECONDARY_VALUES to secondary.joinToString(" · ") { value ->
            listOf(value.label.trim(), value.value.trim())
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
                .joinToString(" ")
        },
        WidgetContentBlock.CONTEXT to distinctContext,
    )
    val lines = normalized.orderedBlocks.mapNotNull { block ->
        blockText.getValue(block).takeIf(String::isNotBlank)?.let { WidgetContentLine(block, it) }
    }
    return WidgetResolvedContent(
        headline = headline,
        secondary = secondary,
        lines = lines,
    )
}

internal fun visibleWidgetLines(
    content: WidgetResolvedContent,
    configuration: WidgetLayoutConfiguration,
    sizeClass: WidgetSizeClass,
    fontScale: Float = 1f,
): List<WidgetContentLine> {
    if (content.lines.isEmpty()) return emptyList()
    if (sizeClass == WidgetSizeClass.ICON || sizeClass == WidgetSizeClass.STRIP) {
        return listOf(
            content.lines.firstOrNull { it.block == WidgetContentBlock.PRIMARY_VALUE }
                ?: content.lines.first(),
        )
    }
    val hostCapacity = when (sizeClass) {
        WidgetSizeClass.STRIP -> 1
        WidgetSizeClass.ICON -> 1
        WidgetSizeClass.COMPACT -> 2
        WidgetSizeClass.WIDE -> 3
        WidgetSizeClass.TALL -> 4
    }
    val densityCapacity = when (configuration.density) {
        WidgetInformationDensity.COMPACT -> 2
        WidgetInformationDensity.BALANCED -> 3
        WidgetInformationDensity.DETAILED -> 4
    }
    val safeFontScale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 2f) ?: 1f
    val fontCapacity = when {
        safeFontScale >= 1.75f && sizeClass == WidgetSizeClass.TALL -> 2
        safeFontScale >= 1.75f -> 1
        safeFontScale >= 1.3f && sizeClass == WidgetSizeClass.TALL -> 3
        safeFontScale >= 1.3f -> 2
        else -> 4
    }
    val capacity = minOf(hostCapacity, densityCapacity, fontCapacity)
    val selected = content.lines.take(capacity).toMutableList()
    val primary = content.lines.firstOrNull { it.block == WidgetContentBlock.PRIMARY_VALUE }
    if (primary != null && selected.none { it.block == WidgetContentBlock.PRIMARY_VALUE }) {
        selected[selected.lastIndex] = primary
        val order = content.lines.withIndex().associate { it.value.block to it.index }
        selected.sortBy { order.getValue(it.block) }
    }
    return selected
}
