package io.github.nikitau.spruthubhelper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.ui.MainActivity
import io.github.nikitau.spruthubhelper.ui.WidgetConfigureActivity
import kotlinx.coroutines.launch

class SprutAppWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        AppGraph.initialize(context.applicationContext)
        updateWidgets(context, manager, appWidgetIds)
        if (appWidgetIds.any { WidgetAssignmentStore.controlId(context, it) != null }) {
            val pendingResult = goAsync()
            AppGraph.applicationScope.launch {
                try {
                    AppGraph.repository.refreshIfStale()
                    updateWidgets(context, manager, appWidgetIds)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetAssignmentStore.remove(context, it) }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        WidgetAssignmentStore.restore(context, oldWidgetIds, newWidgetIds)
        updateWidgets(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PRIMARY -> handlePrimaryAction(context, intent)
            ACTION_REFRESH -> handleRefresh(context)
        }
    }

    private fun handlePrimaryAction(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        AppGraph.initialize(context.applicationContext)
        val event = "Команда виджета"
        val controlId = WidgetAssignmentStore.controlId(context, appWidgetId)
        if (controlId == null) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.COMMAND,
                event = event,
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "Виджет не настроен",
            )
            return
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.COMMAND,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
        )
        val pendingResult = goAsync()
        AppGraph.applicationScope.launch {
            try {
                val control = AppGraph.repository.catalog.value.controls.firstOrNull { it.id == controlId }
                    ?: error("Устройство больше не найдено в SprutHub")
                val decision = WidgetActionResolver.resolve(control)
                val result = when (decision.action) {
                    WidgetPrimaryAction.TOGGLE -> AppGraph.repository.toggleBoolean(control.id)
                    WidgetPrimaryAction.EXECUTE -> AppGraph.repository.execute(control.id)
                    WidgetPrimaryAction.OPEN_APP -> Result.success(Unit)
                }
                result.getOrThrow()
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.COMMAND,
                    event = event,
                    outcome = DiagnosticOutcome.SUCCESS,
                )
                updateAll(context)
            } catch (error: Throwable) {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.COMMAND,
                    event = event,
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message,
                )
                showToast(context, error.message ?: "Не удалось выполнить команду")
                updateAll(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleRefresh(context: Context) {
        AppGraph.initialize(context.applicationContext)
        val event = "Обновление виджета"
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.COMMAND,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
        )
        val pendingResult = goAsync()
        AppGraph.applicationScope.launch {
            try {
                AppGraph.repository.refresh(forceConnection = true).getOrThrow()
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.COMMAND,
                    event = event,
                    outcome = DiagnosticOutcome.SUCCESS,
                )
                updateAll(context)
            } catch (error: Throwable) {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.COMMAND,
                    event = event,
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message,
                )
                showToast(context, error.message ?: "Не удалось обновить SprutHub")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_PRIMARY = "io.github.nikitau.spruthubhelper.widget.PRIMARY"
        private const val ACTION_REFRESH = "io.github.nikitau.spruthubhelper.widget.REFRESH"
        private const val REQUEST_PRIMARY = 10
        private const val REQUEST_REFRESH = 20
        private const val REQUEST_OPEN = 30
        private const val REQUEST_CONFIGURE = 40

        fun updateAll(context: Context) {
            AppGraph.initialize(context.applicationContext)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SprutAppWidgetProvider::class.java))
            updateWidgets(context, manager, ids)
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            AppGraph.initialize(context.applicationContext)
            updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            appWidgetIds.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val assignment = WidgetAssignmentStore.controlId(context, appWidgetId)
            val catalog = AppGraph.repository.catalog.value
            val freshness = AppGraph.repository.freshness()
            val control = assignment?.let { id -> catalog.controls.firstOrNull { it.id == id } }
            val views = RemoteViews(context.packageName, R.layout.widget_sprut_control)

            when {
                assignment == null -> renderUnconfigured(context, views, appWidgetId)
                control == null -> renderMissing(context, views, appWidgetId, catalog.controls.isEmpty())
                else -> renderControl(context, views, appWidgetId, control, freshness)
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun renderUnconfigured(context: Context, views: RemoteViews, appWidgetId: Int) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
            views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_tap_to_configure))
            views.setTextViewText(R.id.widget_value, "")
            views.setViewVisibility(R.id.widget_value, View.GONE)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_tile)
            views.setOnClickPendingIntent(R.id.widget_root, configurePendingIntent(context, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, appWidgetId))
        }

        private fun renderMissing(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            catalogIsLoading: Boolean,
        ) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
            views.setTextViewText(
                R.id.widget_subtitle,
                context.getString(if (catalogIsLoading) R.string.widget_catalog_loading else R.string.widget_control_missing),
            )
            views.setTextViewText(R.id.widget_value, "")
            views.setViewVisibility(R.id.widget_value, View.GONE)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_tile)
            views.setOnClickPendingIntent(R.id.widget_root, configurePendingIntent(context, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, appWidgetId))
        }

        private fun renderControl(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            control: SprutControl,
            freshness: CatalogFreshness,
        ) {
            val presentation = freshness.presentationFor(control)
            val pending = presentation.pending
            val authoritative = presentation.stateIsAuthoritative
            val active = presentation.active
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (active) R.drawable.bg_widget_sprut_active else R.drawable.bg_widget_sprut,
            )
            views.setTextViewText(R.id.widget_title, control.title)
            val statusPrefix = presentation.statusLabel.orEmpty()
            views.setTextViewText(
                R.id.widget_subtitle,
                listOf(statusPrefix, control.subtitle, control.room)
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString(" · "),
            )
            val visibleValue = when {
                pending -> "Ожидаем SprutHub"
                !authoritative && control.behavior == ControlBehavior.BUTTON -> "Команда недоступна"
                !authoritative -> "Последнее: ${widgetValue(control)}"
                else -> widgetValue(control)
            }
            views.setTextViewText(R.id.widget_value, visibleValue)
            views.setViewVisibility(R.id.widget_value, View.VISIBLE)
            views.setContentDescription(
                R.id.widget_root,
                listOf(control.title, statusPrefix, control.subtitle, control.room, visibleValue)
                    .filter(String::isNotBlank)
                    .joinToString(", "),
            )

            val customBitmap = CustomIconManager(context).loadBitmap(control.id)
            if (customBitmap != null) {
                views.setImageViewBitmap(R.id.widget_icon, customBitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon, TileIconResolver.resource(control.kind))
            }

            val decision = WidgetActionResolver.resolve(control)
            val primary = if (decision.action == WidgetPrimaryAction.OPEN_APP) {
                openAppPendingIntent(context, appWidgetId, control.id)
            } else {
                primaryPendingIntent(context, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.widget_root, primary)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, appWidgetId))

            val textColor = ContextCompat.getColor(context, R.color.sprut_text)
            views.setTextColor(R.id.widget_title, textColor)
            views.setTextColor(R.id.widget_subtitle, ContextCompat.getColor(context, R.color.sprut_text_muted))
            views.setTextColor(R.id.widget_value, textColor)
        }

        private fun widgetValue(control: SprutControl): String {
            val display = when (control.behavior) {
                ControlBehavior.BUTTON -> "Нажмите, чтобы запустить"
                else -> control.displayValue
            }
            return if (
                control.behavior == ControlBehavior.SENSOR &&
                control.unit.isNotBlank() &&
                control.value.numberValue != null &&
                !display.endsWith(control.unit)
            ) "$display ${control.unit}" else display
        }

        private fun primaryPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SprutAppWidgetProvider::class.java).apply {
                action = ACTION_PRIMARY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode(appWidgetId, REQUEST_PRIMARY),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SprutAppWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode(appWidgetId, REQUEST_REFRESH),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openAppPendingIntent(context: Context, appWidgetId: Int, controlId: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_CONTROL_ID, controlId)
            }
            return PendingIntent.getActivity(
                context,
                requestCode(appWidgetId, REQUEST_OPEN),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun configurePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getActivity(
                context,
                requestCode(appWidgetId, REQUEST_CONFIGURE),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun requestCode(appWidgetId: Int, suffix: Int): Int = appWidgetId * 100 + suffix

        private fun showToast(context: Context, message: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
