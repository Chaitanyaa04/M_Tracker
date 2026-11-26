package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Immutable
data class LedgerColors(
    val bg: Color, val surface: Color, val border: Color,
    val text: Color, val muted: Color,
    val accent: Color, val onAccent: Color,
    val green: Color, val red: Color,
    val dark: Boolean = true
)

val OledColors = LedgerColors(
    bg = Color(0xFF000000), surface = Color(0xFF0D0D0D), border = Color(0xFF1F1F1F),
    text = Color(0xFFF5F5F5), muted = Color(0xFF888888),
    accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
    green = Color(0xFF4ADE80), red = Color(0xFFF87171)
)
val PaperColors = LedgerColors(
    bg = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), border = Color(0xFFE5E5E5),
    text = Color(0xFF111111), muted = Color(0xFF666666),
    accent = Color(0xFF000000), onAccent = Color(0xFFFFFFFF),
    green = Color(0xFF2E7D32), red = Color(0xFFB91C1C), dark = false
)

val LocalLedger = staticCompositionLocalOf { OledColors }
val LocalHaptics = compositionLocalOf { true }
val L: LedgerColors @Composable @ReadOnlyComposable get() = LocalLedger.current

@Composable
fun LedgerTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "OLED" -> true; "PAPER" -> false; else -> isSystemInDarkTheme() }
    val c = if (dark) OledColors else PaperColors
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.muted,
        outline = c.border, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.muted,
        outline = c.border, surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface
    )
    val r = RoundedCornerShape(6.dp)
    CompositionLocalProvider(LocalLedger provides c) {
        MaterialTheme(
            colorScheme = scheme, typography = LedgerTypography,
            shapes = Shapes(extraSmall = r, small = r, medium = r, large = r, extraLarge = r),
            content = content
        )
    }
}

// ---------- formatting ----------
fun inr(v: Double): String {
    val f = NumberFormat.getNumberInstance(Locale("en", "IN"))
    f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
    return "₹" + f.format(abs(v))
}
fun inrS(v: Double): String = (if (v < -0.004) "−" else "") + inr(v)

// ---------- primitives ----------
/** Understated uppercase tracked label. */
@Composable
fun Cap(t: String, modifier: Modifier = Modifier, color: Color = L.muted) =
    Text(t.uppercase(), modifier, color = color,
        style = TextStyle(fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"))

/** Any number/amount: tabular figures. */
@Composable
fun Num(t: String, modifier: Modifier = Modifier, size: TextUnit = 15.sp, color: Color = L.text, weight: FontWeight = FontWeight.Normal) =
    Text(t, modifier, color = color, style = TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum"))

@Composable
fun Hairline(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(L.border))

/** Springy press feedback, so every tap feels physical. */
@Composable
fun Modifier.tactile(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 600f), label = "press")
    return this.graphicsLayer { scaleX = sc; scaleY = sc }.clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** A soft rounded surface. Used to group rows instead of drawing lines everywhere. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(L.surface, lerp(L.surface, L.bg, 0.45f))))
            .border(1.dp, L.border, shape),
        content = content
    )
}

@Composable
fun MonoButton(t: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.tactile(onClick).clip(shape)
            .then(if (filled) Modifier.background(L.accent) else Modifier.border(1.dp, L.border, shape).background(L.surface))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(t.uppercase(), color = if (filled) L.onAccent else L.text,
            style = TextStyle(fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun Chip(t: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.tactile(onClick).clip(shape)
            .then(if (selected) Modifier.background(L.accent) else Modifier.border(1.dp, L.border, shape).background(L.surface))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(t, color = if (selected) L.onAccent else L.text, fontSize = 13.sp)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = L.text, fontSize = 14.sp)
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = L.accent, checkedThumbColor = L.onAccent, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = L.border, uncheckedThumbColor = L.muted, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    kb: KeyboardType = KeyboardType.Text, size: TextUnit = 16.sp
) {
    Column(modifier) {
        Cap(label)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = L.text, fontSize = size, fontFeatureSettings = "tnum"),
            cursorBrush = SolidColor(L.text),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
        )
        Hairline()
    }
}

@Composable
fun <T> Picker(label: String, value: String, options: List<Pair<String, T>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.clickable { expanded = true }) {
            if (label.isNotEmpty()) Cap(label)
            Text(value, color = L.text, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            Hairline()
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (n, v) ->
                DropdownMenuItem(text = { Text(n, color = L.text) }, onClick = { onPick(v); expanded = false })
            }
        }
    }
}

/** The real launcher icon of an installed app. */
@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(L.border), contentAlignment = Alignment.Center) {
        if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize())
        else Text("₹", color = L.muted, fontSize = 16.sp)
    }
}
