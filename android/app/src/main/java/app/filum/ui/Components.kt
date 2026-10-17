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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

val Accent = Color(0xFFFF8A3D)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Color(0xFF4FC3F7),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E2329),
    surfaceContainer = Color(0xFF1A1E23),
    surfaceContainerHighest = Color(0xFF1E2329),
    error = Color(0xFFFF5252),
)

@Composable
fun K2Theme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

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
