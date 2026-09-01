package io.github.nikitau.spruthubhelper.data

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Stable visual contract for the app-owned panel. It intentionally describes
 * a service role rather than a vendor model: names are user-editable, while
 * SprutHub service type, control behavior and linked topology are structural.
 */
enum class ServiceCardTemplate {
    LIGHT,
    SWITCH,
    OUTLET,
    FAN,
    COVER,
    LOCK,
    CLIMATE,
    SECURITY,
    VACUUM,
    MEDIA,
    SCENE,
    SENSOR,
    RANGE,
    GENERIC,
}

/** One human-readable characteristic exposed by a logical service card. */
data class CharacteristicDisplayValue(
    val key: String,
    val label: String,
    val value: String,
)

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
    val template: ServiceCardTemplate
        get() = when (kind) {
            DeviceKind.LIGHT -> ServiceCardTemplate.LIGHT
            DeviceKind.SWITCH -> ServiceCardTemplate.SWITCH
            DeviceKind.OUTLET -> ServiceCardTemplate.OUTLET
            DeviceKind.FAN -> ServiceCardTemplate.FAN
            DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER, DeviceKind.GARAGE ->
                ServiceCardTemplate.COVER
            DeviceKind.LOCK -> ServiceCardTemplate.LOCK
            DeviceKind.THERMOSTAT -> ServiceCardTemplate.CLIMATE
            DeviceKind.SECURITY -> ServiceCardTemplate.SECURITY
            DeviceKind.VACUUM -> ServiceCardTemplate.VACUUM
            DeviceKind.TELEVISION -> ServiceCardTemplate.MEDIA
            DeviceKind.SCENE -> ServiceCardTemplate.SCENE
            DeviceKind.SENSOR -> ServiceCardTemplate.SENSOR
            DeviceKind.VALVE -> if (primaryControl.behavior == ControlBehavior.RANGE) {
                ServiceCardTemplate.RANGE
            } else {
                ServiceCardTemplate.SWITCH
            }
            DeviceKind.OTHER -> when (primaryControl.behavior) {
                ControlBehavior.RANGE, ControlBehavior.TOGGLE_RANGE -> ServiceCardTemplate.RANGE
                ControlBehavior.BUTTON -> ServiceCardTemplate.SCENE
                ControlBehavior.OPTIONS -> ServiceCardTemplate.GENERIC
                ControlBehavior.SENSOR -> ServiceCardTemplate.SENSOR
                ControlBehavior.TOGGLE -> ServiceCardTemplate.GENERIC
            }
        }

    val recommendedAttributeCount: Int
        get() = when (template) {
            ServiceCardTemplate.SCENE -> 0
            ServiceCardTemplate.LIGHT,
            ServiceCardTemplate.SWITCH,
            ServiceCardTemplate.FAN,
            ServiceCardTemplate.VACUUM,
            ServiceCardTemplate.MEDIA,
            ServiceCardTemplate.RANGE,
            ServiceCardTemplate.GENERIC -> 1
            ServiceCardTemplate.OUTLET,
            ServiceCardTemplate.COVER,
            ServiceCardTemplate.LOCK,
            ServiceCardTemplate.CLIMATE,
            ServiceCardTemplate.SECURITY,
            ServiceCardTemplate.SENSOR -> 2
        }

    val isActive: Boolean?
        get() = when (primaryControl.behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE -> primaryControl.value.asBooleanOrNull()
            else -> null
        }

    val supportsRange: Boolean
        get() = primaryControl.behavior == ControlBehavior.RANGE ||
            primaryControl.behavior == ControlBehavior.TOGGLE_RANGE

    fun headlineValue(): String = when (primaryControl.behavior) {
        ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE -> semanticToggleValue(
            primaryControl.value.asBooleanOrNull(),
        )
        ControlBehavior.RANGE, ControlBehavior.OPTIONS, ControlBehavior.SENSOR -> formattedValue(primaryControl)
        ControlBehavior.BUTTON -> "Готово к запуску"
    }

    fun displayServiceName(): String {
        val explicit = serviceName.trim()
        if (explicit.isNotBlank() && !explicit.equals(title, ignoreCase = true)) {
            localizedServiceLabel(explicit)?.let { return it }
            if (normalizeType(explicit) != normalizeType(serviceType)) return explicit
            readableType(explicit)?.let { return it }
        }
        return localizedServiceLabel(serviceType) ?: template.categoryLabel()
    }

    fun availableAttributes(): List<SprutControl> = controls
        .asSequence()
        .filterNot { it.id == primaryControl.id }
        .filter { it.behavior == ControlBehavior.SENSOR }
        // Characteristic ids are only unique inside one service. Linked sensor
        // services commonly reuse ids such as `1`, so use the full control id.
        .distinctBy(SprutControl::id)
        .sortedWith(
            compareByDescending<SprutControl>(::attributePriority)
                .thenBy { attributeLabel(it).lowercase() },
        )
        .toList()

    fun optionControls(): List<SprutControl> = controls
        .asSequence()
        .filter { it.behavior == ControlBehavior.OPTIONS && it.writable && it.valueOptions.size > 1 }
        .distinctBy(SprutControl::id)
        .sortedBy { attributeLabel(it).lowercase() }
        .toList()

    /**
     * Full, lossless value list for the in-app catalog disclosure. Compact
     * Android surfaces may select only a couple of these rows, but the app
     * must still let the user inspect every characteristic we parsed.
     */
    fun characteristicValues(): List<CharacteristicDisplayValue> = buildList {
        val orderedControls = listOf(primaryControl) + controls.filterNot { it.id == primaryControl.id }
        orderedControls.distinctBy(SprutControl::id).forEach { control ->
            if (control.behavior != ControlBehavior.BUTTON) {
                add(
                    CharacteristicDisplayValue(
                        key = control.id,
                        label = attributeLabel(control),
                        value = if (control.id == primaryControl.id) {
                            headlineValue()
                        } else {
                            attributeValue(control)
                        },
                    ),
                )
            }
            if (
                control.id == primaryControl.id &&
                control.behavior == ControlBehavior.TOGGLE_RANGE &&
                !control.rangeCharacteristicId.isNullOrBlank()
            ) {
                add(
                    CharacteristicDisplayValue(
                        key = "${control.id}:range:${control.rangeCharacteristicId}",
                        label = rangeLabel(),
                        value = rangeValue(),
                    ),
                )
            }
        }
    }.distinctBy(CharacteristicDisplayValue::key)

    fun defaultAttributes(limit: Int = recommendedAttributeCount): List<SprutControl> =
        availableAttributes().take(limit)

    fun selectedAttributes(item: PanelItem, limit: Int = recommendedAttributeCount): List<SprutControl> {
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

    fun optionLabel(option: SprutValueOption): String = localizedValueToken(
        option.name.takeIf(String::isNotBlank)
            ?: option.key.takeIf(String::isNotBlank)
            ?: option.value.stringValue
            ?: option.value.numberValue?.let { formatNumber(it, "") }
            ?: "Вариант",
    )

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
            else -> when (template) {
                ServiceCardTemplate.LIGHT -> "Яркость"
                ServiceCardTemplate.FAN -> "Скорость"
                ServiceCardTemplate.COVER -> "Положение"
                ServiceCardTemplate.CLIMATE -> "Задано"
                ServiceCardTemplate.MEDIA -> "Громкость"
                else -> "Настроить"
            }
        }
    }

    fun rangeValue(value: Double? = primaryControl.value.numberValue): String =
        value?.let { formatNumber(it, primaryControl.unit) } ?: "—"

    fun containsService(candidateServiceId: String): Boolean = candidateServiceId in memberServiceIds

    companion object {
        const val DEFAULT_ATTRIBUTE_COUNT = 2
    }

    private fun semanticToggleValue(active: Boolean?): String = when (active) {
        null -> "Нет данных"
        else -> when (kind) {
            DeviceKind.LOCK -> if (active) "Закрыт" else "Открыт"
            DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER ->
                if (active) "Закрыты" else "Открыты"
            DeviceKind.GARAGE -> if (active) "Закрыты" else "Открыты"
            DeviceKind.VALVE -> if (active) "Открыт" else "Закрыт"
            DeviceKind.SECURITY -> if (active) "Под охраной" else "Снято с охраны"
            DeviceKind.VACUUM -> if (active) "Убирает" else "Остановлен"
            else -> if (active) "Включено" else "Выключено"
        }
    }
}

