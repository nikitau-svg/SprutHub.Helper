package io.github.nikitau.spruthubhelper.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.SprutControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class SprutTileService(private val slot: Int) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = AppGraph.repository

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val refreshed = repository.refreshIfStale()
            val error = refreshed.exceptionOrNull()?.message.takeIf { assignedControl() == null }
            updateTile(error)
        }
    }

    override fun onClick() {
        super.onClick()
        val action = Runnable { scope.launch { performAssignedAction() } }
        if (isLocked) unlockAndRun(action) else action.run()
    }

    private suspend fun performAssignedAction() {
        repository.refreshIfStale(maxAgeMs = 10_000)
        val control = assignedControl()
        if (control == null) {
            updateTile("Обновите назначение")
            return
        }
        val result = when (control.behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE ->
                repository.setBoolean(control.id, !control.value.asBoolean())
            ControlBehavior.RANGE -> {
                val midpoint = (control.minimum + control.maximum) / 2.0
                repository.setRange(control.id, if (control.value.asDouble() > midpoint) control.minimum else control.maximum)
            }
            ControlBehavior.BUTTON -> repository.execute(control.id)
            ControlBehavior.SENSOR -> Result.failure(IllegalStateException("Только чтение"))
        }
        result.onFailure { error -> Log.e(LOG_TAG, "Tile $slot action failed", error) }
        updateTile(result.exceptionOrNull()?.message)
    }

    private fun updateTile(error: String? = null) {
        val tile = qsTile ?: return
        val control = assignedControl()
        if (control == null) {
            tile.label = "SprutHub $slot"
            tile.subtitle = error?.take(30) ?: "Не настроено"
            tile.state = Tile.STATE_UNAVAILABLE
            tile.icon = TileIconResolver.icon(this, io.github.nikitau.spruthubhelper.data.DeviceKind.OTHER)
        } else {
            tile.label = control.title
            tile.subtitle = error?.take(30) ?: control.room
            tile.icon = TileIconResolver.icon(this, control.kind)
            tile.state = if (error != null) {
                Tile.STATE_UNAVAILABLE
            } else {
                when (control.behavior) {
                    ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE ->
                        if (control.value.asBoolean()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    ControlBehavior.BUTTON, ControlBehavior.RANGE -> Tile.STATE_INACTIVE
                    ControlBehavior.SENSOR -> Tile.STATE_UNAVAILABLE
                }
            }
            tile.contentDescription = "${control.title}, ${control.displayValue}"
        }
        tile.updateTile()
    }

    private fun assignedControl(): SprutControl? {
        val controlId = repository.tileAssignments.value.firstOrNull { it.slot == slot }?.controlId ?: return null
        return repository.catalog.value.controls.firstOrNull { it.id == controlId }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "SprutHubTile"
    }
}

class Tile1Service : SprutTileService(1)
class Tile2Service : SprutTileService(2)
class Tile3Service : SprutTileService(3)
class Tile4Service : SprutTileService(4)
class Tile5Service : SprutTileService(5)
class Tile6Service : SprutTileService(6)
class Tile7Service : SprutTileService(7)
class Tile8Service : SprutTileService(8)
class Tile9Service : SprutTileService(9)
class Tile10Service : SprutTileService(10)
class Tile11Service : SprutTileService(11)
class Tile12Service : SprutTileService(12)
