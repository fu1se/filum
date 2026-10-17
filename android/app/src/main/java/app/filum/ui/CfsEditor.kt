package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filum.data.CfsSlot
import app.filum.data.MaterialDef

/** Цвета на случай, когда у пластика нет своей палитры. */
private val basicColors = listOf(
    "#FFFFFF", "#000000", "#9EA2A2", "#5B5F63", "#C12E1F", "#DA291C", "#FF6A14", "#FCE300",
    "#4C8D2B", "#006B54", "#7CE0D3", "#0A2989", "#0032A0", "#6667AB", "#9E007E", "#F3BBCA",
    "#E7CEB5", "#9D432C", "#713F2A", "#B68601",
)

private val brandOrder = listOf("Creality", "Generic")

/** Боксы CFS: по нажатию на слот — правка пластика. */
@Composable
fun CfsBoxes(
    spools: List<CfsSlot>?,
    rack: CfsSlot?,
    error: String?,
    onRefresh: () -> Unit,
    onEdit: (CfsSlot) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            spools == null -> Text(tr(R.string.cfs_loading), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            spools.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error?.let { tr(R.string.cfs_unavailable_reason, it) } ?: tr(R.string.cfs_not_connected),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, tr(R.string.refresh)) }
            }
            else -> spools.groupBy { it.box }.toSortedMap().forEach { (box, list) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CFS $box", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val f = list.first()
                        val meta = listOfNotNull(f.boxTemp?.let { "%.0f°".format(it) }, f.humidity?.let { tr(R.string.humidity_fmt).format(it) })
                        if (meta.isNotEmpty()) Text("  ·  " + meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (box == spools.first().box) IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, tr(R.string.refresh), Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.sortedBy { it.letter }.forEach { s ->
                            SpoolCard(s, selected = false, used = false, modifier = Modifier.weight(1f), clickEmpty = true,
                                onEdit = null) { onEdit(s) }
                        }
                    }
                }
            }
        }
        if (rack != null) Column {
            Text(tr(R.string.spool_holder), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpoolCard(rack.copy(letter = tr(R.string.external)), selected = false, used = false, modifier = Modifier.weight(1f),
                    clickEmpty = true, onEdit = null) { onEdit(rack) }
                Spacer(Modifier.weight(3f))
            }
        }
        if (!spools.isNullOrEmpty() || rack != null) Text(tr(R.string.cfs_tap_to_edit),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Правка слота CFS: производитель → тип → пластик из библиотеки принтера, цвет, температуры, PA. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CfsEditDialog(
    slot: CfsSlot,
    library: List<MaterialDef>?,
    saving: Boolean,
    onLoadLibrary: () -> Unit,
    onSave: (MaterialDef, String, Int, Int, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoadLibrary() }
    val initial = remember(library) {
        library?.let { lib ->
            lib.firstOrNull { it.id == slot.rfid && it.name == slot.name }
                ?: lib.firstOrNull { it.name == slot.name }
                ?: lib.firstOrNull { it.id == slot.rfid }
                ?: lib.firstOrNull { it.brand == "Generic" && it.type == (slot.type ?: "PLA") }
                ?: lib.firstOrNull()
        }
    }
    var brand by remember(initial) { mutableStateOf(initial?.brand) }
    var type by remember(initial) { mutableStateOf(initial?.type) }
    var mat by remember(initial) { mutableStateOf(initial) }
    var color by remember { mutableStateOf(slot.color ?: "#FFFFFF") }
    var hex by remember { mutableStateOf(color.removePrefix("#")) }
    var minT by remember(initial) { mutableStateOf(((if (slot.name == initial?.name) slot.minTemp else null) ?: initial?.minTemp ?: 0).toString()) }
    var maxT by remember(initial) { mutableStateOf(((if (slot.name == initial?.name) slot.maxTemp else null) ?: initial?.maxTemp ?: 0).toString()) }
    var pa by remember(initial) {
        mutableStateOf(((if (slot.name == initial?.name) slot.pressure else null) ?: initial?.pressure ?: 0.04).let { "%.3f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.') })
    }

    fun choose(m: MaterialDef) {
        mat = m
        minT = m.minTemp.toString()
        maxT = m.maxTemp.toString()
        pa = (m.pressure ?: 0.04).toString()
    }

    val minV = minT.toIntOrNull()
    val maxV = maxT.toIntOrNull()
    val paV = pa.replace(',', '.').toDoubleOrNull()
    val valid = mat != null && minV != null && maxV != null && minV in 150..400 && maxV in minV..400 && paV != null && paV in 0.0..2.0

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpoolIcon(parseHexColor(color), 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(if (slot.box == 0) tr(R.string.spool_holder) else tr(R.string.cfs_box_slot, slot.box, slot.letter), style = MaterialTheme.typography.titleMedium)
                    Text(mat?.name ?: "—", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            if (library == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ваши производители из orca-sync — первыми
                val syncedBrands = library.filter { it.synced }.map { it.brand }.toSet()
                val brands = library.map { it.brand }.distinct()
                    .sortedWith(compareBy({ b -> if (b in syncedBrands) -1 else brandOrder.indexOf(b).let { if (it < 0) 99 else it } }, { it }))
                Label(tr(R.string.vendor))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    brands.forEach { b ->
                        Pill(if (b in syncedBrands) "★ $b" else b, b == brand) {
                            brand = b
                            val types = library.filter { it.brand == b }.map { it.type }
                            if (type !in types) type = types.firstOrNull()
                            library.firstOrNull { it.brand == b && it.type == type }?.let(::choose)
                        }
                    }
                }
                Label(tr(R.string.type))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    library.filter { it.brand == brand }.map { it.type }.distinct().forEach { t ->
                        Pill(t, t == type) {
                            type = t
                            library.firstOrNull { it.brand == brand && it.type == t }?.let(::choose)
                        }
                    }
                }
                Label(tr(R.string.filament))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    library.filter { it.brand == brand && it.type == type }.forEach { m ->
                        Pill(if (m.synced) "★ ${m.name}" else m.name, m.id == mat?.id) { choose(m) }
                    }
                }
                Label(tr(R.string.color))
                val palette = (mat?.colors?.takeIf { it.size >= 4 } ?: basicColors).let { p ->
                    if (p.any { it.equals(color, true) }) p else listOf(color.uppercase()) + p
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    palette.forEach { c ->
                        val sel = c.equals(color, true)
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(50))
                                .background(parseHexColor(c) ?: Color.Gray)
                                .border(if (sel) 3.dp else 1.dp, if (sel) Accent else Color(0x55FFFFFF), RoundedCornerShape(50))
                                .clickable { color = c.uppercase(); hex = c.removePrefix("#").uppercase() },
                        )
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        hex = v.removePrefix("#").uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                        if (hex.length == 6) color = "#$hex"
                    },
                    label = { Text("HEX") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(tr(R.string.min_temp), minT, Modifier.weight(1f)) { minT = it }
                    NumField(tr(R.string.max_temp), maxT, Modifier.weight(1f)) { maxT = it }
                    NumField("PA", pa, Modifier.weight(1f), decimal = true) { pa = it }
                }
                Text(tr(R.string.synced_filaments_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (slot.empty) Text(tr(R.string.slot_empty_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(mat!!, color, minV!!, maxV!!, paV!!) },
                enabled = valid && !saving,
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(tr(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(1.dp, Accent, RoundedCornerShape(50)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(6)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** Маленькая кнопка правки на карточке катушки. */
@Composable
fun EditBadge(onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Edit, tr(R.string.edit), Modifier.size(14.dp)) }
}
