package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.filum.data.GcodeFile
import app.filum.data.PrinterClient
import app.filum.data.PrinterState

@Composable
fun FilesScreen(files: List<GcodeFile>?, state: PrinterState, connected: Boolean, client: PrinterClient) {
    var toPrint by remember { mutableStateOf<GcodeFile?>(null) }

    LaunchedEffect(connected) { if (connected && files == null) client.requestFiles() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                files?.let { tr(R.string.files_count, it.size) } ?: tr(R.string.files_no_list),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalButton(onClick = { client.requestFiles() }, enabled = connected) {
                Icon(Icons.Default.Refresh, null); Text(tr(R.string.refresh_spaced))
            }
        }
        HorizontalDivider()
        if (files.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (files == null) tr(R.string.files_hint_1) +
                        tr(R.string.files_hint_2)
                    else tr(R.string.files_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = f.size?.let { { Text(fmtSize(it)) } },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        modifier = Modifier.clickable(enabled = connected) { toPrint = f },
                    )
                }
            }
        }
    }

    toPrint?.let { f ->
        AlertDialog(
            onDismissRequest = { toPrint = null },
            title = { Text(tr(R.string.start_print_q)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name)
                    if (state.hasActiveJob) Text(tr(R.string.printer_busy), color = MaterialTheme.colorScheme.error)
                    Text(tr(R.string.bed_clear), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { client.printFile(f.path); toPrint = null }, enabled = !state.hasActiveJob) {
                    Text(tr(R.string.action_print))
                }
            },
            dismissButton = { TextButton(onClick = { toPrint = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> tr(R.string.size_mb).format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> tr(R.string.size_kb).format(bytes / 1024.0)
    else -> tr(R.string.size_b, bytes)
}
