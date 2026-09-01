package io.github.nikitau.spruthubhelper.data

/**
 * One shared freshness contract for every Android surface that displays a
 * SprutHub value. A cached value is useful for identification and layout, but
 * it must not look authoritative after the transport has failed.
 */
enum class CatalogFreshnessPhase {
    EMPTY,
    REFRESHING,
    LIVE,
    RECENT,
    STALE,
    OFFLINE,
}

data class CatalogFreshness(
    val phase: CatalogFreshnessPhase,
    val authoritativeAtEpochMs: Long? = null,
    val ageMs: Long? = null,
    val pendingControlIds: Set<String> = emptySet(),
) {
    val canDisplayAuthoritativeState: Boolean
        get() = phase == CatalogFreshnessPhase.LIVE || phase == CatalogFreshnessPhase.RECENT

    fun isPending(controlId: String): Boolean = controlId in pendingControlIds

    val shortLabel: String
        get() = when (phase) {
            CatalogFreshnessPhase.EMPTY -> "Нет данных"
            CatalogFreshnessPhase.REFRESHING -> "Обновление…"
            CatalogFreshnessPhase.LIVE -> "Онлайн"
            CatalogFreshnessPhase.RECENT -> "Недавно обновлено"
            CatalogFreshnessPhase.STALE -> "Данные устарели"
            CatalogFreshnessPhase.OFFLINE -> "Нет связи"
        }
}

object CatalogFreshnessPolicy {
    const val DISPLAY_MAX_AGE_MS = 30_000L
    const val COMMAND_MAX_AGE_MS = 10_000L

    fun evaluate(
        catalog: SprutCatalog,
        connection: ConnectionStatus,
        pendingControlIds: Set<String> = emptySet(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CatalogFreshness {
        val authoritativeAt = catalog.refreshedAtEpochMs.takeIf { it > 0L }
        val age = authoritativeAt?.let { (nowEpochMs - it).coerceAtLeast(0L) }
        val phase = when {
            connection.phase == ConnectionPhase.CONNECTING -> CatalogFreshnessPhase.REFRESHING
            connection.phase == ConnectionPhase.ERROR -> CatalogFreshnessPhase.OFFLINE
            catalog.controls.isEmpty() -> CatalogFreshnessPhase.EMPTY
            connection.phase == ConnectionPhase.CONNECTED_LOCAL ||
                connection.phase == ConnectionPhase.CONNECTED_CLOUD -> CatalogFreshnessPhase.LIVE
            age != null && age <= DISPLAY_MAX_AGE_MS -> CatalogFreshnessPhase.RECENT
            else -> CatalogFreshnessPhase.STALE
        }
        return CatalogFreshness(
            phase = phase,
            authoritativeAtEpochMs = authoritativeAt,
            ageMs = age,
            pendingControlIds = pendingControlIds,
        )
    }
}

data class ControlSurfacePresentation(
    val stateIsAuthoritative: Boolean,
    val pending: Boolean,
    val active: Boolean,
    val statusLabel: String? = null,
)

fun CatalogFreshness.presentationFor(control: SprutControl): ControlSurfacePresentation {
    val pending = isPending(control.id)
    val authoritative = canDisplayAuthoritativeState && !pending
    val switchLike = control.behavior == ControlBehavior.TOGGLE ||
        control.behavior == ControlBehavior.TOGGLE_RANGE
    val status = when {
        pending -> "Подтверждаем…"
        phase == CatalogFreshnessPhase.LIVE -> null
        else -> shortLabel
    }
    return ControlSurfacePresentation(
        stateIsAuthoritative = authoritative,
        pending = pending,
        active = authoritative && switchLike && control.value.asBoolean(),
        statusLabel = status,
    )
}
