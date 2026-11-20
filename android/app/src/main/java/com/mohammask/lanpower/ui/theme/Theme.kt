package com.mohammask.lanpower.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class StatusColorToken(
    val dot: Color,
    val content: Color,
    val container: Color,
)

@Immutable
data class ActionColorToken(
    val container: Color,
    val content: Color,
)

@Immutable
data class LanPowerStatusColors(
    val online: StatusColorToken,
    val offline: StatusColorToken,
    val checking: StatusColorToken,
    val booting: StatusColorToken,
    val sleeping: StatusColorToken,
    val shuttingDown: StatusColorToken,
    val rebooting: StatusColorToken,
    val wakeAction: ActionColorToken,
    val sleepAction: ActionColorToken,
)

private val DarkStatusColors = LanPowerStatusColors(
    online = StatusColorToken(StatusOnlineDark, StatusOnlineDark, StatusOnlineContainerDark),
    offline = StatusColorToken(StatusOfflineDark, StatusOfflineDark, StatusOfflineContainerDark),
    checking = StatusColorToken(StatusCheckingDark, StatusCheckingDark, StatusCheckingContainerDark),
    booting = StatusColorToken(StatusBootingDark, StatusBootingDark, StatusBootingContainerDark),
    sleeping = StatusColorToken(StatusSleepingDark, StatusSleepingDark, StatusSleepingContainerDark),
    shuttingDown = StatusColorToken(StatusShuttingDownDark, StatusShuttingDownDark, StatusShuttingDownContainerDark),
    rebooting = StatusColorToken(StatusRebootingDark, StatusRebootingDark, StatusRebootingContainerDark),
    wakeAction = ActionColorToken(ActionWakeContainerDark, ActionWakeContentDark),
    sleepAction = ActionColorToken(ActionSleepContainerDark, ActionSleepContentDark),
)

private val LightStatusColors = LanPowerStatusColors(
    online = StatusColorToken(StatusOnlineLight, StatusOnlineLight, StatusOnlineContainerLight),
    offline = StatusColorToken(StatusOfflineLight, StatusOfflineLight, StatusOfflineContainerLight),
    checking = StatusColorToken(StatusCheckingLight, StatusCheckingLight, StatusCheckingContainerLight),
    booting = StatusColorToken(StatusBootingLight, StatusBootingLight, StatusBootingContainerLight),
    sleeping = StatusColorToken(StatusSleepingLight, StatusSleepingLight, StatusSleepingContainerLight),
    shuttingDown = StatusColorToken(StatusShuttingDownLight, StatusShuttingDownLight, StatusShuttingDownContainerLight),
    rebooting = StatusColorToken(StatusRebootingLight, StatusRebootingLight, StatusRebootingContainerLight),
    wakeAction = ActionColorToken(ActionWakeContainerLight, ActionWakeContentLight),
    sleepAction = ActionColorToken(ActionSleepContainerLight, ActionSleepContentLight),
)

val LocalLanPowerStatusColors = staticCompositionLocalOf { DarkStatusColors }

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = EmeraldOnPrimaryDark,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldOnContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = CyanTertiaryDark,
    onTertiary = CyanOnTertiaryDark,
    tertiaryContainer = CyanTertiaryContainerDark,
    onTertiaryContainer = CyanOnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerHighDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = EmeraldOnPrimaryLight,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = EmeraldOnContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = SlateOnSecondaryContainerLight,
    tertiary = CyanTertiaryLight,
    onTertiary = CyanOnTertiaryLight,
    tertiaryContainer = CyanTertiaryContainerLight,
    onTertiaryContainer = CyanOnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceContainerHighLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val LanPowerShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LanPowerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalLanPowerStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LanPowerTypography,
            shapes = LanPowerShapes,
            content = content,
        )
    }
}