fun ServiceCardTemplate.categoryLabel(): String = when (this) {
    ServiceCardTemplate.LIGHT -> "Свет"
    ServiceCardTemplate.SWITCH -> "Выключатель"
    ServiceCardTemplate.OUTLET -> "Розетка"
    ServiceCardTemplate.FAN -> "Вентилятор"
    ServiceCardTemplate.COVER -> "Шторы и приводы"
    ServiceCardTemplate.LOCK -> "Замок"
    ServiceCardTemplate.CLIMATE -> "Климат"
    ServiceCardTemplate.SECURITY -> "Охрана"
    ServiceCardTemplate.VACUUM -> "Уборка"
    ServiceCardTemplate.MEDIA -> "Медиа"
    ServiceCardTemplate.SCENE -> "Сценарий"
    ServiceCardTemplate.SENSOR -> "Датчик"
    ServiceCardTemplate.RANGE -> "Регулятор"
    ServiceCardTemplate.GENERIC -> "Устройство"
}

/** Shared semantic value used by widgets, tiles and accessibility text. */
fun SprutControl.surfaceValue(): String = buildServiceControlCards(listOf(this))
    .single()
    .headlineValue()

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

    fun serviceLabel(card: ServiceControlCard): String {
        val explicitName = card.serviceName
            .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        if (explicitName != null) {
            return localizedServiceLabel(explicitName)
                ?: readableType(explicitName)
                ?: explicitName
        }
        return localizedServiceLabel(card.serviceType)
            ?: readableType(card.serviceType)
            ?: if (serviceCards.size == 1) title else "Сервис ${serviceCards.indexOf(card) + 1}"
    }

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
    ControlBehavior.OPTIONS -> 850
    ControlBehavior.BUTTON -> 800
    ControlBehavior.RANGE -> 700
    ControlBehavior.SENSOR -> 100 + attributePriority(control)
}

