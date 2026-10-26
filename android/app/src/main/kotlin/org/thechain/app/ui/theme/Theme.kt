package org.thechain.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import org.thechain.app.R

val Cement = Color(0xFFF7F6F2)
val CardWhite = Color(0xFFFFFFFF)
val CardBorder = Color(0xFFDDE2DF)
val Teal = Color(0xFF005F60)
val TealPressed = Color(0xFF0B4F50)
val Ink = Color(0xFF1A1C1E)
val Muted = Color(0xFF49454F)
val Amber = Color(0xFFB36B00)
val AmberBg = Color(0xFFFEF3C7)
val Brick = Color(0xFF9E2A2B)
val Verified = Color(0xFF1E6B47)
val VerifiedBg = Color(0xFFE5F4EC)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6EFF0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF895100),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEA946),
    surface = Cement,
    onSurface = Ink,
    surfaceVariant = CardWhite,
    onSurfaceVariant = Muted,
    outline = CardBorder,
    error = Brick,
    onError = Color.White,
    background = Cement,
    onBackground = Ink,
)

private fun chainFace(context: Context): Typeface {
    val latin = R.font.noto_sans_regular
    val deva = R.font.noto_sans_devanagari_regular
    if (Build.VERSION.SDK_INT >= 29) {
        val latinFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(context.resources, latin).build(),
        ).build()
        val devaFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(context.resources, deva).build(),
        ).build()
        return Typeface.CustomFallbackBuilder(latinFamily).addCustomFallback(devaFamily).build()
    }
    return ResourcesCompat.getFont(context, latin) ?: Typeface.SANS_SERIF
}

@Composable
fun TheChainTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fonts = remember(context) { FontFamily(chainFace(context)) }
    val typography = remember(fonts) {
        Typography(
            headlineLarge = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, color = Ink),
            headlineMedium = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, color = Ink),
            titleLarge = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
            bodyLarge = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp, color = Ink),
            bodyMedium = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
            labelLarge = TextStyle(fontFamily = fonts, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = fonts, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        )
    }
    MaterialTheme(
        colorScheme = LightColors,
        typography = typography,
        content = content,
    )
}
