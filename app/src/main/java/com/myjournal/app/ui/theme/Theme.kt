package com.myjournal.app.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
val WarmSandBackground = Color(0xFFEDE6DC)
val SoftCreamSurface = Color(0xFFF7F3EB)
val MutedBeigeVariant = Color(0xFFE5DDD0)
val WarmOchrePrimary = Color(0xFFD49B42)
val TerracottaSecondary = Color(0xFF7B4B2A)
val DeepEspressoText = Color(0xFF1E1B18)
val WarmTaupeText = Color(0xFF6E655C)
val DarkBackground = Color(0xFF141210)
val DarkSurface = Color(0xFF1E1B18)
val DarkVariant = Color(0xFF2B2621)
val DarkTextPrimary = Color(0xFFF7F3EB)
val DarkTextSecondary = Color(0xFFB3A89E)
private val LightColorScheme = lightColorScheme(
    primary = WarmOchrePrimary,
    onPrimary = Color.White,
    secondary = TerracottaSecondary,
    background = WarmSandBackground,
    onBackground = DeepEspressoText,
    surface = SoftCreamSurface,
    onSurface = DeepEspressoText,
    surfaceVariant = MutedBeigeVariant,
    onSurfaceVariant = WarmTaupeText
)
private val DarkColorScheme = darkColorScheme(
    primary = WarmOchrePrimary,
    onPrimary = Color.Black,
    secondary = WarmOchrePrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkVariant,
    onSurfaceVariant = DarkTextSecondary
)
@Composable
fun MyJournalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}