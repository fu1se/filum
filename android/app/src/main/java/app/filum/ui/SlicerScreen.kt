package app.filum.ui

import app.filum.tr
import app.filum.R
import app.filum.data.CfsSlot
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filum.SlicerViewModel
import app.filum.data.PresetItem
import app.filum.data.SliceJob
import app.filum.data.SliceResult
import app.filum.data.SlicerModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что открыто в полноэкранном предпросмотре. */
sealed interface PreviewTarget {
    data class Model(val model: SlicerModel) : PreviewTarget
    data class Gcode(val job: SliceJob, val result: SliceResult, val forPrint: Boolean) : PreviewTarget
}

@Composable
fun SlicerScreen(vm: SlicerViewModel, printerConnected: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    var showMakerWorld by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    var editSlot by remember { mutableStateOf<CfsSlot?>(null) }

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    LaunchedEffect(ui.message) {
        ui.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeMessage() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importUri(it) }
    }

    preview?.let { target ->
        PreviewPanel(vm, target, printerConnected, onClose = { preview = null })
        return
    }
    if (showMakerWorld) {
        MakerWorldPanel(vm, onClose = { showMakerWorld = false })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ui.presetsError?.let { err ->
            Section(tr(R.string.slicer_unavailable)) {
                Text(err, color = MaterialTheme.colorScheme.error)
                Text(
                    tr(R.string.slicer_check_url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { vm.reload() }) { Text(tr(R.string.retry)) }
            }
        }

        // ---------- модель ----------
        Section(tr(R.string.model)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.file))
                }
                FilledTonalButton(onClick = { showMakerWorld = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Public, null); Spacer(Modifier.width(6.dp)); Text("MakerWorld")
                }
            }
            ui.uploading?.let {
                Text(tr(R.string.uploading_pct, (it * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), trackColor = TrackColor)
            }
            ui.model?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(vm.api.modelThumbUrl(m), Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Fit)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(m.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (m.source == "makerworld") "MakerWorld" + (m.author?.let { " · $it" } ?: "") else tr(R.string.file_lower),
                                tr(R.string.size_mb).format(m.size / 1048576.0),
                                m.slots.size.takeIf { it > 1 }?.let { tr(R.string.n_colors, it) },
                                m.plates.size.takeIf { it > 1 }?.let { tr(R.string.n_plates, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { preview = PreviewTarget.Model(m) }) {
                        Icon(Icons.Default.ViewInAr, null); Spacer(Modifier.width(4.dp)); Text("3D")
                    }
                }
                if (m.plates.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton(tr(R.string.all), selected = ui.plate == 0) { vm.setPlate(0) }
                        m.plates.forEach { p -> ChipButton(tr(R.string.plate_n, p), selected = ui.plate == p) { vm.setPlate(p) } }
                    }
                }
            } ?: Text(
                tr(R.string.slicer_empty_1) +
                    tr(R.string.slicer_empty_2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.recent.isNotEmpty()) {
                Text(tr(R.string.recent), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.recent.take(20), key = { it.id }) { m ->
                        Column(
                            Modifier.width(84.dp).clip(RoundedCornerShape(10.dp))
                                .then(if (ui.model?.id == m.id) Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                                .clickable { vm.selectModel(m) },
                        ) {
                            RemoteImage(vm.api.modelThumbUrl(m), Modifier.size(84.dp), ContentScale.Fit)
                            Text(m.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }

        // ---------- настройки ----------
        ui.presets?.let { p ->
            Section(tr(R.string.settings)) {
                // orca-sync необязателен: без него строки нет вовсе
                if (p.syncConfigured) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (p.syncOk) tr(R.string.orca_sync_presets, p.syncCount) else tr(R.string.orca_sync_status, p.syncError ?: tr(R.string.error_lower)),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.syncOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    IconButton(onClick = { vm.loadPresets(ui.printer, refreshSync = true) }) {
                        Icon(Icons.Default.Refresh, tr(R.string.refresh_presets))
                    }
                }
                PresetField(tr(R.string.printer), ui.printer, p.printers) { vm.selectPrinter(it) }
                ui.nozzleMismatch?.let { have ->
                    Text(tr(R.string.nozzle_mismatch).format(have, ui.nozzle),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                PresetField(tr(R.string.process), ui.process, p.processes) { vm.selectProcess(it) }
                val slots = ui.model?.slots ?: listOf(null)
                val spools = ui.cfs.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (slots.size > 1) tr(R.string.colors_and_spools) else tr(R.string.cfs_spool),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { vm.loadCfs() }) { Icon(Icons.Default.Refresh, tr(R.string.refresh_cfs)) }
                }
                when {
                    ui.cfs == null -> Text(tr(R.string.cfs_loading), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    spools.none { !it.empty } -> Text(
                        ui.cfsError?.let { tr(R.string.cfs_unavailable_reason, it) } ?: tr(R.string.cfs_no_spools),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ui.filaments.forEachIndexed { i, f ->
                    val slot = slots.getOrNull(i)
                    if (spools.any { !it.empty }) {
                        CfsAssignRow(i, ui.filaments.size, slot, spools.firstOrNull { it.id == ui.cfsAssign.getOrNull(i) }) {
                            pickFor = i
                        }
                    }
                    PresetField(
                        if (slots.size > 1) tr(R.string.filament_profile_n, i + 1) + (slot?.type?.let { " ($it)" } ?: "") else tr(R.string.filament_profile),
                        f, p.filaments,
                        swatch = spools.firstOrNull { it.id == ui.cfsAssign.getOrNull(i) }?.color ?: slot?.color,
                    ) { vm.selectFilament(i, it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.auto_arrange))
                        Text(tr(R.string.auto_arrange_desc, p.bedW.toInt(), p.bedH.toInt()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ui.arrange, onCheckedChange = { vm.setArrange(it) })
                }
            }
        }

        Button(
            onClick = { vm.slice() },
            enabled = ui.model != null && ui.presets != null && !ui.busy && ui.job?.busy != true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(tr(R.string.slice), fontWeight = FontWeight.SemiBold) }

        // ---------- текущее задание ----------
        ui.job?.let { j ->
            Section(j.modelName) {
                when (j.state) {
                    "queued", "slicing" -> {
                        Text(if (j.state == "queued") tr(R.string.queued, if (j.queuePosition > 0) " (${j.queuePosition})" else "")
                             else tr(R.string.slicing))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = TrackColor)
                    }
                    "error" -> Text(tr(R.string.log_error, j.error), color = MaterialTheme.colorScheme.error)
                    else -> Text(
                        tr(R.string.sliced_in, j.sliceSeconds?.let { "%.1f".format(it) } ?: "?", j.process ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                j.results.forEach { r ->
                    ResultCard(vm, j, r, printerConnected,
                        onPreview = { preview = PreviewTarget.Gcode(j, r, forPrint = false) },
                        onPrint = { preview = PreviewTarget.Gcode(j, r, forPrint = true) })
                }
            }
        }

        // ---------- история ----------
        val history = ui.history.filter { it.id != ui.job?.id && it.state == "done" }
        if (history.isNotEmpty()) {
            Section(tr(R.string.history)) {
                history.take(15).forEach { j ->
                    val r = j.results.firstOrNull()
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openJob(j.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(r?.let { vm.api.jobThumbUrl(j.id, it.plate) }, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Fit)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(j.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(fmtDate(j.created), r?.time?.let(::fmtOrcaTime), r?.grams?.let { tr(R.string.grams, it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (j.results.any { it.printing }) Icon(Icons.Default.Print, tr(R.string.printing_lower), tint = Accent)
                        else if (j.results.any { it.uploaded }) Icon(Icons.Default.CloudUpload, tr(R.string.on_printer),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    pickFor?.let { i ->
        CfsPickerDialog(
            title = if ((ui.model?.slots?.size ?: 1) > 1) tr(R.string.spool_for_color, i + 1) else tr(R.string.spool_for_print),
            spools = ui.cfs.orEmpty(),
            selected = ui.cfsAssign.getOrNull(i),
            usedElsewhere = ui.cfsAssign.filterIndexed { k, id -> k != i && id != null }.filterNotNull().toSet(),
            onPick = { vm.assignSpool(i, it) },
            onEdit = { editSlot = it },
            onDismiss = { pickFor = null },
        )
    }

    editSlot?.let { s ->
        CfsEditDialog(
            slot = s, library = ui.library, saving = ui.cfsSaving,
            onLoadLibrary = vm::loadLibrary,
            onSave = { m, c, mn, mx, pa -> vm.saveSlot(s, m, c, mn, mx, pa) { editSlot = null } },
            onDismiss = { editSlot = null },
        )
    }

}

@Composable
private fun ResultCard(
    vm: SlicerViewModel, job: SliceJob, r: SliceResult, printerConnected: Boolean,
    onPreview: () -> Unit, onPrint: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(vm.api.jobThumbUrl(job.id, r.plate), Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Fit)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(r.file, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                        InfoCell(tr(R.string.time), r.time?.let(::fmtOrcaTime) ?: "—")
                        InfoCell(tr(R.string.filament), r.grams?.let { tr(R.string.grams, it) } ?: "—")
                        r.layers?.let { InfoCell(tr(R.string.layers), it) }
                    }
                }
            }
            when {
                r.printing -> Text(tr(R.string.sent_and_started), color = Color(0xFF4CAF50))
                r.uploaded -> Text(tr(R.string.uploaded_check), color = Color(0xFF4CAF50))
            }
            r.uploadError?.let { Text(tr(R.string.send_failed, it), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f)) { Text(tr(R.string.preview)) }
                if (!r.uploaded) {
                    OutlinedButton(onClick = { vm.sendToPrinter(job.id, r.plate, start = false) }, modifier = Modifier.weight(1f)) {
                        Text(tr(R.string.to_printer))
                    }
                }
            }
            if (!r.printing) {
                Button(onClick = onPrint, modifier = Modifier.fillMaxWidth(), enabled = printerConnected) {
                    Icon(Icons.Default.Print, null); Spacer(Modifier.width(6.dp))
                    Text(if (printerConnected) tr(R.string.printing_ellipsis) else tr(R.string.print_not_connected))
                }
            }
        }
    }
}

/** Поле выбора пресета: открывает список с поиском (пластиков бывает сотня). */
@Composable
private fun PresetField(label: String, value: String?, items: List<PresetItem>, swatch: String? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            swatch?.let { ColorSwatch(it); Spacer(Modifier.width(8.dp)) }
            val user = items.firstOrNull { it.name == value }?.user == true
            Text((if (user) "★ " else "") + (value ?: "—"), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val filtered = items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    if (items.size > 8) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text(tr(R.string.search)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.size(8.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.name }) { item ->
                            Text(
                                (if (item.user) "★ " else "") + item.name,
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (item.name == value) Accent.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { onSelect(item.name); open = false }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                color = if (item.name == value) Accent else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Text(tr(R.string.synced_presets_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(tr(R.string.close)) } },
        )
    }
}

@Composable
fun ColorSwatch(hex: String, size: Int = 18) {
    val c = runCatching { Color(android.graphics.Color.parseColor(hex.take(7))) }.getOrDefault(Color.Gray)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(5.dp)).background(c).border(1.dp, Color(0x55000000), RoundedCornerShape(5.dp)))
}

/** "1h 38m 12s" → "1 ч 38 мин". */
fun fmtOrcaTime(t: String): String {
    val parts = Regex("(\\d+)([dhms])").findAll(t).map { it.groupValues[1] to it.groupValues[2] }.toList()
    val units = mapOf("d" to tr(R.string.unit_day), "h" to tr(R.string.unit_hour), "m" to tr(R.string.unit_min), "s" to tr(R.string.unit_sec))
    val keep = if (parts.size > 1) parts.filter { it.second != "s" } else parts
    return keep.joinToString(" ") { "${it.first} ${units[it.second]}" }.ifBlank { t }
}

private fun fmtDate(epoch: Double): String =
    SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date((epoch * 1000).toLong()))
