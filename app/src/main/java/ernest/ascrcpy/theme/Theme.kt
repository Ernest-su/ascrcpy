package ernest.ascrcpy.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
  primary = WeChatBrand, onPrimary = Color.White,
  primaryContainer = Color(0xFFB4ECCE), onPrimaryContainer = Color(0xFF035D2F),
  secondary = WeChatLink, onSecondary = Color.White,
  tertiary = WeChatWarning, error = WeChatDanger, onError = Color.White,
  background = WeChatLightBackground, onBackground = WeChatLightText,
  surface = WeChatLightSurface, onSurface = WeChatLightText,
  surfaceVariant = WeChatLightSurfaceSubtle, onSurfaceVariant = WeChatLightTextSecondary,
  surfaceContainer = WeChatLightSurface, surfaceContainerLow = WeChatLightSurfaceSubtle,
  outline = Color(0xFFB2B2B2), outlineVariant = WeChatLightDivider,
)

private val DarkColorScheme = darkColorScheme(
  primary = WeChatBrand, onPrimary = Color.White,
  primaryContainer = Color(0xFF023A1C), onPrimaryContainer = Color(0xFFB4ECCE),
  secondary = Color(0xFF7D90A9), onSecondary = Color.White,
  tertiary = Color(0xFFC87D2F), error = WeChatDanger, onError = Color.White,
  background = WeChatDarkBackground, onBackground = WeChatDarkText,
  surface = WeChatDarkSurface, onSurface = WeChatDarkText,
  surfaceVariant = WeChatDarkSurfaceSubtle, onSurfaceVariant = WeChatDarkTextSecondary,
  surfaceContainer = WeChatDarkSurface, surfaceContainerLow = WeChatDarkSurfaceSubtle,
  outline = Color(0xFF595959), outlineVariant = WeChatDarkDivider,
)

private val WeChatShapes = Shapes(
  extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
  extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AScrcpyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    typography = Typography,
    shapes = WeChatShapes,
    content = content,
  )
}
