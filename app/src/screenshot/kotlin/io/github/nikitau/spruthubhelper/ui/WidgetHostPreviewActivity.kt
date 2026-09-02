package io.github.nikitau.spruthubhelper.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.widget.SprutAppWidgetProvider
import io.github.nikitau.spruthubhelper.widget.WidgetAssignmentStore
import io.github.nikitau.spruthubhelper.widget.WidgetInformationDensity
import io.github.nikitau.spruthubhelper.widget.WidgetItemConfiguration
import io.github.nikitau.spruthubhelper.widget.WidgetLayoutConfiguration

/** Hardware-only host for validating the real RemoteViews in the isolated screenshot package. */
class WidgetHostPreviewActivity : Activity() {
    private lateinit var widgetHost: AppWidgetHost
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)

        val widthDp = intent.getIntExtra(EXTRA_WIDTH_DP, DEFAULT_WIDTH_DP).coerceIn(56, 560)
        val heightDp = intent.getIntExtra(EXTRA_HEIGHT_DP, DEFAULT_HEIGHT_DP).coerceIn(50, 420)
        val controlIds = intent.getStringExtra(EXTRA_CONTROL_IDS)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.take(8)
            .orEmpty()
            .ifEmpty { DEFAULT_CONTROL_IDS }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        val label = TextView(this).apply {
            text = "Настоящий Android-виджет · ${widthDp}×${heightDp} dp"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        content.addView(
            label,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            },
        )
        setContentView(content)

        val manager = AppWidgetManager.getInstance(this)
        val providerComponent = ComponentName(this, SprutAppWidgetProvider::class.java)
        val provider = manager.installedProviders.firstOrNull { it.provider == providerComponent }
        if (provider == null) {
            showError(content, "Провайдер виджета не найден")
            return
        }

        widgetHost = AppWidgetHost(this, SCREENSHOT_WIDGET_HOST_ID)
        // ADB may stop this activity without delivering onDestroy. Clear the
        // previous synthetic host so screenshot runs never leave orphaned ids.
        widgetHost.deleteHost()
        widgetHost.startListening()
        appWidgetId = widgetHost.allocateAppWidgetId()
        val options = widgetOptions(widthDp, heightDp)
        if (!manager.bindAppWidgetIdIfAllowed(appWidgetId, providerComponent, options)) {
            showError(content, "AppWidgetHost не получил разрешение на привязку")
            return
        }

        WidgetAssignmentStore.save(
            context = this,
            appWidgetId = appWidgetId,
            controlId = controlIds.first(),
            layout = WidgetLayoutConfiguration(
                density = WidgetInformationDensity.DETAILED,
                items = controlIds.map(::WidgetItemConfiguration),
                showRefresh = true,
            ),
        )
        manager.updateAppWidgetOptions(appWidgetId, options)
        val hostView = widgetHost.createView(this, appWidgetId, provider).apply {
            setPadding(0, 0, 0, 0)
        }
        val availableWidthDp = (resources.configuration.screenWidthDp - 40).coerceAtLeast(1)
        val previewScale = minOf(1f, availableWidthDp.toFloat() / widthDp.toFloat())
        val previewFrame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        hostView.pivotX = 0f
        hostView.pivotY = 0f
        hostView.scaleX = previewScale
        hostView.scaleY = previewScale
        previewFrame.addView(
            hostView,
            FrameLayout.LayoutParams(dp(widthDp), dp(heightDp)),
        )
        content.addView(
            previewFrame,
            LinearLayout.LayoutParams(
                dp((widthDp * previewScale).roundToInt()),
                dp((heightDp * previewScale).roundToInt()),
            ),
        )
        hostView.postDelayed(
            { SprutAppWidgetProvider.updateWidget(this, appWidgetId) },
            200L,
        )
    }

    override fun onDestroy() {
        if (::widgetHost.isInitialized) {
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetAssignmentStore.remove(this, appWidgetId)
                widgetHost.deleteAppWidgetId(appWidgetId)
            }
            widgetHost.stopListening()
        }
        super.onDestroy()
    }

    private fun widgetOptions(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
            )
        }
    }

    private fun showError(container: LinearLayout, message: String) {
        container.addView(TextView(this).apply {
            text = message
            setTextColor(Color.rgb(255, 168, 5))
            textSize = 16f
            gravity = Gravity.CENTER
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SCREENSHOT_WIDGET_HOST_ID = 0x5350
        const val EXTRA_WIDTH_DP = "previewWidthDp"
        const val EXTRA_HEIGHT_DP = "previewHeightDp"
        const val EXTRA_CONTROL_IDS = "previewControlIds"
        const val DEFAULT_WIDTH_DP = 226
        const val DEFAULT_HEIGHT_DP = 102
        val DEFAULT_CONTROL_IDS = listOf("climate-power", "coffee-outlet", "floor-lamp")
    }
}
