package io.github.nikitau.spruthubhelper.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One logical SprutHub service card. Characteristics remain addressable by
 * their own ids, but no longer become duplicate-looking cards in the panel.
 */
data class ServiceControlCard(
    val id: String,
    val accessoryId: String,
    val serviceId: String,
    val title: String,
    val serviceName: String,
    val room: String,
    val kind: DeviceKind,
    val serviceType: String,
    val controls: List<SprutControl>,
    val primaryControl: SprutControl,
    val linkedServiceIds: List<String>,
    /** Service ids whose characteristics were safely composed into this card. */
    val memberServiceIds: List<String>,
    val isPrimaryService: Boolean,
) {
    val isActive: Boolean?
        get() = when (primaryControl.behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE -> primaryControl.value.asBooleanOrNull()
            else -> null
        }

    val supportsRange: Boolean
        get() = primaryControl.behavior == ControlBehavior.RANGE ||
            primaryControl.behavior == ControlBehavior.TOGGLE_RANGE

    fun headlineValue(): String = when (primaryControl.behavior) {
        ControlBehavior.TOGGLE -> when (primaryControl.value.asBooleanOrNull()) {
            true -> "Включено"
            false -> "Выключено"
            null -> "Нет данных"
        }
        ControlBehavior.TOGGLE_RANGE -> {
            when (primaryControl.value.asBooleanOrNull()) {
                true -> "Включено"
                false -> "Выключено"
                null -> "Нет данных"
            }
        }
        ControlBehavior.RANGE, ControlBehavior.SENSOR -> formattedValue(primaryControl)
        ControlBehavior.BUTTON -> "Готово к запуску"
    }

    fun availableAttributes(): List<SprutControl> = controls
        .asSequence()
        .filterNot { it.id == primaryControl.id }
        // Characteristic ids are only unique inside one service. Linked sensor
        // services commonly reuse ids such as `1`, so use the full control id.
        .distinctBy(SprutControl::id)
        .sortedWith(
            compareByDescending<SprutControl>(::attributePriority)
                .thenBy { attributeLabel(it).lowercase() },
        )
        .toList()

    fun defaultAttributes(limit: Int = DEFAULT_ATTRIBUTE_COUNT): List<SprutControl> =
        availableAttributes().take(limit)

    fun selectedAttributes(item: PanelItem, limit: Int = DEFAULT_ATTRIBUTE_COUNT): List<SprutControl> {
        val selectedIds = item.attributeControlIds
        return if (selectedIds == null) {
            defaultAttributes(limit)
        } else {
            val byId = availableAttributes().associateBy(SprutControl::id)
            selectedIds.mapNotNull(byId::get).take(limit)
        }
    }

    fun attributeLabel(control: SprutControl): String {
        val base = characteristicLabel(control)
        val sameLabel = controls
            .asSequence()
            .filterNot { it.id == primaryControl.id }
            .distinctBy(SprutControl::id)
            .filter { characteristicLabel(it).equals(base, ignoreCase = true) }
            .toList()
        if (sameLabel.size < 2) return base

        val spansSeveralServices = sameLabel.map(SprutControl::serviceId).distinct().size > 1
        val qualifier = if (spansSeveralServices) {
            control.serviceName.takeIf(String::isNotBlank)
                ?: control.subtitle.substringBefore(" · ").takeIf(String::isNotBlank)
        } else {
            control.characteristicName.takeIf(String::isNotBlank)
                ?: control.subtitle.substringAfterLast(" · ").takeIf(String::isNotBlank)
        }
        return qualifier
            ?.takeUnless { it.equals(base, ignoreCase = true) }
            ?.let { "$it · $base" }
            ?: base
    }

    fun attributeValue(control: SprutControl): String = formattedValue(control)

    fun rangeLabel(): String {
        val type = normalizeType(
            primaryControl.rangeCharacteristicType.ifBlank { primaryControl.characteristicType },
        )
        return when (type) {
            "targettemperature", "coolingthresholdtemperature", "heatingthresholdtemperature" -> "Задано"
            "brightness" -> "Яркость"
            "targetposition", "currentposition" -> "Положение"
            "rotationspeed", "fanspeed", "speed" -> "Скорость"
            "volume" -> "Громкость"
            else -> "Настроить"
        }
    }

    fun rangeValue(value: Double? = primaryControl.value.numberValue): String =
        value?.let { formatNumber(it, primaryControl.unit) } ?: "—"

    fun containsService(candidateServiceId: String): Boolean = candidateServiceId in memberServiceIds

    companion object {
        const val DEFAULT_ATTRIBUTE_COUNT = 2
    }
}