private fun attributePriority(control: SprutControl): Int {
    val type = normalizeType(control.characteristicType)
    return when {
        control.kind == DeviceKind.LOCK && type in setOf("lockcurrentstate", "locktargetstate") -> 680
        control.kind == DeviceKind.SECURITY && type in setOf(
            "securitysystemcurrentstate",
            "securitysystemtargetstate",
        ) -> 680
        control.kind in setOf(
            DeviceKind.CURTAIN,
            DeviceKind.BLINDS,
            DeviceKind.SHUTTER,
            DeviceKind.GARAGE,
        ) && type in setOf("currentdoorstate", "targetdoorstate", "currentposition", "positionstate") -> 660
        control.kind == DeviceKind.OUTLET && type in setOf("outletinuse", "inuse") -> 640
        control.kind == DeviceKind.OUTLET && listOf(
            "watt",
            "power",
            "ampere",
            "current",
            "volt",
            "consumption",
        ).any(type::contains) -> 620
        type == "currenttemperature" -> 600
        type in setOf("currentheatercoolerstate", "currentheatingcoolingstate", "currentoperationalstate") -> 560
        type in setOf("targetheatercoolerstate", "targetheatingcoolingstate", "targetoperationalstate") -> 540
        type == "currentrelativehumidity" -> 520
        type in setOf("fanspeed", "rotationspeed", "currentfanstate") -> 500
        type in setOf("currentposition", "positionstate", "currentdoorstate", "targetdoorstate") -> 480
        type in setOf("outletinuse", "inuse") -> 460
        type in setOf(
            "contactsensorstate",
            "motiondetected",
            "occupancydetected",
            "leakdetected",
            "smokedetected",
            "carbondioxidedetected",
            "carbonmonoxidedetected",
            "gasdetected",
            "noisedetected",
        ) -> 450
        type == "airquality" -> 440
        type in setOf("statusfault", "statusjammed", "operationalerror", "obstructiondetected") -> 420
        type in setOf("online", "statusactive") -> 400
        type in setOf("batterylevel", "statuslowbattery", "chargingstate") -> 380
        else -> if (!control.writable) 200 else 100
    }
}

