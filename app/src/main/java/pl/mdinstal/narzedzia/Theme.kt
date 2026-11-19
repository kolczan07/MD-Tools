package pl.mdinstal.narzedzia

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    background = Color(0xFFF1F5F9), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0), onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8), outlineVariant = Color(0xFFD5DCE6),
    error = Color(0xFFDC2626)
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF3B82F6), onPrimary = Color.White,
    background = Color(0xFF0B1220), onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF162033), onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B), onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF64748B), outlineVariant = Color(0xFF2A3A55),
    error = Color(0xFFF87171)
)

/** Ciemny motyw z czystą czernią — oszczędza baterię na ekranach OLED. */
internal val OledColors = darkColorScheme(
    primary = Color(0xFF3B82F6), onPrimary = Color.White,
    background = Color.Black, onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0C0C0E), onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF151518), onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF5B6472), outlineVariant = Color(0xFF26262B),
    error = Color(0xFFF87171)
)

@Composable
fun AppTheme(dark: Boolean, oled: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark && oled) OledColors else if (dark) DarkColors else LightColors, content = content)
}
