package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
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
    val headlineKey: String = "",
    val secondaryValues: String = "",
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
    card: ServiceControlCard? = null,
    preference: ServicePresentationPreference? = null,
): WidgetRenderFingerprint = when {
    assignment == null -> WidgetRenderFingerprint(mode = WidgetRenderMode.UNCONFIGURED)
    control == null -> WidgetRenderFingerprint(
        mode = WidgetRenderMode.MISSING,
        assignment = assignment,
        catalogIsEmpty = catalogIsEmpty,
    )
    else -> {
        val presentation = freshness.presentationFor(control)
        val resolvedCard = card ?: buildServiceControlCards(listOf(control)).single()
        val headline = resolvedCard.headlineDisplayValue(preference)
        val secondary = resolvedCard.secondaryDisplayValues(preference)
        WidgetRenderFingerprint(
            mode = WidgetRenderMode.CONTROL,
            assignment = assignment,
            title = resolvedCard.title,
            subtitle = resolvedCard.displayServiceName(),
            room = resolvedCard.room,
            semanticValue = headline.value,
            rawDisplayValue = headline.value,
            headlineKey = headline.key,
            secondaryValues = secondary.joinToString("|") { "${it.key}=${it.value}" },
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
