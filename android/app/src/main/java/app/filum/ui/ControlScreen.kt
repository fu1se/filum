package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filum.SlicerViewModel
import app.filum.data.CfsSlot
import app.filum.data.PrinterClient
import app.filum.data.PrinterState

private data class Preset(val name: String, val nozzle: Int, val bed: Int, val chamber: Int = 0)

private val presets = listOf(
    Preset("PLA", 210, 60),
    Preset("PETG", 240, 80),
    Preset("ABS", 260, 100, 45),
    Preset("ASA", 260, 100, 45),
    Preset("TPU", 225, 50),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(state: PrinterState, connected: Boolean, client: PrinterClient, slicerVm: SlicerViewModel) {
    val slicerUi by slicerVm.ui.collectAsStateWithLifecycle()
    var editSlot by remember { mutableStateOf<CfsSlot?>(null) }
    var tagFor by remember { mutableStateOf<TagPrefill?>(null) }
    var nozzleAsk by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { if (slicerUi.cfs == null) slicerVm.loadCfs() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(slicerUi.message) {
        slicerUi.message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); slicerVm.consumeMessage() }
    }
    var step by rememberSaveable { mutableDoubleStateOf(10.0) }
    var extrudeLen by rememberSaveable { mutableDoubleStateOf(10.0) }
    // Двигать оси во время печати опасно — блокируем перемещения и экструзию
    val canMove = connected && !state.isPrinting
    val caps = state.caps

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(tr(R.string.heating)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    ChipButton(p.name, enabled = connected) {
                        client.setNozzleTemp(p.nozzle)
                        client.setBedTemp(p.bed)
                        if (p.chamber > 0 && caps.chamberHeater) client.setChamberTemp(p.chamber)
                    }
                }
                ChipButton(tr(R.string.all_off), enabled = connected) {
                    client.setNozzleTemp(0); client.setBedTemp(0)
                    if (caps.chamberHeater) client.setChamberTemp(0)
                }
            }
            HeaterRow(tr(R.string.nozzle), state.nozzleTemp, state.nozzleTarget, 350, connected) { client.setNozzleTemp(it) }
            HeaterRow(tr(R.string.bed), state.bedTemp, state.bedTarget, 120, connected) { client.setBedTemp(it) }
            if (caps.chamberHeater) {
                HeaterRow(tr(R.string.chamber), state.chamberTemp, state.chamberTarget, 60, connected) { client.setChamberTemp(it) }
            }
        }

        Section(tr(R.string.fans)) {
            PercentSlider(tr(R.string.fan_part), state.modelFanPct, 0..100, connected) { client.setFan(0, it) }
            if (caps.auxFan) PercentSlider(tr(R.string.fan_aux), state.auxFanPct, 0..100, connected) { client.setFan(1, it) }
            if (caps.chamberFan) PercentSlider(tr(R.string.fan_chamber), state.caseFanPct, 0..100, connected) { client.setFan(2, it) }
        }

        if (caps.lightPin != null) Section(tr(R.string.lighting)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.chamber_light), Modifier.weight(1f))
                Switch(checked = state.light == true, onCheckedChange = { client.setLight(it) }, enabled = connected)
            }
        }

        Section(tr(R.string.movement)) {
            state.position?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.isPrinting) {
                Text(tr(R.string.move_blocked), color = MaterialTheme.colorScheme.error)
            } else if (connected && state.homedAxes != "xyz") {
                Text(
                    tr(R.string.not_homed, state.homedAxes.ifEmpty { tr(R.string.none) }),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.1, 1.0, 10.0, 50.0).forEach { s ->
                    ChipButton(tr(R.string.mm_fmt, if (s < 1) s else s.toInt()), selected = step == s) { step = s }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Крестовина XY
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JogButton(Icons.Default.ArrowUpward, "Y+", canMove) { client.move('Y', step, 6000) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        JogButton(Icons.AutoMirrored.Filled.ArrowBack, "X−", canMove) { client.move('X', -step, 6000) }
                        FilledTonalIconButton(onClick = { client.home("X Y") }, enabled = canMove, modifier = Modifier.size(56.dp)
                            .border(tonalBorder(canMove), androidx.compose.foundation.shape.CircleShape)) {
                            Icon(Icons.Default.Home, tr(R.string.home_xy))
                        }
                        JogButton(Icons.AutoMirrored.Filled.ArrowForward, "X+", canMove) { client.move('X', step, 6000) }
                    }
                    JogButton(Icons.Default.ArrowDownward, "Y−", canMove) { client.move('Y', -step, 6000) }
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JogButton(Icons.Default.ArrowUpward, "Z+", canMove) { client.move('Z', step, 600) }
                    Text("Z", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 14.dp))
                    JogButton(Icons.Default.ArrowDownward, "Z−", canMove) { client.move('Z', -step, 600) }
                }
            }
            Text(
                tr(R.string.z_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { client.home() }, enabled = canMove, border = tonalBorder(canMove)) { Text(tr(R.string.home_all)) }
                OutlinedButton(onClick = { client.home("Z") }, enabled = canMove) { Text(tr(R.string.home_z)) }
                OutlinedButton(onClick = { client.motorsOff() }, enabled = canMove) { Text(tr(R.string.motors_off)) }
            }
        }

        if (caps.nozzleDiameter) Section(tr(R.string.nozzle)) {
            val current = state.nozzleDiameter
            Text(
                current?.let { tr(R.string.nozzle_current).format(it) }
                    ?: tr(R.string.nozzle_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.2, 0.4, 0.6, 0.8).forEach { d ->
                    val sel = current != null && kotlin.math.abs(current - d) < 0.01
                    ChipButton(tr(R.string.mm_1f).format(d), selected = sel, enabled = connected && !state.hasActiveJob && !sel) {
                        nozzleAsk = d
                    }
                }
            }
            if (state.hasActiveJob) Text(tr(R.string.no_change_while_printing), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (caps.cfs) Section(tr(R.string.spools)) {
            CfsBoxes(slicerUi.cfs, slicerUi.rack, slicerUi.cfsError, onRefresh = { slicerVm.loadCfs() }, onEdit = { editSlot = it })
            OutlinedButton(onClick = { tagFor = TagPrefill(null, null) }) {
                Icon(Icons.Default.Nfc, null, Modifier.size(18.dp))
                Text(tr(R.string.rfid_button), Modifier.padding(start = 8.dp))
            }
        }

        Section(tr(R.string.extruder)) {
            Text(
                tr(R.string.extruder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if ((state.nozzleTemp ?: 0.0) < 170) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5.0, 10.0, 50.0).forEach { l ->
                    ChipButton(tr(R.string.mm_fmt, l.toInt()), selected = extrudeLen == l) { extrudeLen = l }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { client.extrude(extrudeLen) }, enabled = canMove, modifier = Modifier.weight(1f),
                    border = tonalBorder(canMove)) {
                    Text(tr(R.string.extrude))
                }
                OutlinedButton(onClick = { client.extrude(-extrudeLen) }, enabled = canMove, modifier = Modifier.weight(1f)) {
                    Text(tr(R.string.retract))
                }
            }
        }
    }
    nozzleAsk?.let { d ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { nozzleAsk = null },
            title = { Text(tr(R.string.nozzle_mm).format(d)) },
            text = {
                Text(tr(R.string.nozzle_confirm_1) +
                    tr(R.string.nozzle_confirm_2).format(d))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { client.setNozzleDiameter(d); nozzleAsk = null }) { Text(tr(R.string.apply)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { nozzleAsk = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    editSlot?.let { s ->
        CfsEditDialog(
            slot = s, library = slicerUi.library, saving = slicerUi.cfsSaving,
            onLoadLibrary = slicerVm::loadLibrary,
            onSave = { m, c, mn, mx, pa -> slicerVm.saveSlot(s, m, c, mn, mx, pa) { editSlot = null } },
            onDismiss = { editSlot = null },
            onWriteTag = { m, c -> editSlot = null; tagFor = TagPrefill(m.id, c) },
        )
    }
    tagFor?.let { p ->
        SpoolTagDialog(
            library = slicerUi.library, onLoadLibrary = slicerVm::loadLibrary, onDismiss = { tagFor = null },
            initialMaterialId = p.materialId, initialColor = p.color,
        )
    }
}

@Composable
private fun JogButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(2.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)
            .border(tonalBorder(enabled), androidx.compose.foundation.shape.CircleShape)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, label)
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class TagPrefill(val materialId: String?, val color: String?)
