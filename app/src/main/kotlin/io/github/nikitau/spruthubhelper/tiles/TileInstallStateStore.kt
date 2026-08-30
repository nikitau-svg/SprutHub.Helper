package io.github.nikitau.spruthubhelper.tiles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks callbacks from System UI separately from in-app tile assignments.
 *
 * An assignment only tells a TileService what it should control. Android still
 * needs to add that service to Quick Settings, which is a distinct user-owned
 * action. Keeping both states prevents the UI from claiming that a tile is in
 * the shade when it is only configured inside the app.
 */
object TileInstallStateStore {
    private const val PREFERENCES = "quick_settings_tiles"
    private const val INSTALLED_SLOTS = "installed_slots"

    private val mutableInstalledSlots = MutableStateFlow<Set<Int>>(emptySet())
    val installedSlots: StateFlow<Set<Int>> = mutableInstalledSlots.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        mutableInstalledSlots.value = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(INSTALLED_SLOTS, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .filter { it in 1..12 }
            .toSet()
        initialized = true
    }

    fun markAdded(context: Context, slot: Int) = update(context, slot, installed = true)

    fun markRemoved(context: Context, slot: Int) = update(context, slot, installed = false)

    private fun update(context: Context, slot: Int, installed: Boolean) {
        if (slot !in 1..12) return
        initialize(context)
        val next = if (installed) mutableInstalledSlots.value + slot else mutableInstalledSlots.value - slot
        if (next == mutableInstalledSlots.value) return
        mutableInstalledSlots.value = next
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(INSTALLED_SLOTS, next.map(Int::toString).toSet())
            .apply()
    }
}
