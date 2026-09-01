package io.github.nikitau.spruthubhelper.widget

import android.content.Context

/** Synchronous storage used by the launcher while the app process is starting. */
object WidgetAssignmentStore {
    private const val PREFERENCES = "spruthub_home_screen_widgets"
    private const val KEY_PREFIX = "widget_"

    fun controlId(context: Context, appWidgetId: Int): String? = preferences(context)
        .getString(key(appWidgetId), null)
        ?.takeIf(String::isNotBlank)

    fun save(context: Context, appWidgetId: Int, controlId: String) {
        require(appWidgetId >= 0) { "Некорректный идентификатор виджета" }
        require(controlId.isNotBlank()) { "Устройство для виджета не выбрано" }
        preferences(context).edit().putString(key(appWidgetId), controlId).apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        preferences(context).edit().remove(key(appWidgetId)).apply()
    }

    fun restore(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val editor = preferences(context).edit()
        oldWidgetIds.zip(newWidgetIds).forEach { (oldId, newId) ->
            val controlId = controlId(context, oldId) ?: return@forEach
            editor.putString(key(newId), controlId)
            if (oldId != newId) editor.remove(key(oldId))
        }
        editor.apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun key(appWidgetId: Int): String = "$KEY_PREFIX$appWidgetId"
}
