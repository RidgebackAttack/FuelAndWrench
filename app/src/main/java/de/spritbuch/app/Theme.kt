package de.spritbuch.app

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

/** Darstellungs-Einstellungen; wirken sofort, ohne Neustart. */
data class ThemeConfig(
    val mode: String = "system",      // system | light | dark
    val palette: String = "dynamic",  // dynamic | Paletten-Schlüssel
    val amoled: Boolean = false,
)

object ThemeSettings {
    val state = MutableStateFlow(ThemeConfig())

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)
        state.value = ThemeConfig(
            mode = sp.getString("themeMode", "system") ?: "system",
            palette = sp.getString("themePalette", if (Build.VERSION.SDK_INT >= 31) "dynamic" else "carbon") ?: "carbon",
            amoled = sp.getBoolean("themeAmoled", false),
        )
    }

    fun update(ctx: Context, cfg: ThemeConfig) {
        state.value = cfg
        ctx.getSharedPreferences("spritbuch", Context.MODE_PRIVATE).edit()
            .putString("themeMode", cfg.mode)
            .putString("themePalette", cfg.palette)
            .putBoolean("themeAmoled", cfg.amoled)
            .apply()
    }
}

/** Farbpalette: Hauptfarbe je für hell und dunkel. */
data class Palette(
    val key: String,
    val nameRes: Int,
    val swatch: Color,
    val lPrimary: Color, val lOnPrimary: Color, val lContainer: Color, val lOnContainer: Color,
    val dPrimary: Color, val dOnPrimary: Color, val dContainer: Color, val dOnContainer: Color,
    val accent: Color, val accentDark: Color,
)

val palettes = listOf(
    Palette(
        "carbon", R.string.pal_carbon, Color(0xFF1C4E8A),
        Color(0xFF1C4E8A), Color.White, Color(0xFFD6E3FF), Color(0xFF001B3E),
        Color(0xFFA9C7FF), Color(0xFF003063), Color(0xFF0F3C72), Color(0xFFD6E3FF),
        Color(0xFFB8741A), Color(0xFFF2B544),
    ),
    Palette(
        "racing", R.string.pal_racing, Color(0xFFB3261E),
        Color(0xFFB3261E), Color.White, Color(0xFFFFDAD6), Color(0xFF410002),
        Color(0xFFFFB4AB), Color(0xFF690005), Color(0xFF93000A), Color(0xFFFFDAD6),
        Color(0xFF5B6470), Color(0xFFBCC7D3),
    ),
    Palette(
        "brg", R.string.pal_brg, Color(0xFF1B5E35),
        Color(0xFF1B5E35), Color.White, Color(0xFFA6F5B5), Color(0xFF00210C),
        Color(0xFF8BD89B), Color(0xFF003919), Color(0xFF005228), Color(0xFFA6F5B5),
        Color(0xFF8A6A1F), Color(0xFFE5C26B),
    ),
    Palette(
        "orange", R.string.pal_orange, Color(0xFFE06A00),
        Color(0xFF9A4600), Color.White, Color(0xFFFFDBC8), Color(0xFF321300),
        Color(0xFFFFB68A), Color(0xFF532200), Color(0xFF763300), Color(0xFFFFDBC8),
        Color(0xFF3F5F7A), Color(0xFFA7C8E6),
    ),
    Palette(
        "petrol", R.string.pal_petrol, Color(0xFF006A6A),
        Color(0xFF006A6A), Color.White, Color(0xFF6FF7F6), Color(0xFF002020),
        Color(0xFF4CDADA), Color(0xFF003737), Color(0xFF004F4F), Color(0xFF6FF7F6),
        Color(0xFF7A5900), Color(0xFFF5BF48),
    ),
    Palette(
        "graphite", R.string.pal_graphite, Color(0xFF46505C),
        Color(0xFF46505C), Color.White, Color(0xFFDDE3EC), Color(0xFF121C26),
        Color(0xFFBCC7D3), Color(0xFF26313C), Color(0xFF3C4753), Color(0xFFDDE3EC),
        Color(0xFFB3261E), Color(0xFFFFB4AB),
    ),
)

private fun schemeFor(p: Palette, dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = p.dPrimary, onPrimary = p.dOnPrimary,
        primaryContainer = p.dContainer, onPrimaryContainer = p.dOnContainer,
        secondary = p.dPrimary, onSecondary = p.dOnPrimary,
        secondaryContainer = p.dContainer, onSecondaryContainer = p.dOnContainer,
        tertiary = p.accentDark, tertiaryContainer = p.accent,
    )
} else {
    lightColorScheme(
        primary = p.lPrimary, onPrimary = p.lOnPrimary,
        primaryContainer = p.lContainer, onPrimaryContainer = p.lOnContainer,
        secondary = p.lPrimary, onSecondary = p.lOnPrimary,
        secondaryContainer = p.lContainer, onSecondaryContainer = p.lOnContainer,
        tertiary = p.accent, tertiaryContainer = p.accentDark,
    )
}

/** Reines Schwarz für OLED-Displays (spart Akku, maximaler Kontrast). */
private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceVariant = Color(0xFF1C1C1C),
)

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val cfg by ThemeSettings.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val dark = when (cfg.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    var scheme = when {
        cfg.palette == "dynamic" && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> schemeFor(palettes.firstOrNull { it.key == cfg.palette } ?: palettes[0], dark)
    }
    if (dark && cfg.amoled) scheme = scheme.amoled()

    // Statusleiste/Navigationsleiste passend zum App-Design (nicht nur zum System)
    val activity = ctx as? ComponentActivity
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    MaterialTheme(colorScheme = scheme, shapes = appShapes, content = content)
}

/** Auswahl der Farbpalette als Farbkreise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PalettePicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (Build.VERSION.SDK_INT >= 31) {
            val ctx = LocalContext.current
            val dyn = dynamicLightColorScheme(ctx).primary
            Swatch(str(R.string.pal_system), dyn, selected == "dynamic", dynamic = true) { onSelect("dynamic") }
        }
        palettes.forEach { p -> Swatch(str(p.nameRes), p.swatch, selected == p.key) { onSelect(p.key) } }
    }
}

@Composable
private fun Swatch(name: String, color: Color, selected: Boolean, dynamic: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(color)
                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val onColor = if ((color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f) > 0.6f) Color.Black else Color.White
            when {
                selected -> Icon(Icons.Default.Check, contentDescription = null, tint = onColor)
                dynamic -> Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = onColor)
            }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Suppress("unused")
private fun Color.argb() = toArgb()
