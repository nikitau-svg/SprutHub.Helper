package io.github.nikitau.spruthubhelper.tiles

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.data.TileAssignment

object TileComponents {
    private val classes = listOf(
        Tile1Service::class.java,
        Tile2Service::class.java,
        Tile3Service::class.java,
        Tile4Service::class.java,
        Tile5Service::class.java,
        Tile6Service::class.java,
        Tile7Service::class.java,
        Tile8Service::class.java,
        Tile9Service::class.java,
        Tile10Service::class.java,
        Tile11Service::class.java,
        Tile12Service::class.java,
    )

    fun component(context: Context, slot: Int): ComponentName = ComponentName(
        context,
        classes.getOrElse(slot - 1) { error("Плитка $slot не существует") },
    )

    fun syncEnabled(context: Context, assignments: List<TileAssignment>) {
        val highestAssigned = assignments.maxOfOrNull(TileAssignment::slot) ?: 0
        val visibleSlots = maxOf(4, highestAssigned + 4).coerceAtMost(SettingsRepository.MAX_TILE_SLOTS)
        classes.forEachIndexed { index, serviceClass ->
            val enabled = index < visibleSlots
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, serviceClass),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