/** Stable id persisted by the custom panel. */
fun serviceCardId(control: SprutControl): String = if (control.accessoryId.isBlank()) {
    "control:${control.id}"
} else {
    "service:${control.accessoryId}:${control.serviceId}"
}

fun buildServiceControlCards(controls: List<SprutControl>): List<ServiceControlCard> = controls
    .distinctBy(SprutControl::id)
    .groupBy(::serviceCardId)
    .map { (cardId, grouped) ->
        val primary = grouped.maxWithOrNull(
            compareBy<SprutControl>(::primaryPriority)
                .thenBy { it.id == "${it.accessoryId}:${it.serviceId}:main" },
        ) ?: grouped.first()
        ServiceControlCard(
            id = cardId,
            accessoryId = primary.accessoryId,
            serviceId = primary.serviceId,
            title = primary.title,
            serviceName = grouped.firstNotNullOfOrNull { control ->
                control.serviceName.takeIf(String::isNotBlank)
            } ?: primary.subtitle.substringBefore(" · ").takeIf(String::isNotBlank).orEmpty(),
            room = primary.room,
            kind = primary.kind,
            serviceType = primary.sourceType,
            controls = grouped,
            primaryControl = primary,
            linkedServiceIds = grouped.flatMap(SprutControl::linkedServiceIds).distinct(),
            memberServiceIds = listOf(primary.serviceId),
            isPrimaryService = grouped.any(SprutControl::servicePrimary),
        )
    }
    .let(::composeLinkedServiceCards)
    .sortedWith(
        compareBy<ServiceControlCard>({ it.room.lowercase() }, { it.title.lowercase() })
            .thenByDescending(ServiceControlCard::isPrimaryService)
            .thenBy { it.serviceName.lowercase() }
            .thenBy(ServiceControlCard::id),
    )

internal fun List<ServiceControlCard>.findCardForService(
    accessoryId: String,
    serviceId: String,
): ServiceControlCard? = firstOrNull { card ->
    card.accessoryId == accessoryId && card.containsService(serviceId)
}

/**
 * SprutHub declares service topology with `primary` and `linkedServices`.
 * A single action service and its read-only satellites become one card. If a
 * linked component contains several independent actions, every action remains
 * available and only an unambiguously linked sensor group is attached to it.
 */
private fun composeLinkedServiceCards(cards: List<ServiceControlCard>): List<ServiceControlCard> = cards
    .groupBy { card ->
        if (card.accessoryId.isBlank()) "control:${card.id}" else "accessory:${card.accessoryId}"
    }
    .values
    .flatMap(::composeAccessoryCards)

private fun composeAccessoryCards(cards: List<ServiceControlCard>): List<ServiceControlCard> {
    if (cards.size < 2 || cards.first().accessoryId.isBlank()) return cards

    val byServiceId = cards.associateBy(ServiceControlCard::serviceId)
    val adjacency = cards.associate { it.serviceId to linkedSetOf<String>() }.toMutableMap()
    cards.forEach { card ->
        card.linkedServiceIds.forEach { linkedId ->
            if (linkedId == card.serviceId || linkedId !in byServiceId) return@forEach
            adjacency.getValue(card.serviceId).add(linkedId)
            adjacency.getValue(linkedId).add(card.serviceId)
        }
    }

    val visited = mutableSetOf<String>()
    return cards.flatMap { start ->
        if (!visited.add(start.serviceId)) return@flatMap emptyList()
        val pending = ArrayDeque<String>().apply { add(start.serviceId) }
        val componentIds = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            componentIds.add(current)
            adjacency.getValue(current).forEach { neighbour ->
                if (visited.add(neighbour)) pending.add(neighbour)
            }
        }
        val component = cards.filter { it.serviceId in componentIds }
        composeLinkedComponent(component, adjacency)
    }
}

