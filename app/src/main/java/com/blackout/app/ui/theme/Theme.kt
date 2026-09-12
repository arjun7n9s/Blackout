package com.blackout.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BlackoutColors = darkColorScheme(
    primary = BlackoutViolet,
    onPrimary = BlackoutOnViolet,
    primaryContainer = BlackoutVioletDeep,
    onPrimaryContainer = BlackoutOnInk,
    background = BlackoutInk,
    onBackground = BlackoutOnInk,
    surface = BlackoutSurface,
    onSurface = BlackoutOnInk,
    surfaceVariant = BlackoutSurfaceHigh,
    onSurfaceVariant = BlackoutMuted,
    outline = BlackoutOutline,
    error = BlackoutDanger,
)

private val BlackoutTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

/**
 * Deliberately always dark - [isSystemInDarkTheme] is ignored for v0 so the camera and the
 * redaction preview read consistently regardless of the phone's theme.
 */
@Composable
fun BlackoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackoutColors,
        typography = BlackoutTypography,
        content = content,
    )
}
