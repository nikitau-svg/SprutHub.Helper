package io.github.nikitau.spruthubhelper.tiles

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
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

    fun enableSlot(context: Context, slot: Int) {
        context.packageManager.setComponentEnabledSetting(
            component(context, slot),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun syncEnabled(context: Context, assignments: List<TileAssignment>) {
        val assignedSlots = assignedTileSlots(assignments)
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                classes.mapIndexed { index, serviceClass ->
                    PackageManager.ComponentEnabledSetting(
                        ComponentName(context, serviceClass),
                        if (index + 1 in assignedSlots) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                },
            )
        } else {
            classes.forEachIndexed { index, serviceClass ->
                packageManager.setComponentEnabledSetting(
                    ComponentName(context, serviceClass),
                    if (index + 1 in assignedSlots) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        classes.indices.forEach { index ->
            val slot = index + 1
            if (slot !in assignedSlots) TileInstallStateStore.markRemoved(context, slot)
        }
        assignments.forEach { assignment ->
            runCatching {
                TileService.requestListeningState(context, component(context, assignment.slot))
            }
        }
    }
}

internal fun assignedTileSlots(assignments: List<TileAssignment>): Set<Int> = assignments
    .map(TileAssignment::slot)
    .filter { it in 1..12 }
    .toSet()
