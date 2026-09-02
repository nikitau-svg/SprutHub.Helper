package io.github.nikitau.spruthubhelper.widget

import android.content.Context

/** Synchronous storage used by the launcher while the app process is starting. */
object WidgetAssignmentStore {
    private const val PREFERENCES = "spruthub_home_screen_widgets"
    private const val KEY_PREFIX = "widget_"
    private const val LAYOUT_KEY_PREFIX = "widget_layout_"

    data class Assignment(
        val controlId: String,
        val layout: WidgetLayoutConfiguration?,
    )

    fun assignment(context: Context, appWidgetId: Int): Assignment? {
        val preferences = preferences(context)
        val controlId = preferences.getString(key(appWidgetId), null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val savedLayout = WidgetLayoutConfigurationCodec.decode(
            preferences.getString(layoutKey(appWidgetId), null),
        )
        // Widgets created by 0.7 stored only one control id. Treat them as a
        // one-item composition so an app update upgrades them in place; users
        // do not have to remove and add the widget again.
        val layout = savedLayout ?: WidgetLayoutConfiguration(
            items = listOf(WidgetItemConfiguration(controlId)),
        )
        return Assignment(
            controlId = controlId,
            layout = layout.normalized(fallbackPrimaryControlId = controlId),
        )
    }

    fun controlId(context: Context, appWidgetId: Int): String? = assignment(context, appWidgetId)?.controlId

    fun layout(context: Context, appWidgetId: Int): WidgetLayoutConfiguration? =
        assignment(context, appWidgetId)?.layout

    fun save(context: Context, appWidgetId: Int, controlId: String) {
        require(appWidgetId >= 0) { "Некорректный идентификатор виджета" }
        require(controlId.isNotBlank()) { "Устройство для виджета не выбрано" }
        preferences(context).edit()
            .putString(key(appWidgetId), controlId)
            .remove(layoutKey(appWidgetId))
            .apply()
    }

    fun save(
        context: Context,
        appWidgetId: Int,
        controlId: String,
        layout: WidgetLayoutConfiguration,
    ) {
        require(appWidgetId >= 0) { "Некорректный идентификатор виджета" }
        require(controlId.isNotBlank()) { "Устройство для виджета не выбрано" }
        preferences(context).edit()
            .putString(key(appWidgetId), controlId)
            .putString(
                layoutKey(appWidgetId),
                WidgetLayoutConfigurationCodec.encode(
                    layout.normalized(fallbackPrimaryControlId = controlId),
                ),
            )
            .apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        preferences(context).edit()
            .remove(key(appWidgetId))
            .remove(layoutKey(appWidgetId))
            .apply()
    }

    fun restore(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val editor = preferences(context).edit()
        oldWidgetIds.zip(newWidgetIds).forEach { (oldId, newId) ->
            val assignment = assignment(context, oldId) ?: return@forEach
            editor.putString(key(newId), assignment.controlId)
            assignment.layout?.let { layout ->
                editor.putString(layoutKey(newId), WidgetLayoutConfigurationCodec.encode(layout))
            }
            if (oldId != newId) {
                editor.remove(key(oldId))
                editor.remove(layoutKey(oldId))
            }
        }
        editor.apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun key(appWidgetId: Int): String = "$KEY_PREFIX$appWidgetId"

    private fun layoutKey(appWidgetId: Int): String = "$LAYOUT_KEY_PREFIX$appWidgetId"
}
