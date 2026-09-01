package io.github.nikitau.spruthubhelper.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.initialize(context.applicationContext)
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.SYNC,
                event = "Событие геозоны Android",
                outcome = DiagnosticOutcome.FAILED,
                reason = "Android сообщил код ошибки геозоны ${event.errorCode}",
            )
            AppGraph.presence.reportError("Android сообщил ошибку геозоны: ${event.errorCode}")
            return
        }
        val entered = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val ids = event.triggeringGeofences.orEmpty().mapTo(mutableSetOf(), Geofence::getRequestId)
        if (ids.isEmpty()) return
        val pending = goAsync()
        AppGraph.applicationScope.launch {
            try {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = "Событие геозоны Android",
                    outcome = DiagnosticOutcome.STARTED,
                    details = mapOf("переход" to if (entered) "вход" else "выход"),
                )
                AppGraph.presence.handleTransition(ids, entered, event.triggeringLocation)
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = "Событие геозоны Android",
                    outcome = DiagnosticOutcome.SUCCESS,
                )
            } catch (error: Exception) {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = "Событие геозоны Android",
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message ?: "неизвестная ошибка",
                )
                AppGraph.presence.reportError(error.message ?: "Не удалось обработать событие геозоны")
            } finally {
                pending.finish()
            }
        }
    }
}
