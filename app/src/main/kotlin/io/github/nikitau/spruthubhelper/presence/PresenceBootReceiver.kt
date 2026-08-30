package io.github.nikitau.spruthubhelper.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nikitau.spruthubhelper.AppGraph
import kotlinx.coroutines.launch

class PresenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        AppGraph.initialize(context.applicationContext)
        val pending = goAsync()
        AppGraph.applicationScope.launch {
            try {
                AppGraph.presence.refreshRegistrations()
                // Restore the user-enabled live phone monitor after reboot or
                // an in-place APK update. BOOT_COMPLETED and
                // MY_PACKAGE_REPLACED are permitted background-start cases;
                // the service itself still validates notification settings.
                AppGraph.phone.ensureLiveMonitorNow()
            } finally {
                pending.finish()
            }
        }
    }
}
