package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Фирменный бирюзовый (цвет иконки); в тёмной теме — светлее, в светлой — темнее. */
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DD9C1),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFF9DF4E2),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF2A4A45),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFFFB46B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    surfaceTint = Color(0xFF3DD9C1),
    background = Color(0xFF0F1415),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0F1415),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF1B2425),
    onSurfaceVariant = Color(0xFFA9B6B3),
    surfaceContainerLowest = Color(0xFF0A0F10),
    surfaceContainerLow = Color(0xFF151C1D),
    surfaceContainer = Color(0xFF172021),
    surfaceContainerHigh = Color(0xFF1D2728),
    surfaceContainerHighest = Color(0xFF222D2E),
    outline = Color(0xFF6F7C7A),
    outlineVariant = Color(0xFF3B4746),
    error = Color(0xFFFF6B5E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFFA65200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF331200),
    surfaceTint = Color(0xFF00796E),
    background = Color(0xFFF5FAF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF5FAF9),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9F0EE),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE5E3),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A),
)

@Composable
fun K2Theme(dark: Boolean, content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val window = (view.context as android.app.Activity).window
        val bg = (if (dark) DarkColors else LightColors).background.toArgb()
        @Suppress("DEPRECATION") run { window.statusBarColor = bg; window.navigationBarColor = bg }
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

fun fmtTemp(v: Double?) = v?.let { "%.1f°".format(it) } ?: "—"

fun fmtDuration(sec: Long?): String {
    if (sec == null || sec < 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) tr(R.string.duration_h_min, h, m) else tr(R.string.duration_min_s, m, sec % 60)
}

/** Строка нагревателя: текущая/целевая температура, поле ввода, «Уст.» и «Выкл». */
@Composable
fun HeaterRow(
    label: String,
    current: Double?,
    target: Double?,
    max: Int,
    enabled: Boolean,
    onSet: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val value = text.toIntOrNull()
    val valid = value != null && value in 0..max
    fun submit() {
        if (valid) { onSet(value!!); text = ""; focus.clearFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                "${fmtTemp(current)} / ${fmtTemp(target)}",
                style = MaterialTheme.typography.titleLarge,
                color = if ((target ?: 0.0) > 0) Accent else MaterialTheme.colorScheme.onSurface,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { s -> text = s.filter { it.isDigit() }.take(3) },
            modifier = Modifier.width(84.dp),
            singleLine = true,
            placeholder = { Text("°C") },
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.width(4.dp))
        Column {
            TextButton(onClick = { submit() }, enabled = enabled && valid) { Text(tr(R.string.set)) }
            TextButton(onClick = { onSet(0) }, enabled = enabled) { Text(tr(R.string.off)) }
        }
    }
}

/** Слайдер процентов: показывает значение принтера, пока его не тянут, и отправляет по отпусканию. */
@Composable
fun PercentSlider(
    label: String,
    value: Int?,
    range: IntRange,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) local.roundToInt() else value
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(shown?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = if (dragging) local else (value ?: range.first).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onCommit(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
fun ChipButton(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (selected) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = Accent.copy(alpha = 0.18f), contentColor = Accent
        ) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text) }
}
