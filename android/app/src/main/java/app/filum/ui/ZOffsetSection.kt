package app.filum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.filum.R
import app.filum.data.PrinterClient
import app.filum.data.PrinterState
import app.filum.tr
import java.util.Locale

// + 0.0 убирает «-0.000» у отрицательного нуля
private fun mm(v: Double) = "%+.3f".format(Locale.US, v + 0.0)

/**
 * Режим эксперта: Z-offset. «+» — сопло выше от стола.
 * - сейчас = смещение с учётом подстройки: homing_origin.z − z_offset датчика;
 * - сохранено = −z_offset датчика (то, что останется после перезапуска и в следующих печатях).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZOffsetSection(state: PrinterState, connected: Boolean, client: PrinterClient) {
    val probe = state.probeZOffset
    val saved = probe?.let { -it }
    // Z сейчас считается от z_offset загруженного конфига; сохранённый новый вступит при следующем хоминге
    val base = state.cfgProbeZOffset ?: probe
    val current = if (base != null) (state.gcodeZOffset ?: 0.0) - base else state.gcodeZOffset
    var text by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    // пока пользователь не начал править — поле следует за текущим значением (подстройка кнопками)
    LaunchedEffect(current) { if (!edited && current != null) text = "%.3f".format(Locale.US, current) }
    var ask by remember { mutableStateOf<Double?>(null) }
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Section(tr(R.string.zoffset_title)) {
        Text(
            tr(R.string.zoffset_now, current?.let(::mm) ?: "—", saved?.let(::mm) ?: "—"),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-0.05, -0.01, 0.01, 0.05).forEach { d ->
                ChipButton("%+.2f".format(Locale.US, d), enabled = connected) { client.adjustZOffset(d) }
            }
        }
        Text(tr(R.string.zoffset_adjust_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (state.caps.zOffsetSave) {
            val value = text.replace(',', '.').toDoubleOrNull()
            val valid = value != null && value in -2.0..2.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> edited = true; text = v.filter { it.isDigit() || it in "-.," }.take(7) },
                    label = { Text(tr(R.string.zoffset_saved_label)) },
                    suffix = { Text(tr(R.string.mm_unit)) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = { ask = value },
                    enabled = connected && valid && !state.hasActiveJob && !saving,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(tr(R.string.save))
                }
            }
            Text(
                if (state.hasActiveJob) tr(R.string.zoffset_save_after_print) else tr(R.string.zoffset_save_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ask?.let { v ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(tr(R.string.zoffset_confirm_title, mm(v))) },
            text = { Text(tr(R.string.zoffset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    saving = true
                    client.saveZOffset(v) { err ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            saving = false
                            edited = false
                            android.widget.Toast.makeText(
                                context,
                                if (err == null) tr(R.string.zoffset_saved, mm(v)) else tr(R.string.zoffset_error, err),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) { Text(tr(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}