private fun composeLinkedComponent(
    component: List<ServiceControlCard>,
    adjacency: Map<String, Set<String>>,
): List<ServiceControlCard> {
    if (component.size < 2) return component
    val actionCards = component.filter(ServiceControlCard::hasAction)
    if (actionCards.size <= 1) {
        val root = actionCards.singleOrNull() ?: selectComponentRoot(component)
        return listOf(mergeServiceCards(root, component))
    }

    val actionIds = actionCards.mapTo(mutableSetOf(), ServiceControlCard::serviceId)
    val sensorCards = component.filterNot(ServiceControlCard::hasAction)
    val sensorsById = sensorCards.associateBy(ServiceControlCard::serviceId)
    val visitedSensors = mutableSetOf<String>()
    val sensorsForAction = actionCards.associate { it.serviceId to mutableListOf<ServiceControlCard>() }
    val standaloneSensorCards = mutableListOf<ServiceControlCard>()

    sensorCards.forEach { start ->
        if (!visitedSensors.add(start.serviceId)) return@forEach
        val pending = ArrayDeque<String>().apply { add(start.serviceId) }
        val sensorGroupIds = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            sensorGroupIds.add(current)
            adjacency.getValue(current).forEach { neighbour ->
                if (neighbour in sensorsById && visitedSensors.add(neighbour)) pending.add(neighbour)
            }
        }
        val sensorGroup = sensorCards.filter { it.serviceId in sensorGroupIds }
        val adjacentActions = sensorGroupIds
            .flatMap { adjacency.getValue(it) }
            .filterTo(linkedSetOf()) { it in actionIds }
        if (adjacentActions.size == 1) {
            sensorsForAction.getValue(adjacentActions.single()).addAll(sensorGroup)
        } else {
            val root = selectComponentRoot(sensorGroup)
            standaloneSensorCards.add(mergeServiceCards(root, sensorGroup))
        }
    }

    return actionCards.map { action ->
        mergeServiceCards(action, listOf(action) + sensorsForAction.getValue(action.serviceId))
    } + standaloneSensorCards
}

private fun ServiceControlCard.hasAction(): Boolean = primaryControl.behavior != ControlBehavior.SENSOR

private fun selectComponentRoot(cards: List<ServiceControlCard>): ServiceControlCard = cards
    .sortedWith(
        compareByDescending<ServiceControlCard>(ServiceControlCard::isPrimaryService)
            .thenByDescending(ServiceControlCard::hasAction)
            .thenByDescending { primaryPriority(it.primaryControl) }
            .thenBy { it.serviceId.toLongOrNull() ?: Long.MAX_VALUE }
            .thenBy(ServiceControlCard::serviceId),
    )
    .first()

private fun mergeServiceCards(
    root: ServiceControlCard,
    members: List<ServiceControlCard>,
): ServiceControlCard {
    val orderedMembers = listOf(root) + members.filterNot { it.serviceId == root.serviceId }
    val memberServiceIds = orderedMembers.flatMap(ServiceControlCard::memberServiceIds).distinct()
    return root.copy(
        controls = orderedMembers.flatMap(ServiceControlCard::controls).distinctBy(SprutControl::id),
        linkedServiceIds = (orderedMembers.flatMap(ServiceControlCard::linkedServiceIds) + memberServiceIds)
            .filterNot { it == root.serviceId }
            .distinct(),
        memberServiceIds = memberServiceIds,
        isPrimaryService = orderedMembers.any(ServiceControlCard::isPrimaryService),
    )
}

/**
 * SprutHub accessories may expose several independently controllable services.
 * Keep the accessory as a settings section and every independent action as a
 * card; linked read-only services remain available inside their logical card.
 */
