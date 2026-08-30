package io.github.nikitau.spruthubhelper.data

/**
 * SprutHub accessories may expose several independently controllable services.
 * Keep every service selectable while presenting one accessory only once.
 */
data class AccessoryControlGroup(
    val key: String,
    val title: String,
    val room: String,
    val controls: List<SprutControl>,
) {
    fun serviceLabel(control: SprutControl): String {
        val index = controls.indexOfFirst { it.id == control.id }.coerceAtLeast(0)
        return control.subtitle
            .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            ?: control.sourceType.takeIf(String::isNotBlank)
            ?: if (controls.size == 1) title else "Канал ${index + 1}"
    }

    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return true
        return listOf(title, room).any { it.contains(needle, ignoreCase = true) } || controls.any { control ->
            listOf(control.title, control.subtitle, control.sourceType, control.displayValue)
                .any { it.contains(needle, ignoreCase = true) }
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
