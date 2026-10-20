package app.filum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.filum.R
import app.filum.data.PrinterClient
import app.filum.data.PrinterState
import app.filum.tr
import java.util.Locale

// + 0.0 убирает «-0.000» у отрицательного нуля
private fun mm(v: Double) = "%+.3f".format(Locale.US, v + 0.0)

/**
 * Режим эксперта: Z-offset на лету (SET_GCODE_OFFSET), «+» — сопло выше.
 *
 * Постоянное значение здесь не сохраняется: START_PRINT у K2 обнуляет смещение, а z_offset датчика перебивает
 * экран принтера. Надёжно — `SET_GCODE_OFFSET Z=…` после START_PRINT в стартовом G-code профиля принтера.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZOffsetSection(state: PrinterState, connected: Boolean, client: PrinterClient) {
    Section(tr(R.string.zoffset_title)) {
        Text(tr(R.string.zoffset_now, state.gcodeZOffset?.let(::mm) ?: "—"), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-0.05, -0.01, 0.01, 0.05).forEach { d ->
                ChipButton("%+.2f".format(Locale.US, d), enabled = connected) { client.adjustZOffset(d) }
            }
        }
        Text(tr(R.string.zoffset_adjust_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
