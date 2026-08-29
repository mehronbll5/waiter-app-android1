package com.waiterapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = SurfaceWhite,
    primaryContainer = GreenContainer,
    onPrimaryContainer = GreenPrimaryDark,

    secondary = GreenSecondary,
    onSecondary = SurfaceWhite,
    secondaryContainer = GreenLight,
    onSecondaryContainer = GreenPrimaryDark,

    tertiary = GreenPrimaryDark,
    onTertiary = SurfaceWhite,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = GreenPrimaryDark,

    background = BackgroundGray,
    onBackground = TextDark,

    surface = SurfaceWhite,
    onSurface = TextDark,
    surfaceVariant = GreenLight,
    onSurfaceVariant = TextGray,

    outline = GreenBorder,
    outlineVariant = DividerGreenGray,

    error = RedBusy,
    onError = SurfaceWhite
)

// Единые скругления для карточек/кнопок/полей — более "премиальный" вид
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun WaiterAppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = view.context
        val activity = context as? Activity
        activity?.let {
            // Тёмная зелёная статус-бар в тон primary, светлые иконки
            it.window.statusBarColor = GreenPrimaryDark.toArgb()
            WindowCompat.getInsetsController(it.window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