private fun characteristicLabel(control: SprutControl): String {
    if (control.characteristicType.contains("PM1_0", ignoreCase = true)) return "PM1.0"
    return when (normalizeType(control.characteristicType)) {
        "active", "on" -> "Питание"
        "brightness" -> "Яркость"
        "currenttemperature" -> "Сейчас"
        "targettemperature", "coolingthresholdtemperature", "heatingthresholdtemperature" -> "Задано"
        "currentheatercoolerstate", "currentheatingcoolingstate", "currentoperationalstate" -> "Режим"
        "targetheatercoolerstate", "targetheatingcoolingstate", "targetoperationalstate" -> "Заданный режим"
        "currentrelativehumidity" -> "Влажность"
        "fanspeed", "rotationspeed", "currentfanstate" -> "Вентилятор"
        "currentposition" -> "Положение"
        "positionstate" -> "Движение"
        "currentdoorstate" -> "Состояние"
        "targetdoorstate" -> "Команда"
        "lockcurrentstate" -> "Состояние"
        "locktargetstate" -> "Команда"
        "securitysystemcurrentstate" -> "Охрана"
        "securitysystemtargetstate" -> "Заданный режим"
        "outletinuse", "inuse" -> "Нагрузка"
        "contactsensorstate" -> "Контакт"
        "motiondetected" -> "Движение"
        "occupancydetected" -> "Присутствие"
        "leakdetected" -> "Протечка"
        "smokedetected" -> "Дым"
        "carbondioxidedetected" -> "CO₂ обнаружен"
        "carbondioxidelevel", "carbondioxidepeaklevel" -> "CO₂"
        "carbonmonoxidedetected" -> "CO"
        "gasdetected" -> "Газ"
        "noisedetected" -> "Шум"
        "airquality" -> "Качество воздуха"
        "pm25density" -> "PM2.5"
        "pm10density" -> "PM10"
        "currentatmosphericpressure" -> "Атмосферное давление"
        "currentambientlightlevel" -> "Освещённость"
        "currentnoiselevel" -> "Уровень шума"
        "airparticulatedensity" -> "Частицы в воздухе"
        "formaldehydedensity" -> "Формальдегид"
        "aqidensity" -> "AQI"
        "vocdensity" -> "Летучие соединения"
        "nitrogendioxidedensity" -> "Диоксид азота"
        "ozonedensity" -> "Озон"
        "sulphurdioxidedensity" -> "Диоксид серы"
        "carbonmonoxidelevel" -> "CO"
        "carbonmonoxidepeaklevel" -> "Пиковый CO"
        "volt" -> "Напряжение"
        "ampere" -> "Ток"
        "watt" -> "Мощность"
        "voltampere" -> "Полная мощность"
        "kilowatthour" -> "Энергия"
        "kilovoltamperehour" -> "Полная энергия"
        "voltamperereactive" -> "Реактивная мощность"
        "kilovoltamperereactivehour" -> "Реактивная энергия"
        "powerfactor" -> "Коэффициент мощности"
        "frequency" -> "Частота"
        "currentultraviolet" -> "УФ-индекс"
        "pulsecount" -> "Импульсы"
        "cubicmeter" -> "Объём"
        "distance" -> "Расстояние"
        "currentmotionlevel", "intensity" -> "Интенсивность"
        "currenttiltangle", "tiltangle", "angle" -> "Угол"
        "filterlifelevel" -> "Ресурс фильтра"
        "filterchangeindication" -> "Замена фильтра"
        "waterlevel" -> "Уровень воды"
        "statusfault", "operationalerror" -> "Ошибка"
        "statusjammed" -> "Заклинивание"
        "obstructiondetected" -> "Препятствие"
        "online", "statusactive" -> "Связь"
        "batterylevel" -> "Батарея"
        "statuslowbattery" -> "Низкий заряд"
        "chargingstate" -> "Зарядка"
        else -> control.characteristicName
            .takeIf(String::isNotBlank)
            ?: readableType(control.characteristicType)
            ?: control.subtitle.substringAfterLast(" · ")
                .takeIf { it.isNotBlank() && !it.equals(control.serviceName, ignoreCase = true) }
            ?: "Параметр"
    }
}

