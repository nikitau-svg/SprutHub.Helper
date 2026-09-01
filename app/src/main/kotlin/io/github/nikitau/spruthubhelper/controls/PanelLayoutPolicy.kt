package io.github.nikitau.spruthubhelper.controls

/**
 * Keeps cards useful on both an unfolded Fold and a narrow phone. Larger
 * accessibility fonts reserve more width instead of squeezing the same number
 * of columns into the viewport.
 */
internal object PanelLayoutPolicy {
    private const val CARD_GAP_DP = 12f

    fun columnCount(maxWidthDp: Float, fontScale: Float): Int {
        val minimumCardWidthDp = when {
            fontScale >= 1.5f -> 250f
            fontScale >= 1.25f -> 210f
            else -> 170f
        }
        return ((maxWidthDp + CARD_GAP_DP) / (minimumCardWidthDp + CARD_GAP_DP))
            .toInt()
            .coerceIn(1, 4)
    }
}
