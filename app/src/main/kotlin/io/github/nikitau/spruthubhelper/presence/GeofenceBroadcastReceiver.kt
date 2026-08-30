package io.github.nikitau.spruthubhelper.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.github.nikitau.spruthubhelper.AppGraph
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.initialize(context.applicationContext)
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
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
                AppGraph.presence.handleTransition(ids, entered, event.triggeringLocation)
            } finally {
                pending.finish()
            }
        }
    }
}
