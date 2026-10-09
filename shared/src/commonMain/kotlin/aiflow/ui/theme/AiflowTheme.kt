package aiflow.ui.theme

import aiflow.storage.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Light = lightColorScheme(
    primary = Color(0xff276a60), onPrimary = Color.White,
    primaryContainer = Color(0xffaaeee0), onPrimaryContainer = Color(0xff00201b),
    secondary = Color(0xff4a635c), onSecondary = Color.White,
    secondaryContainer = Color(0xffd1e8e1), onSecondaryContainer = Color(0xff0b211c),
    tertiary = Color(0xff795900), onTertiary = Color.White,
    tertiaryContainer = Color(0xfffff0c4), onTertiaryContainer = Color(0xff251a00),
    error = Color(0xffba1a1a), onError = Color.White,
    errorContainer = Color(0xffffdad6), onErrorContainer = Color(0xff410002),
    background = Color(0xfff8faf8), onBackground = Color(0xff18201d),
    surface = Color(0xfff8faf8), onSurface = Color(0xff18201d),
    surfaceVariant = Color(0xffdae2dd), onSurfaceVariant = Color(0xff414a45),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff0f4f1),
    surfaceContainer = Color(0xffeaf0ec), surfaceContainerHigh = Color(0xffe2e9e5),
    surfaceContainerHighest = Color(0xffdae2dd), surfaceBright = Color(0xfff8faf8), surfaceDim = Color(0xffd7dfd9),
    outline = Color(0xff717c75), outlineVariant = Color(0xffbec9c2),
    inverseSurface = Color(0xff2c342f), inverseOnSurface = Color(0xffedf3ef), inversePrimary = Color(0xff8ed2c4),
    surfaceTint = Color(0xff276a60), scrim = Color.Black,
)
internal val Dark = darkColorScheme(
    primary = Color(0xff8ed2c4), onPrimary = Color(0xff00382f),
    primaryContainer = Color(0xff0b5147), onPrimaryContainer = Color(0xffaaeee0),
    secondary = Color(0xffb5cdc4), onSecondary = Color(0xff20372e),
    secondaryContainer = Color(0xff354b45), onSecondaryContainer = Color(0xffd1e8e1),
    tertiary = Color(0xffffd977), onTertiary = Color(0xff3f2e00),
    tertiaryContainer = Color(0xff5a4300), onTertiaryContainer = Color(0xfffff0c4),
    error = Color(0xffffb4ab), onError = Color(0xff690005),
    errorContainer = Color(0xff93000a), onErrorContainer = Color(0xffffdad6),
    background = Color(0xff111512), onBackground = Color(0xffe0e8e2),
    surface = Color(0xff111512), onSurface = Color(0xffe0e8e2),
    surfaceVariant = Color(0xff323c35), onSurfaceVariant = Color(0xffbec9c2),
    surfaceContainerLowest = Color(0xff0b100d), surfaceContainerLow = Color(0xff191e1b),
    surfaceContainer = Color(0xff1d2420), surfaceContainerHigh = Color(0xff27302a),
    surfaceContainerHighest = Color(0xff323c35), surfaceBright = Color(0xff363f38), surfaceDim = Color(0xff111512),
    outline = Color(0xff89958c), outlineVariant = Color(0xff414a45),
    inverseSurface = Color(0xffe0e8e2), inverseOnSurface = Color(0xff2b332e), inversePrimary = Color(0xff276a60),
    surfaceTint = Color(0xff8ed2c4), scrim = Color.Black,
)
data class StatusColors(val success: Color, val warning: Color, val comment: Color, val string: Color)
val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xff276a60), Color(0xff795900), Color(0xff456950), Color(0xff855400)) }
@Composable
fun AiflowTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when(mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val status = if (dark) StatusColors(Color(0xff8ed2c4), Color(0xffffd977), Color(0xffa7c8ad), Color(0xffffcb88)) else StatusColors(Color(0xff276a60), Color(0xff795900), Color(0xff456950), Color(0xff855400))
    val type = Typography().let { it.copy(bodyLarge = it.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp), bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)) }
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)), content = content)
    }
}
