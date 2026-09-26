package ernest.ascrcpy.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SystemFont = FontFamily.SansSerif

val Typography = Typography(
  headlineLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
  titleLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
  titleMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
  bodyLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
  bodyMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
  labelLarge = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
  labelMedium = TextStyle(fontFamily = SystemFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)