private fun formattedValue(control: SprutControl): String {
    control.valueOptions.firstOrNull { option -> option.value.sameValueAs(control.value) }
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
            "currentdoorstate" -> mapOf(
                0L to "Открыто",
                1L to "Закрыто",
                2L to "Открывается",
                3L to "Закрывается",
                4L to "Остановлено",
            )[numeric.roundToLong()]
            "targetdoorstate" -> mapOf(0L to "Открыть", 1L to "Закрыть")[numeric.roundToLong()]
            "lockcurrentstate" -> mapOf(
                0L to "Открыт",
                1L to "Закрыт",
                2L to "Заклинил",
                3L to "Неизвестно",
            )[numeric.roundToLong()]
            "locktargetstate" -> mapOf(0L to "Открыть", 1L to "Закрыть")[numeric.roundToLong()]
            "securitysystemcurrentstate" -> mapOf(
                0L to "Дома",
                1L to "Вне дома",
                2L to "Ночь",
                3L to "Снято с охраны",
                4L to "Тревога",
            )[numeric.roundToLong()]
            "securitysystemtargetstate" -> mapOf(
                0L to "Дома",
                1L to "Вне дома",
                2L to "Ночь",
                3L to "Снять с охраны",
            )[numeric.roundToLong()]
            "contactsensorstate" -> mapOf(0L to "Замкнут", 1L to "Разомкнут")[numeric.roundToLong()]
            "occupancydetected" -> mapOf(0L to "Нет", 1L to "Есть")[numeric.roundToLong()]
            "leakdetected" -> mapOf(0L to "Нет", 1L to "Обнаружена")[numeric.roundToLong()]
            "smokedetected" -> mapOf(0L to "Нет", 1L to "Обнаружен")[numeric.roundToLong()]
            "carbondioxidedetected", "carbonmonoxidedetected", "gasdetected" ->
                mapOf(0L to "Нет", 1L to "Обнаружено")[numeric.roundToLong()]
            "airquality" -> mapOf(
                0L to "Неизвестно",
                1L to "Отличное",
                2L to "Хорошее",
                3L to "Среднее",
                4L to "Плохое",
                5L to "Очень плохое",
            )[numeric.roundToLong()]
            "chargingstate" -> mapOf(
                0L to "Не заряжается",
                1L to "Заряжается",
                2L to "Недоступно",
            )[numeric.roundToLong()]
            "statusfault" -> mapOf(0L to "Нет", 1L to "Есть")[numeric.roundToLong()]
            "statuslowbattery" -> mapOf(0L to "Норма", 1L to "Низкий")[numeric.roundToLong()]
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
    val readableUnit = readableSprutUnit(unit)
    return if (readableUnit.isBlank()) number else "$number $readableUnit"
}

private fun normalizeType(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("^(hs|hc)[.:_]"), "")
    .replace(Regex("^[sc][.:_]"), "")
    .filter(Char::isLetterOrDigit)

private fun localizedServiceLabel(value: String): String? = when (normalizeType(value)) {
    "light", "lightbulb", "lighting" -> "Свет"
    "fan", "fanv2", "fanbasic" -> "Вентилятор"
    "airpurifier" -> "Очиститель воздуха"
    "switch" -> "Выключатель"
    "outlet" -> "Розетка"
    "thermostat", "heatercooler", "humidifierdehumidifier", "temperaturecontrol",
    "airconditioner", "airconditioning" -> "Климат"
    "battery", "batteryservice" -> "Батарея"
    "temperature", "temperaturesensor" -> "Температура"
    "humidity", "humiditysensor" -> "Влажность"
    "airquality", "airqualitysensor" -> "Качество воздуха"
    "contactsensor" -> "Контакт"
    "motionsensor" -> "Движение"
    "occupancysensor" -> "Присутствие"
    "lightsensor" -> "Освещённость"
    "leaksensor" -> "Протечка"
    "smokesensor" -> "Дым"
    "co2", "carbondioxide", "carbondioxidesensor" -> "CO₂"
    "carbonmonoxidesensor" -> "CO"
    "gassensor" -> "Газ"
    "noisesensor" -> "Шум"
    "lock", "lockmechanism" -> "Замок"
    "windowcovering", "blinds", "curtain", "slat" -> "Шторы"
    "door", "window" -> "Привод"
    "garagedooropener" -> "Ворота"
    "valve", "faucet" -> "Клапан"
    "irrigationsystem" -> "Полив"
    "securitysystem" -> "Охрана"
    "vacuumcleaner" -> "Пылесос"
    "television", "televisionspeaker", "speaker", "microphone", "inputsource" -> "Медиа"
    "petfeeder" -> "Кормушка"
    "button", "statelessprogrammableswitch" -> "Кнопка"
    else -> null
}

private fun readableType(value: String): String? = value
    .takeIf(String::isNotBlank)
    ?.replace(Regex("^(HS|HC)[.:_]", RegexOption.IGNORE_CASE), "")
    ?.replace(Regex("^[SC]_", RegexOption.IGNORE_CASE), "")
    ?.replace('_', ' ')
    ?.replace('-', ' ')
    ?.replace(Regex("(?<=[a-zа-я0-9])(?=[A-ZА-Я])"), " ")
    ?.trim()
    ?.replaceFirstChar { it.uppercase() }
