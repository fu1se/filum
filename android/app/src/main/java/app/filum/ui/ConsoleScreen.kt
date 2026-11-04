package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.filum.data.LogLine
import app.filum.data.PrinterClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConsoleScreen(log: List<LogLine>, connected: Boolean, client: PrinterClient, device: Boolean = false) {
    var input by rememberSaveable { mutableStateOf("") }
    var verbose by remember { mutableStateOf(client.verboseLog) }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // докручиваем к новым строкам, только если пользователь и так внизу — иначе не мешаем читать
    LaunchedEffect(log.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (log.isNotEmpty() && last >= log.size - 4) listState.animateScrollToItem(log.lastIndex)
    }

    fun send() {
        val cmd = input.trim()
        if (cmd.isNotEmpty()) { client.gcode(cmd); input = "" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.all_messages), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Switch(checked = verbose, onCheckedChange = { verbose = it; client.verboseLog = it })
            IconButton(onClick = { client.clearLog() }) { Icon(Icons.Default.DeleteSweep, tr(R.string.clear)) }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = listState) {
            items(log) { l ->
                Text(
                    "${timeFmt.format(Date(l.time))} ${l.dir} ${l.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when (l.dir) {
                        '>' -> Accent
                        '!' -> Color(0xFF9E9E9E)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        HorizontalDivider()
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // у GRBL свои команды; RESTART-подобных среди них нет
            (if (device) listOf("\$H", "\$X", "\$\$", "\$#", "G0 X0 Y0", "M5")
            else listOf("G28", "M84", "BED_MESH_CALIBRATE", "SAVE_CONFIG", "FIRMWARE_RESTART")).forEach { c ->
                ChipButton(c, enabled = connected) { input = c }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(tr(R.string.gcode_command)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = { send() }, enabled = connected && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, tr(R.string.send))
            }
        }
    }
}
