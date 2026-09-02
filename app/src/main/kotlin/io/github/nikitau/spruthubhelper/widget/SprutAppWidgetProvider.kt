package io.github.nikitau.spruthubhelper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.ui.MainActivity
import io.github.nikitau.spruthubhelper.ui.WidgetConfigureActivity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class SprutAppWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        AppGraph.initialize(context.applicationContext)
        // The launcher may have restarted and lost its RemoteViews even while
        // our process-local fingerprint cache survived. A host update must
        // therefore always deliver one complete tree.
        updateWidgets(context, manager, appWidgetIds, force = true)
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
        updateWidget(context, appWidgetManager, appWidgetId, force = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            WidgetAssignmentStore.remove(context, appWidgetId)
            renderedFingerprints.remove(appWidgetId)
        }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        WidgetAssignmentStore.restore(context, oldWidgetIds, newWidgetIds)
        oldWidgetIds.forEach(renderedFingerprints::remove)
        newWidgetIds.forEach(renderedFingerprints::remove)
        updateWidgets(context, AppWidgetManager.getInstance(context), newWidgetIds, force = true)
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
        val assignment = WidgetAssignmentStore.assignment(context, appWidgetId)
        val requestedControlId = intent.getStringExtra(EXTRA_CONTROL_ID)
        val controlId = resolveWidgetActionControlId(
            primaryControlId = assignment?.controlId,
            configuration = assignment?.layout,
            requestedControlId = requestedControlId,
        )
        if (assignment == null || controlId == null) {
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
        private const val EXTRA_CONTROL_ID = "widget_control_id"
        private const val REQUEST_PRIMARY = 10
        private const val REQUEST_REFRESH = 20
        private const val REQUEST_OPEN = 30
        private const val REQUEST_CONFIGURE = 40
        private val renderedFingerprints = ConcurrentHashMap<Int, WidgetRenderFingerprint>()
        private val renderLock = Any()

        private data class WidgetRenderItem(
            val control: SprutControl,
            val card: ServiceControlCard,
            val preference: ServicePresentationPreference?,
            val configuration: WidgetItemConfiguration,
            val iconRevision: String?,
        )

        fun updateAll(context: Context) {
            AppGraph.initialize(context.applicationContext)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SprutAppWidgetProvider::class.java))
            updateWidgets(context, manager, ids)
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            AppGraph.initialize(context.applicationContext)
            updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId, force = true)
        }

        private fun updateWidgets(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
            force: Boolean = false,
        ) {
            appWidgetIds.forEach { updateWidget(context, manager, it, force) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            force: Boolean = false,
        ) {
            val storedAssignment = WidgetAssignmentStore.assignment(context, appWidgetId)
            val assignment = storedAssignment?.controlId
            val layoutConfiguration = storedAssignment?.layout
            val catalog = AppGraph.repository.catalog.value
            val freshness = AppGraph.repository.freshness()
            val control = assignment?.let { id -> catalog.controls.firstOrNull { it.id == id } }
            val cards = buildServiceControlCards(catalog.controls)
            val preferences = AppGraph.repository.servicePresentations.value
            val renderItems = layoutConfiguration?.items.orEmpty().mapNotNull { itemConfiguration ->
                val itemControl = catalog.controls.firstOrNull { it.id == itemConfiguration.controlId }
                    ?: return@mapNotNull null
                val itemCard = cards.firstOrNull { candidate ->
                    candidate.controls.any { it.id == itemControl.id }
                } ?: buildServiceControlCards(listOf(itemControl)).single()
                WidgetRenderItem(
                    control = itemControl,
                    card = itemCard,
                    preference = preferences.firstOrNull { it.cardId == itemCard.id },
                    configuration = itemConfiguration,
                    iconRevision = CustomIconManager(context).revision(itemCard.id)
                        ?: CustomIconManager(context).revision(itemControl.id),
                )
            }
            val primaryRenderItem = renderItems.firstOrNull()
            val card = primaryRenderItem?.card ?: control?.let { selectedControl ->
                cards.firstOrNull { candidate -> candidate.controls.any { it.id == selectedControl.id } }
            }
            val preference = card?.let { selectedCard ->
                preferences.firstOrNull { it.cardId == selectedCard.id }
            }
            val fingerprintControl = primaryRenderItem?.control ?: control
            val iconRevision = primaryRenderItem?.iconRevision
                ?: card?.let { CustomIconManager(context).revision(it.id) }
                ?: control?.let { CustomIconManager(context).revision(it.id) }
            val options = manager.getAppWidgetOptions(appWidgetId)
            val sizeSignature = if (layoutConfiguration == null) "legacy" else widgetSizeSignature(options)
            val collectionSignature = renderItems.joinToString("||") { item ->
                val surface = freshness.presentationFor(item.control)
                val content = resolveWidgetContent(
                    card = item.card,
                    configuration = requireNotNull(layoutConfiguration),
                    item = item.configuration,
                    sharedPreference = item.preference,
                )
                listOf(
                    item.control.id,
                    item.card.title,
                    item.card.room,
                    content.headline.key,
                    content.headline.value,
                    content.secondary.joinToString("|") { "${it.key}=${it.value}" },
                    surface.pending,
                    surface.stateIsAuthoritative,
                    surface.active,
                    surface.statusLabel,
                    item.iconRevision,
                ).joinToString(";")
            }
            val fingerprint = widgetRenderFingerprint(
                assignment = assignment,
                control = fingerprintControl,
                catalogIsEmpty = catalog.controls.isEmpty(),
                freshness = freshness,
                customIconRevision = iconRevision,
                card = card,
                preference = preference,
                layoutConfiguration = layoutConfiguration,
                itemConfiguration = primaryRenderItem?.configuration,
                sizeSignature = sizeSignature,
                collectionSignature = collectionSignature,
            )
            synchronized(renderLock) {
                if (!force && renderedFingerprints[appWidgetId] == fingerprint) return
                val resolvedCard = fingerprintControl?.let { selectedControl ->
                    card ?: buildServiceControlCards(listOf(selectedControl)).single()
                }
                val views = if (layoutConfiguration != null && renderItems.isNotEmpty()) {
                    createResponsiveViews(
                        context = context,
                        appWidgetId = appWidgetId,
                        items = renderItems,
                        freshness = freshness,
                        configuration = layoutConfiguration,
                        options = options,
                    )
                } else {
                    RemoteViews(context.packageName, R.layout.widget_sprut_control).also { legacyViews ->
                        when {
                            assignment == null -> renderUnconfigured(context, legacyViews, appWidgetId)
                            control == null -> renderMissing(
                                context,
                                legacyViews,
                                appWidgetId,
                                catalog.controls.isEmpty(),
                            )
                            else -> renderControl(
                                context,
                                legacyViews,
                                appWidgetId,
                                control,
                                requireNotNull(resolvedCard),
                                preference,
                                freshness,
                            )
                        }
                    }
                }
                manager.updateAppWidget(appWidgetId, views)
                renderedFingerprints[appWidgetId] = fingerprint
            }
        }

        private fun createResponsiveViews(
            context: Context,
            appWidgetId: Int,
            items: List<WidgetRenderItem>,
            freshness: CatalogFreshness,
            configuration: WidgetLayoutConfiguration,
            options: Bundle,
        ): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = exactWidgetHostSizes(options)
                if (sizes.isNotEmpty()) {
                    return responsiveRemoteViews(
                        sizes = sizes,
                        factory = { hostSize ->
                            createResponsiveView(
                                context = context,
                                appWidgetId = appWidgetId,
                                items = items,
                                freshness = freshness,
                                configuration = configuration,
                                hostSize = hostSize,
                            )
                        },
                    )
                }
            }
            return createResponsiveView(
                context = context,
                appWidgetId = appWidgetId,
                items = items,
                freshness = freshness,
                configuration = configuration,
                hostSize = fallbackWidgetSize(options),
            )
        }

        private fun createResponsiveView(
            context: Context,
            appWidgetId: Int,
            items: List<WidgetRenderItem>,
            freshness: CatalogFreshness,
            configuration: WidgetLayoutConfiguration,
            hostSize: WidgetHostSize,
        ): RemoteViews {
            val sizeClass = hostSize.sizeClass()
            val minimal = sizeClass == WidgetSizeClass.ICON || sizeClass == WidgetSizeClass.STRIP
            return if (items.size > 1 && !minimal) {
                createGridView(
                    context = context,
                    appWidgetId = appWidgetId,
                    items = items,
                    freshness = freshness,
                    configuration = configuration,
                    hostSize = hostSize,
                )
            } else {
                createComposedView(
                    context = context,
                    appWidgetId = appWidgetId,
                    item = items.first(),
                    freshness = freshness,
                    configuration = configuration,
                    hostSize = hostSize,
                    hiddenItemCount = if (minimal) items.size - 1 else 0,
                )
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun responsiveRemoteViews(
            sizes: List<WidgetHostSize>,
            factory: (WidgetHostSize) -> RemoteViews,
        ): RemoteViews = RemoteViews(
            sizes.associate { size -> SizeF(size.widthDp, size.heightDp) to factory(size) },
        )

        private fun createComposedView(
            context: Context,
            appWidgetId: Int,
            item: WidgetRenderItem,
            freshness: CatalogFreshness,
            configuration: WidgetLayoutConfiguration,
            hostSize: WidgetHostSize,
            hiddenItemCount: Int,
        ): RemoteViews {
            val sizeClass = hostSize.sizeClass()
            val layout = when (sizeClass) {
                WidgetSizeClass.STRIP -> R.layout.widget_sprut_strip
                WidgetSizeClass.ICON -> R.layout.widget_sprut_icon
                WidgetSizeClass.COMPACT,
                WidgetSizeClass.WIDE,
                WidgetSizeClass.TALL,
                -> R.layout.widget_sprut_composed
            }
            val views = RemoteViews(context.packageName, layout)
            val control = item.control
            val card = item.card
            val presentation = freshness.presentationFor(control)
            val headline = resolveWidgetContent(
                card = card,
                configuration = configuration,
                item = item.configuration,
                sharedPreference = item.preference,
            ).headline
            val visibleValue = when {
                presentation.pending -> "Ожидаем SprutHub"
                !presentation.stateIsAuthoritative && control.behavior == ControlBehavior.BUTTON ->
                    "Команда недоступна"
                !presentation.stateIsAuthoritative -> "Последнее: ${headline.value}"
                else -> headline.value
            }
            val content = resolveWidgetContent(
                card = card,
                configuration = configuration,
                item = item.configuration,
                sharedPreference = item.preference,
                primaryValueOverride = visibleValue,
            )
            val lines = visibleWidgetLines(
                content = content,
                configuration = configuration,
                sizeClass = sizeClass,
                fontScale = context.resources.configuration.fontScale,
            ).map { line ->
                if (
                    (sizeClass == WidgetSizeClass.ICON || sizeClass == WidgetSizeClass.STRIP) &&
                    line.block == WidgetContentBlock.PRIMARY_VALUE
                ) {
                    line.copy(
                        text = compactWidgetValue(
                            value = visibleValue,
                            hiddenItemCount = hiddenItemCount,
                            narrow = sizeClass == WidgetSizeClass.ICON && hostSize.widthDp < 72f,
                        ),
                    )
                } else {
                    line
                }
            }
            val lineIds = intArrayOf(
                R.id.widget_line_1,
                R.id.widget_line_2,
                R.id.widget_line_3,
                R.id.widget_line_4,
            )
            lineIds.forEach { id -> views.setViewVisibility(id, View.GONE) }
            lines.zip(lineIds.asIterable()).forEach { (line, id) ->
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, line.text)
                views.setTextViewTextSize(
                    id,
                    TypedValue.COMPLEX_UNIT_SP,
                    widgetLineTextSize(line.block, sizeClass, hostSize),
                )
                views.setTextColor(
                    id,
                    ContextCompat.getColor(
                        context,
                        if (
                            line.block == WidgetContentBlock.TITLE ||
                            line.block == WidgetContentBlock.PRIMARY_VALUE
                        ) {
                            R.color.sprut_text
                        } else {
                            R.color.sprut_text_muted
                        },
                    ),
                )
                views.setInt(
                    id,
                    "setMaxLines",
                    if (sizeClass == WidgetSizeClass.TALL && line.block == WidgetContentBlock.SECONDARY_VALUES) 2 else 1,
                )
            }
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (presentation.active) R.drawable.bg_widget_sprut_active else R.drawable.bg_widget_sprut,
            )
            val customBitmap = CustomIconManager(context).loadBitmap(card.id)
                ?: CustomIconManager(context).loadBitmap(control.id)
            if (customBitmap != null) {
                views.setImageViewBitmap(R.id.widget_icon, customBitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon, TileIconResolver.resource(card))
                views.setInt(
                    R.id.widget_icon,
                    "setColorFilter",
                    ContextCompat.getColor(
                        context,
                        if (presentation.active) R.color.sprut_green else R.color.sprut_text,
                    ),
                )
            }
            val showRefresh = shouldShowWidgetRefresh(
                hostSize = hostSize,
                requested = configuration.showRefresh,
                fontScale = context.resources.configuration.fontScale,
            )
            views.setViewVisibility(R.id.widget_refresh, if (showRefresh) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                refreshPendingIntent(context, appWidgetId),
            )
            val decision = WidgetActionResolver.resolve(control)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                if (decision.action == WidgetPrimaryAction.OPEN_APP) {
                    openAppPendingIntent(context, appWidgetId, control.id)
                } else {
                    primaryPendingIntent(context, appWidgetId, control.id)
                },
            )
            views.setContentDescription(
                R.id.widget_root,
                (listOf(card.title) + lines.map(WidgetContentLine::text) + presentation.statusLabel.orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString(", "),
            )
            return views
        }

        private fun createGridView(
            context: Context,
            appWidgetId: Int,
            items: List<WidgetRenderItem>,
            freshness: CatalogFreshness,
            configuration: WidgetLayoutConfiguration,
            hostSize: WidgetHostSize,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_sprut_grid)
            val grid = widgetGridLayout(
                hostSize = hostSize,
                itemCount = items.size,
                density = configuration.density,
                fontScale = context.resources.configuration.fontScale,
            )
            val visibleItems = items.take(grid.visibleItemCount)
            views.removeAllViews(R.id.widget_items_row_1)
            views.removeAllViews(R.id.widget_items_row_2)
            views.setViewVisibility(R.id.widget_items_row_2, if (grid.rows > 1) View.VISIBLE else View.GONE)
            visibleItems.forEachIndexed { index, item ->
                val child = createGridItemView(
                    context = context,
                    appWidgetId = appWidgetId,
                    itemIndex = index,
                    item = item,
                    allItems = items,
                    freshness = freshness,
                    configuration = configuration,
                    showSecondary = grid.rows > 1 &&
                        configuration.density == WidgetInformationDensity.DETAILED,
                    hiddenItemCount = if (index == visibleItems.lastIndex) grid.hiddenItemCount else 0,
                )
                views.addView(
                    if (index < grid.columns) R.id.widget_items_row_1 else R.id.widget_items_row_2,
                    child,
                )
            }
            val showRefresh = configuration.showRefresh && hostSize.widthDp >= 440f
            val refreshInsetPx = if (showRefresh) {
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    36f,
                    context.resources.displayMetrics,
                ).roundToInt()
            } else {
                0
            }
            views.setViewPadding(
                R.id.widget_items_container,
                0,
                0,
                refreshInsetPx,
                0,
            )
            views.setViewVisibility(R.id.widget_refresh, if (showRefresh) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, appWidgetId))
            views.setContentDescription(
                R.id.widget_root,
                visibleItems.joinToString(", ") { item ->
                    val headline = resolveWidgetContent(
                        item.card,
                        configuration,
                        item.configuration,
                        item.preference,
                    ).headline
                    "${gridItemTitle(item, items)}: ${headline.value}"
                },
            )
            return views
        }

        private fun createGridItemView(
            context: Context,
            appWidgetId: Int,
            itemIndex: Int,
            item: WidgetRenderItem,
            allItems: List<WidgetRenderItem>,
            freshness: CatalogFreshness,
            configuration: WidgetLayoutConfiguration,
            showSecondary: Boolean,
            hiddenItemCount: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_sprut_grid_item)
            val surface = freshness.presentationFor(item.control)
            val content = resolveWidgetContent(
                card = item.card,
                configuration = configuration,
                item = item.configuration,
                sharedPreference = item.preference,
            )
            val value = when {
                surface.pending -> "Ожидание…"
                !surface.stateIsAuthoritative -> "Последнее: ${content.headline.value}"
                else -> content.headline.value
            }
            views.setTextViewText(R.id.widget_item_title, gridItemTitle(item, allItems))
            views.setTextViewText(R.id.widget_item_value, value)
            val secondary = content.secondary.firstOrNull()?.let { "${it.label} ${it.value}" }.orEmpty()
            views.setTextViewText(R.id.widget_item_secondary, secondary)
            views.setViewVisibility(
                R.id.widget_item_secondary,
                if (showSecondary && secondary.isNotBlank()) View.VISIBLE else View.GONE,
            )
            val overflow = widgetOverflowLabel(hiddenItemCount)
            views.setTextViewText(R.id.widget_item_overflow, overflow)
            views.setViewVisibility(
                R.id.widget_item_overflow,
                if (overflow.isNotBlank()) View.VISIBLE else View.GONE,
            )
            views.setInt(
                R.id.widget_item_root,
                "setBackgroundResource",
                if (surface.active) R.drawable.bg_widget_grid_item_active else R.drawable.bg_widget_grid_item,
            )
            val customBitmap = CustomIconManager(context).loadBitmap(item.card.id)
                ?: CustomIconManager(context).loadBitmap(item.control.id)
            if (customBitmap != null) {
                views.setImageViewBitmap(R.id.widget_item_icon, customBitmap)
            } else {
                views.setImageViewResource(R.id.widget_item_icon, TileIconResolver.resource(item.card))
                views.setInt(
                    R.id.widget_item_icon,
                    "setColorFilter",
                    ContextCompat.getColor(
                        context,
                        if (surface.active) R.color.sprut_green else R.color.sprut_text,
                    ),
                )
            }
            val decision = WidgetActionResolver.resolve(item.control)
            views.setOnClickPendingIntent(
                R.id.widget_item_root,
                if (decision.action == WidgetPrimaryAction.OPEN_APP) {
                    openAppPendingIntent(context, appWidgetId, item.control.id, itemIndex)
                } else {
                    primaryPendingIntent(context, appWidgetId, item.control.id, itemIndex)
                },
            )
            views.setContentDescription(
                R.id.widget_item_root,
                listOf(gridItemTitle(item, allItems), value, secondary, overflow)
                    .filter(String::isNotBlank)
                    .joinToString(", "),
            )
            return views
        }

        private fun gridItemTitle(item: WidgetRenderItem, allItems: List<WidgetRenderItem>): String {
            val duplicateTitle = allItems.count {
                it.card.title.equals(item.card.title, ignoreCase = true)
            } > 1
            return if (duplicateTitle) {
                "${item.card.title} · ${item.card.displayServiceName()}"
            } else {
                item.card.title
            }
        }

        private fun widgetLineTextSize(
            block: WidgetContentBlock,
            sizeClass: WidgetSizeClass,
            hostSize: WidgetHostSize,
        ): Float = when {
            sizeClass == WidgetSizeClass.ICON && hostSize.widthDp < 72f -> 10f
            (sizeClass == WidgetSizeClass.ICON || sizeClass == WidgetSizeClass.STRIP) &&
                block == WidgetContentBlock.PRIMARY_VALUE -> 11f
            block == WidgetContentBlock.PRIMARY_VALUE -> 15f
            block == WidgetContentBlock.TITLE -> 14f
            else -> 11f
        }

        private fun widgetSizeSignature(options: Bundle): String {
            val exact = exactWidgetHostSizes(options)
            if (exact.isNotEmpty()) {
                return exact
                    .sortedWith(compareBy(WidgetHostSize::widthDp, WidgetHostSize::heightDp))
                    .joinToString("|") { size ->
                        "${size.widthDp.toInt()}x${size.heightDp.toInt()}:${size.sizeClass()}"
                    }
            }
            val fallback = fallbackWidgetSize(options)
            return "${fallback.widthDp.toInt()}x${fallback.heightDp.toInt()}:${fallback.sizeClass()}"
        }

        private fun fallbackWidgetSize(options: Bundle): WidgetHostSize = safeWidgetHostSize(
            widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 226).toFloat(),
            heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 102).toFloat(),
        )

        private fun exactWidgetHostSizes(options: Bundle): List<WidgetHostSize> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
            val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java,
                ).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
            }
            return boundedWidgetHostSizes(
                sizes.map { size -> WidgetHostSize(size.width, size.height) },
            )
        }

        private fun renderUnconfigured(context: Context, views: RemoteViews, appWidgetId: Int) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
            views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_tap_to_configure))
            views.setTextViewText(R.id.widget_value, "")
            views.setViewVisibility(R.id.widget_value, View.GONE)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_device_other)
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
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_device_other)
            views.setOnClickPendingIntent(R.id.widget_root, configurePendingIntent(context, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context, appWidgetId))
        }

        private fun renderControl(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            control: SprutControl,
            card: ServiceControlCard,
            preference: ServicePresentationPreference?,
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
            views.setTextViewText(R.id.widget_title, card.title)
            val statusPrefix = presentation.statusLabel.orEmpty()
            val headline = card.headlineDisplayValue(preference)
            val secondaryValues = card.secondaryDisplayValues(preference)
            val subtitleParts = widgetSubtitleParts(
                statusPrefix = statusPrefix,
                headline = headline,
                secondary = secondaryValues,
                serviceName = card.displayServiceName(),
                room = card.room,
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                subtitleParts.joinToString(" · "),
            )
            val headlineValue = headline.value
            val visibleValue = when {
                pending -> "Ожидаем SprutHub"
                !authoritative && control.behavior == ControlBehavior.BUTTON -> "Команда недоступна"
                !authoritative -> "Последнее: $headlineValue"
                else -> headlineValue
            }
            views.setTextViewText(R.id.widget_value, visibleValue)
            views.setViewVisibility(R.id.widget_value, View.VISIBLE)
            views.setContentDescription(
                R.id.widget_root,
                (listOf(card.title) + subtitleParts + visibleValue)
                    .filter(String::isNotBlank)
                    .joinToString(", "),
            )

            val customBitmap = CustomIconManager(context).loadBitmap(card.id)
                ?: CustomIconManager(context).loadBitmap(control.id)
            if (customBitmap != null) {
                views.setImageViewBitmap(R.id.widget_icon, customBitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon, TileIconResolver.resource(card))
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

        private fun primaryPendingIntent(
            context: Context,
            appWidgetId: Int,
            controlId: String? = null,
            itemIndex: Int = 0,
        ): PendingIntent {
            val intent = Intent(context, SprutAppWidgetProvider::class.java).apply {
                action = ACTION_PRIMARY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                controlId?.let { putExtra(EXTRA_CONTROL_ID, it) }
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode(appWidgetId, REQUEST_PRIMARY + itemIndex),
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

        private fun openAppPendingIntent(
            context: Context,
            appWidgetId: Int,
            controlId: String,
            itemIndex: Int = 0,
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_CONTROL_ID, controlId)
            }
            return PendingIntent.getActivity(
                context,
                requestCode(appWidgetId, REQUEST_OPEN + itemIndex),
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