data class AccessoryControlGroup(
    val key: String,
    val title: String,
    val room: String,
    val controls: List<SprutControl>,
) {
    val serviceCards: List<ServiceControlCard> = buildServiceControlCards(controls)

    fun serviceLabel(control: SprutControl): String = serviceCards
        .firstOrNull { card -> card.controls.any { it.id == control.id } }
        ?.let(::serviceLabel)
        ?: control.subtitle.takeIf(String::isNotBlank)
        ?: control.sourceType.takeIf(String::isNotBlank)
        ?: title

    fun serviceLabel(card: ServiceControlCard): String = card.serviceName
        .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        ?: readableType(card.serviceType)
        ?: if (serviceCards.size == 1) title else "Сервис ${serviceCards.indexOf(card) + 1}"

    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        return listOf(title, room).any { it.contains(needle, ignoreCase = true) } || serviceCards.any { card ->
            listOf(card.title, card.serviceName, card.serviceType, card.headlineValue())
                .any { it.contains(needle, ignoreCase = true) } || card.controls.any { control ->
                listOf(control.characteristicName, control.characteristicType, control.displayValue)
                    .any { it.contains(needle, ignoreCase = true) }
            }
        }
    }
}

fun groupControlsByAccessory(controls: List<SprutControl>): List<AccessoryControlGroup> = controls
    .distinctBy(SprutControl::id)
    .groupBy { control ->
        if (control.accessoryId.isBlank()) "control:${control.id}" else "accessory:${control.accessoryId}"
    }
    .map { (key, grouped) ->
        AccessoryControlGroup(
            key = key,
            title = grouped.first().title,
            room = grouped.first().room,
            controls = grouped,
        )
    }
    .sortedWith(
        compareBy<AccessoryControlGroup>({ it.room.lowercase() }, { it.title.lowercase() }, AccessoryControlGroup::key),
    )

private fun primaryPriority(control: SprutControl): Int = when (control.behavior) {
    ControlBehavior.TOGGLE_RANGE -> 1_000
    ControlBehavior.TOGGLE -> 900
    ControlBehavior.BUTTON -> 800
    ControlBehavior.RANGE -> 700
    ControlBehavior.SENSOR -> 100 + attributePriority(control)
}

private fun attributePriority(control: SprutControl): Int = when (normalizeType(control.characteristicType)) {
    "currenttemperature" -> 600
    "currentheatercoolerstate", "currentheatingcoolingstate", "currentoperationalstate" -> 560
    "targetheatercoolerstate", "targetheatingcoolingstate", "targetoperationalstate" -> 540
    "currentrelativehumidity" -> 520
    "fanspeed", "rotationspeed", "currentfanstate" -> 500
    "currentposition", "positionstate" -> 480
    "outletinuse", "inuse" -> 460
    "airquality" -> 440
    "statusfault", "statusjammed", "operationalerror" -> 420
    "online", "statusactive" -> 400
    "batterylevel", "statuslowbattery" -> 380
    else -> if (!control.writable) 200 else 100
}

private fun characteristicLabel(control: SprutControl): String = when (normalizeType(control.characteristicType)) {
    "currenttemperature" -> "Сейчас"
    "targettemperature", "coolingthresholdtemperature", "heatingthresholdtemperature" -> "Задано"
    "currentheatercoolerstate", "currentheatingcoolingstate", "currentoperationalstate" -> "Режим"
    "targetheatercoolerstate", "targetheatingcoolingstate", "targetoperationalstate" -> "Заданный режим"
    "currentrelativehumidity" -> "Влажность"
    "fanspeed", "rotationspeed", "currentfanstate" -> "Вентилятор"
    "currentposition" -> "Положение"
    "positionstate" -> "Движение"
    "outletinuse", "inuse" -> "Нагрузка"
    "airquality" -> "Воздух"
    "statusfault", "operationalerror" -> "Ошибка"
    "statusjammed" -> "Заклинивание"
    "online", "statusactive" -> "Связь"
    "batterylevel" -> "Батарея"
    "statuslowbattery" -> "Низкий заряд"
    else -> control.characteristicName
        .takeIf(String::isNotBlank)
        ?: readableType(control.characteristicType)
        ?: control.subtitle.substringAfterLast(" · ")
            .takeIf { it.isNotBlank() && !it.equals(control.serviceName, ignoreCase = true) }
        ?: "Параметр"
}

