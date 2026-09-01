package io.github.nikitau.spruthubhelper.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

abstract class SprutTileService(private val slot: Int) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = AppGraph.repository
    private var listeningJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        TileInstallStateStore.markAdded(this, slot)
    }

    override fun onTileRemoved() {
        TileInstallStateStore.markRemoved(this, slot)
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        // Also migrates tiles that were already present before install-state
        // tracking was introduced.
        TileInstallStateStore.markAdded(this, slot)
        listeningJob?.cancel()
        listeningJob = scope.launch {
            combine(
                repository.catalog,
                repository.connectionStatus,
                repository.pendingControlIds,
            ) { _, _, _ -> Unit }.collect {
                updateTile()
            }
        }
        // Keep the check alive even if SystemUI closes the shade and stops
        // listening before SprutHub has answered.
        AppGraph.applicationScope.launch { repository.refreshIfStale() }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val action = Runnable { AppGraph.applicationScope.launch { performAssignedAction() } }
        if (isLocked) unlockAndRun(action) else action.run()
    }

    private suspend fun performAssignedAction() {
        val event = "Команда плитки быстрых настроек"
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.COMMAND,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("слот" to slot.toString()),
        )
        val control = assignedControl()
        if (control == null) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.COMMAND,
                event = event,
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "Плитке не назначено устройство или назначение устарело",
                details = mapOf("слот" to slot.toString()),
            )
            updateTile("Обновите назначение")
            return
        }
        val result = when (control.behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE ->
                repository.toggleBoolean(control.id)
            ControlBehavior.RANGE -> {
                if (control.kind in COVERING_KINDS) {
                    repository.toggleRangeEndpoint(control.id)
                } else {
                    AppGraph.diagnostics.record(
                        category = DiagnosticCategory.COMMAND,
                        event = event,
                        outcome = DiagnosticOutcome.SKIPPED,
                        reason = "Для регулировки требуется открыть приложение",
                        details = mapOf("слот" to slot.toString()),
                    )
                    openControl(control)
                    updateTile()
                    return
                }
            }
            ControlBehavior.OPTIONS -> {
                openControl(control)
                updateTile()
                return
            }
            ControlBehavior.BUTTON -> repository.execute(control.id)
            ControlBehavior.SENSOR -> Result.failure(IllegalStateException("Только чтение"))
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.COMMAND,
            event = event,
            outcome = if (result.isSuccess) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILED,
            reason = result.exceptionOrNull()?.message,
            details = mapOf("слот" to slot.toString()),
        )
        result.onFailure { error -> Log.e(LOG_TAG, "Tile $slot action failed", error) }
        updateTile(result.exceptionOrNull()?.message)
    }

    private fun updateTile(error: String? = null) {
        val tile = qsTile ?: return
        val control = assignedControl()
        val freshness = repository.freshness()
        if (control == null) {
            tile.label = "SprutHub $slot"
            tile.subtitle = error?.take(30) ?: "Не настроено"
            tile.state = Tile.STATE_UNAVAILABLE
            tile.icon = TileIconResolver.icon(this, DeviceKind.OTHER)
        } else {
            tile.label = control.title
            val presentation = freshness.presentationFor(control)
            val unavailableReason = when {
                error != null -> error.take(30)
                presentation.pending -> presentation.statusLabel
                !presentation.stateIsAuthoritative -> presentation.statusLabel
                else -> null
            }
            tile.subtitle = unavailableReason
                ?: presentation.statusLabel
                ?: control.subtitle.ifBlank { control.room }
            tile.icon = CustomIconManager(this).loadIcon(control.id)
                ?: TileIconResolver.icon(this, control.kind)
            tile.state = if (unavailableReason != null) {
                Tile.STATE_UNAVAILABLE
            } else {
                when (control.behavior) {
                    ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE ->
                        if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    ControlBehavior.OPTIONS, ControlBehavior.BUTTON, ControlBehavior.RANGE -> Tile.STATE_INACTIVE
                    ControlBehavior.SENSOR -> Tile.STATE_UNAVAILABLE
                }
            }
            tile.contentDescription = listOf(
                control.title,
                unavailableReason ?: presentation.statusLabel,
                control.displayValue,
            )
                .filterNotNull()
                .joinToString(", ")
        }
        tile.updateTile()
    }

    private fun assignedControl(): SprutControl? {
        val controlId = repository.tileAssignments.value.firstOrNull { it.slot == slot }?.controlId ?: return null
        return repository.catalog.value.controls.firstOrNull { it.id == controlId }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openControl(control: SprutControl) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONTROL_ID, control.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                control.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val LOG_TAG = "SprutHubTile"
        val COVERING_KINDS = setOf(DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER, DeviceKind.GARAGE)
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
