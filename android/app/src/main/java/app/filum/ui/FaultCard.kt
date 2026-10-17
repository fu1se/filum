package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.filum.data.ActiveFault
import app.filum.data.PrinterState

/**
 * Какую ошибку показать: текущую с экрана принтера (:9999) или из ответа G-code.
 * Код с экрана прошивка иногда не сбрасывает после устранения, поэтому во время
 * обычной печати показываем только свежие ошибки.
 */
fun pickFault(screen: ActiveFault?, gcode: ActiveFault?, state: PrinterState): ActiveFault? {
    val now = System.currentTimeMillis()
    fun relevant(f: ActiveFault?) = f != null && (!state.isPrinting || now - f.since < 60_000)
    if (relevant(screen)) {
        // у ответа G-code бывают уточнения (бокс и слот CFS) — берём их, если код тот же
        val extra = gcode?.fault?.takeIf { it.code == screen!!.fault.code }?.detail
        return if (extra != null) screen!!.copy(fault = screen.fault.copy(detail = extra)) else screen
    }
    return gcode.takeIf { relevant(it) && (state.isPaused || state.printState == "error") }
}

@Composable
fun FaultCard(f: ActiveFault, onDismiss: () -> Unit) {
    val fault = f.fault
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Icon(Icons.Default.Warning, null, Modifier.padding(top = 2.dp).size(22.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(fault.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                fault.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                fault.what?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                fault.todo?.let {
                    Text(tr(R.string.what_to_do), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (fault.code > 0) Text(tr(R.string.fault_code, fault.code),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top)) {
                Icon(Icons.Default.Close, tr(R.string.hide))
            }
        }
    }
}
