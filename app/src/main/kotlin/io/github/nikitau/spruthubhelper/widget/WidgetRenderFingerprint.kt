package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.CharacteristicDisplayValue
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.data.surfaceValue
import io.github.nikitau.spruthubhelper.icons.DefaultServiceIconResolver

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
    val defaultIcon: String = "",
    val behavior: String = "",
    val freshnessPhase: String = "",
    val authoritative: Boolean = false,
    val pending: Boolean = false,
    val active: Boolean = false,
    val statusLabel: String = "",
    val customIconRevision: String? = null,
    val layoutConfiguration: WidgetLayoutConfiguration? = null,
    val sizeSignature: String = "",
    val collectionSignature: String = "",
)

internal fun widgetRenderFingerprint(
    assignment: String?,
    control: SprutControl?,
    catalogIsEmpty: Boolean,
    freshness: CatalogFreshness,
    customIconRevision: String?,
    card: ServiceControlCard? = null,
    preference: ServicePresentationPreference? = null,
    layoutConfiguration: WidgetLayoutConfiguration? = null,
    itemConfiguration: WidgetItemConfiguration? = null,
    sizeSignature: String = "",
    collectionSignature: String = "",
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
        val configuredContent = layoutConfiguration?.let { configuration ->
            resolveWidgetContent(
                card = resolvedCard,
                configuration = configuration,
                item = itemConfiguration
                    ?: configuration.items.firstOrNull { it.controlId == control.id }
                    ?: WidgetItemConfiguration(control.id),
                sharedPreference = preference,
            )
        }
        val headline = configuredContent?.headline ?: resolvedCard.headlineDisplayValue(preference)
        val secondary = configuredContent?.secondary ?: resolvedCard.secondaryDisplayValues(preference)
        val subtitle = configuredContent?.lines?.joinToString("|") { line ->
            "${line.block.name}=${line.text}"
        } ?: widgetSubtitleParts(
                statusPrefix = presentation.statusLabel.orEmpty(),
                headline = headline,
                secondary = secondary,
                serviceName = resolvedCard.displayServiceName(),
                room = resolvedCard.room,
            ).joinToString(" · ")
        WidgetRenderFingerprint(
            mode = WidgetRenderMode.CONTROL,
            assignment = assignment,
            title = resolvedCard.title,
            subtitle = subtitle,
            room = resolvedCard.room,
            semanticValue = headline.value,
            rawDisplayValue = headline.value,
            headlineKey = headline.key,
            secondaryValues = secondary.joinToString("|") { "${it.key}=${it.value}" },
            kind = control.kind.name,
            defaultIcon = DefaultServiceIconResolver.resolve(resolvedCard).name,
            behavior = control.behavior.name,
            freshnessPhase = freshness.phase.name,
            authoritative = presentation.stateIsAuthoritative,
            pending = presentation.pending,
            active = presentation.active,
            statusLabel = presentation.statusLabel.orEmpty(),
            customIconRevision = customIconRevision,
            layoutConfiguration = layoutConfiguration?.normalized(),
            sizeSignature = sizeSignature,
            collectionSignature = collectionSignature,
        )
    }
}

/**
 * Builds the compact widget caption without repeating a service name that is
 * already used as the selected headline or one of the secondary metrics.
 */
internal fun widgetSubtitleParts(
    statusPrefix: String,
    headline: CharacteristicDisplayValue,
    secondary: List<CharacteristicDisplayValue>,
    serviceName: String,
    room: String,
): List<String> {
    val metricLabels = (listOf(headline) + secondary)
        .map { it.label.trim() }
        .filter(String::isNotBlank)
    val distinctServiceName = serviceName.trim().takeUnless { candidate ->
        metricLabels.any { label -> label.equals(candidate, ignoreCase = true) }
    }.orEmpty()
    val secondaryText = secondary.joinToString(" · ") { "${it.label} ${it.value}".trim() }
    return listOf(statusPrefix, headline.label, secondaryText, distinctServiceName, room)
        .map(String::trim)
        .filter(String::isNotBlank)
        .fold(mutableListOf()) { parts, candidate ->
            if (parts.none { it.equals(candidate, ignoreCase = true) }) parts += candidate
            parts
        }
}
