package io.github.nikitau.spruthubhelper.data

import java.util.Locale
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
            val state = when (primaryControl.value.asBooleanOrNull()) {
                true -> "Включено"
                false -> "Выключено"
                null -> "Нет данных"
            }
            val target = primaryControl.value.numberValue?.let {
                formatNumber(it, primaryControl.unit)
            }
            listOfNotNull(state, target).joinToString(" · ")
        }
        ControlBehavior.RANGE, ControlBehavior.SENSOR -> formattedValue(primaryControl)
        ControlBehavior.BUTTON -> "Готово к запуску"
    }

    fun availableAttributes(): List<SprutControl> = controls
        .asSequence()
        .filterNot { it.id == primaryControl.id }
        .distinctBy { it.characteristicId }
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

    fun attributeLabel(control: SprutControl): String = characteristicLabel(control)

    fun attributeValue(control: SprutControl): String = formattedValue(control)

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
            isPrimaryService = grouped.any(SprutControl::servicePrimary),
        )
    }
    .sortedWith(
        compareBy<ServiceControlCard>({ it.room.lowercase() }, { it.title.lowercase() })
            .thenByDescending(ServiceControlCard::isPrimaryService)
            .thenBy { it.serviceName.lowercase() }
            .thenBy(ServiceControlCard::id),
    )

/**
 * SprutHub accessories may expose several independently controllable services.
 * Keep the accessory as a settings section and every service as one card.
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
        ?: control.subtitle.substringAfterLast(" · ").takeIf(String::isNotBlank)
        ?: readableType(control.characteristicType)
        ?: "Параметр"
}

private fun formattedValue(control: SprutControl): String {
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
        return when (value.uppercase(Locale.ROOT)) {
            "AUTO" -> "Авто"
            "QUIET" -> "Тихо"
            "LOW" -> "Низкая"
            "MEDIUM" -> "Средняя"
            "HIGH" -> "Высокая"
            "TURBO" -> "Турбо"
            "HEAT", "HEATING" -> "Нагрев"
            "COOL", "COOLING" -> "Охлаждение"
            "DRY" -> "Осушение"
            "FAN" -> "Вентиляция"
            "OFF", "INACTIVE" -> "Выключено"
            "ON", "ACTIVE" -> "Включено"
            else -> value
        }
    }
    control.value.boolValue?.let { return if (it) "Да" else "Нет" }
    return "—"
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
