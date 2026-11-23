package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.model.AppThemeMode

private val ShadoraDarkColorScheme = darkColorScheme(
    primary = NeonCrimson,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainerDark,
    onPrimaryContainer = NeonCrimsonGlow,
    secondary = BrushedSilver,
    onSecondary = ObsidianBlack,
    secondaryContainer = GraphiteElevated,
    onSecondaryContainer = MetallicSilver,
    tertiary = EmeraldSignal,
    onTertiary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = MetallicSilver,
    surface = CarbonSurface,
    onSurface = MetallicSilver,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = BrushedSilver,
    outline = SlateBorder,
    outlineVariant = Color(0xFF1F2330),
    error = ErrorRed,
    onError = Color.White
)

private val ShadoraLightColorScheme = lightColorScheme(
    primary = CrimsonLightPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainerLight,
    onPrimaryContainer = CrimsonDeep,
    secondary = InkMutedLight,
    onSecondary = Color.White,
    secondaryContainer = SilverElevatedLight,
    onSecondaryContainer = InkObsidianLight,
    tertiary = Color(0xFF00A854),
    onTertiary = Color.White,
    background = SilverCanvasLight,
    onBackground = InkObsidianLight,
    surface = SilverSurfaceLight,
    onSurface = InkObsidianLight,
    surfaceVariant = SilverElevatedLight,
    onSurfaceVariant = InkMutedLight,
    outline = SilverBorderLight,
    outlineVariant = Color(0xFFDDE2EC),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

val ShadoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ShadoraTheme(
    themeMode: AppThemeMode = AppThemeMode.OBSIDIAN_DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.OBSIDIAN_DARK -> true
        AppThemeMode.SILVER_LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) ShadoraDarkColorScheme else ShadoraLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShadoraTypography,
        shapes = ShadoraShapes,
        content = content
    )
}
