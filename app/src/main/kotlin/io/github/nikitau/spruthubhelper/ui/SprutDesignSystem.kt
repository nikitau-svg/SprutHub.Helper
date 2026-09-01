package io.github.nikitau.spruthubhelper.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual contract for the 0.7 line.
 *
 * The palette follows the visual rhythm of the current SprutHub web interface
 * without copying its private assets: neutral graphite layers, restrained
 * translucency, a single amber action accent and compact typography.
 */
internal val SprutBackground = Color(0xFF121212)
internal val SprutSurfaceLow = Color(0xFF1B1B1B)
internal val SprutSurface = Color(0xFF262626)
internal val SprutSurfaceHigh = Color(0xFF323232)
internal val SprutSurfaceHighest = Color(0xFF3F3F3F)
internal val SprutAccent = Color(0xFFFFA805)
internal val SprutAccentDim = Color(0xFF774E00)
internal val SprutText = Color(0xFFFFFFFF)
internal val SprutTextMuted = Color(0x99FFFFFF)
internal val SprutTextFaint = Color(0x66FFFFFF)
internal val SprutOutline = Color(0xFF4B4B4B)
internal val SprutSuccess = Color(0xFF4CAF50)
internal val SprutWarning = Color(0xFFFF9800)
internal val SprutError = Color(0xFFDD3434)
internal val SprutInfo = Color(0xFF64B5F6)

internal val SprutTileShape = RoundedCornerShape(16.dp)
internal val SprutControlShape = RoundedCornerShape(12.dp)

private val SprutColorScheme = darkColorScheme(
    primary = SprutAccent,
    onPrimary = Color(0xFF211500),
    primaryContainer = SprutAccentDim,
    onPrimaryContainer = SprutText,
    secondary = Color(0xFFFFC65C),
    onSecondary = Color(0xFF211500),
    secondaryContainer = Color(0xFF4A3500),
    onSecondaryContainer = SprutText,
    background = SprutBackground,
    surface = SprutSurfaceLow,
    surfaceVariant = SprutSurface,
    onBackground = SprutText,
    onSurface = SprutText,
    onSurfaceVariant = SprutTextMuted,
    outline = SprutOutline,
    outlineVariant = Color(0xFF343434),
    error = SprutError,
    onError = SprutText,
    errorContainer = Color(0xFF5A1515),
    onErrorContainer = SprutText,
)

private val SprutTypography = Typography(
    displayLarge = sprutTextStyle(52, 56, FontWeight.SemiBold),
    displayMedium = sprutTextStyle(44, 48, FontWeight.SemiBold),
    displaySmall = sprutTextStyle(36, 40, FontWeight.SemiBold),
    headlineLarge = sprutTextStyle(30, 36, FontWeight.SemiBold),
    headlineMedium = sprutTextStyle(24, 30, FontWeight.SemiBold),
    headlineSmall = sprutTextStyle(22, 28, FontWeight.SemiBold),
    titleLarge = sprutTextStyle(20, 26, FontWeight.SemiBold),
    titleMedium = sprutTextStyle(16, 22, FontWeight.SemiBold),
    titleSmall = sprutTextStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = sprutTextStyle(16, 22, FontWeight.Normal),
    bodyMedium = sprutTextStyle(14, 20, FontWeight.Normal),
    bodySmall = sprutTextStyle(12, 17, FontWeight.Normal),
    labelLarge = sprutTextStyle(14, 18, FontWeight.SemiBold),
    labelMedium = sprutTextStyle(12, 16, FontWeight.Medium),
    labelSmall = sprutTextStyle(11, 14, FontWeight.Normal),
)

private val SprutShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = SprutControlShape,
    medium = SprutTileShape,
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun sprutTextStyle(
    sizeSp: Int,
    lineHeightSp: Int,
    weight: FontWeight,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = 0.sp,
)

@Composable
internal fun SprutHelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SprutColorScheme,
        typography = SprutTypography,
        shapes = SprutShapes,
        content = content,
    )
}

@Composable
internal fun SprutHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = SprutText)
        }
    }
}

@Composable
internal fun sprutTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SprutText,
    unfocusedTextColor = SprutText,
    disabledTextColor = SprutTextFaint,
    errorTextColor = SprutText,
    focusedContainerColor = SprutSurfaceHighest,
    unfocusedContainerColor = SprutSurfaceHigh,
    disabledContainerColor = SprutSurfaceHigh.copy(alpha = 0.5f),
    errorContainerColor = SprutSurfaceHigh,
    cursorColor = SprutAccent,
    errorCursorColor = SprutError,
    focusedBorderColor = SprutAccent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    errorBorderColor = SprutError,
    focusedLabelColor = SprutAccent,
    unfocusedLabelColor = SprutTextMuted,
    disabledLabelColor = SprutTextFaint,
    errorLabelColor = SprutError,
    focusedLeadingIconColor = SprutAccent,
    unfocusedLeadingIconColor = SprutTextMuted,
    disabledLeadingIconColor = SprutTextFaint,
    errorLeadingIconColor = SprutError,
    focusedSupportingTextColor = SprutTextMuted,
    unfocusedSupportingTextColor = SprutTextMuted,
    disabledSupportingTextColor = SprutTextFaint,
    errorSupportingTextColor = SprutError,
)