private fun formattedValue(control: SprutControl): String {
    control.valueOptions.firstOrNull { option -> option.value.matches(control.value) }
        ?.let { option ->
            option.name.takeIf(String::isNotBlank)
                ?: option.key.takeIf(String::isNotBlank)
        }
        ?.let { return localizedValueToken(it) }
    val type = normalizeType(control.characteristicType)
    val numeric = control.value.numberValue
    if (numeric != null) {
        val mapped = when (type) {
            "currentheatercoolerstate" -> mapOf(0L to "Выключен", 1L to "Ожидание", 2L to "Нагрев", 3L to "Охлаждение")[numeric.roundToLong()]
            "currentheatingcoolingstate" -> mapOf(0L to "Выключен", 1L to "Нагрев", 2L to "Охлаждение")[numeric.roundToLong()]
            "targetheatercoolerstate" -> mapOf(0L to "Авто", 1L to "Нагрев", 2L to "Охлаждение")[numeric.roundToLong()]
            "targetheatingcoolingstate" -> mapOf(0L to "Выключен", 1L to "Нагрев", 2L to "Охлаждение", 3L to "Авто")[numeric.roundToLong()]
            "currentfanstate" -> mapOf(0L to "Выключен", 1L to "Ожидание", 2L to "Работает")[numeric.roundToLong()]
            "positionstate" -> mapOf(0L to "Опускается", 1L to "Поднимается", 2L to "Остановлено")[numeric.roundToLong()]
            else -> null
        }
        return mapped ?: formatNumber(numeric, control.unit)
    }
    control.value.stringValue?.let { value ->
        return localizedValueToken(value)
    }
    control.value.boolValue?.let { return if (it) "Да" else "Нет" }
    return "—"
}

private fun SprutValue.matches(other: SprutValue): Boolean = when {
    boolValue != null && other.boolValue != null -> boolValue == other.boolValue
    numberValue != null && other.numberValue != null -> abs(numberValue - other.numberValue) < 0.000_001
    stringValue != null && other.stringValue != null -> stringValue.equals(other.stringValue, ignoreCase = true)
    else -> false
}

private fun localizedValueToken(value: String): String = when (value.uppercase(Locale.ROOT)) {
    "AUTO" -> "Авто"
    "QUIET" -> "Тихо"
    "LOW" -> "Низкая"
    "MEDIUM" -> "Средняя"
    "HIGH" -> "Высокая"
    "TURBO" -> "Турбо"
    "HEAT", "HEATING" -> "Нагрев"
    "COOL", "COOLING" -> "Охлаждение"
    "DRY" -> "Осушение"
    "FAN", "FAN_ONLY" -> "Вентиляция"
    "OFF", "INACTIVE" -> "Выключено"
    "ON", "ACTIVE" -> "Включено"
    else -> value
}

private fun formatNumber(value: Double, unit: String): String {
    val number = if (value % 1.0 == 0.0) value.roundToLong().toString() else {
        String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
    }
    val readableUnit = when (unit.lowercase()) {
        "celsius", "@unit_celsius" -> "°C"
        "percentage", "@unit_percent" -> "%"
        "arcdegrees", "@unit_degree" -> "°"
        "seconds", "@unit_sec" -> "с"
        else -> unit
    }
    return if (readableUnit.isBlank()) number else "$number $readableUnit"
}

private fun normalizeType(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("^(hs|hc)[.:_]"), "")
    .replace(Regex("^[sc][.:_]"), "")
    .filter(Char::isLetterOrDigit)

private fun readableType(value: String): String? = value
    .takeIf(String::isNotBlank)
    ?.replace(Regex("^(HS|HC)[.:_]", RegexOption.IGNORE_CASE), "")
    ?.replace(Regex("^[SC]_", RegexOption.IGNORE_CASE), "")
    ?.replace('_', ' ')
    ?.replace('-', ' ')
    ?.replace(Regex("(?<=[a-zа-я0-9])(?=[A-ZА-Я])"), " ")
    ?.trim()
    ?.replaceFirstChar { it.uppercase() }
