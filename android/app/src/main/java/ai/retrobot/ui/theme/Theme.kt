package ai.retrobot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val RetroDark = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF06202B),
    primaryContainer = Color(0xFF12414F),
    onPrimaryContainer = Color(0xFFBDE6FF),
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF161E4B),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF00382A),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF1B1F27),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF2B3140),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
)

private val RetroLight = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9FF),
    onPrimaryContainer = Color(0xFF06202B),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF12141A),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF505A6B),
    outline = Color(0xFFC7CDD8),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun RetroBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) RetroDark else RetroLight
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RetroTypography,
        content = content,
    )
}

/** Unwrap the Activity behind a (possibly wrapped) Context. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
