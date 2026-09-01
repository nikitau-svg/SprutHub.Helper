package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.data.surfaceValue

internal enum class WidgetRenderMode {
    UNCONFIGURED,
    MISSING,
    CONTROL,
}

/**
 * Everything that can change the visible RemoteViews tree. Equal fingerprints
 * are safe to coalesce; a value, pending state or connection transition is not.
 */
internal data class WidgetRenderFingerprint(
    val mode: WidgetRenderMode,
    val assignment: String? = null,
    val catalogIsEmpty: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val room: String = "",
    val semanticValue: String = "",
    val rawDisplayValue: String = "",
    val kind: String = "",
    val behavior: String = "",
    val freshnessPhase: String = "",
    val authoritative: Boolean = false,
    val pending: Boolean = false,
    val active: Boolean = false,
    val statusLabel: String = "",
    val customIconRevision: String? = null,
)

internal fun widgetRenderFingerprint(
    assignment: String?,
    control: SprutControl?,
    catalogIsEmpty: Boolean,
    freshness: CatalogFreshness,
    customIconRevision: String?,
): WidgetRenderFingerprint = when {
    assignment == null -> WidgetRenderFingerprint(mode = WidgetRenderMode.UNCONFIGURED)
    control == null -> WidgetRenderFingerprint(
        mode = WidgetRenderMode.MISSING,
        assignment = assignment,
        catalogIsEmpty = catalogIsEmpty,
    )
    else -> {
        val presentation = freshness.presentationFor(control)
        WidgetRenderFingerprint(
            mode = WidgetRenderMode.CONTROL,
            assignment = assignment,
            title = control.title,
            subtitle = control.subtitle,
            room = control.room,
            semanticValue = control.surfaceValue(),
            rawDisplayValue = control.displayValue,
            kind = control.kind.name,
            behavior = control.behavior.name,
            freshnessPhase = freshness.phase.name,
            authoritative = presentation.stateIsAuthoritative,
            pending = presentation.pending,
            active = presentation.active,
            statusLabel = presentation.statusLabel.orEmpty(),
            customIconRevision = customIconRevision,
        )
    }
}
