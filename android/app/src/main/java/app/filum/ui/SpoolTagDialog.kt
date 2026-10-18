package app.filum.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.filum.R
import app.filum.data.MaterialDef
import app.filum.data.SpoolTag
import app.filum.nfc.SpoolTagNfc
import app.filum.nfc.TagContent
import app.filum.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private enum class TagOp { Write, Read, Erase }

private sealed interface TagStep {
    data object Edit : TagStep
    data class Waiting(val op: TagOp) : TagStep
    data class Written(val tag: SpoolTag) : TagStep
    data class ReadResult(val content: TagContent) : TagStep
    data object Erased : TagStep
    data class Failed(val message: String, val nfcOff: Boolean = false) : TagStep
}

/**
 * RFID tag for a spool: pick a material from the printer's library, a color and a weight, then hold a MIFARE
 * Classic 1K tag to the phone. The CFS reads the tag when the spool is loaded and fills the slot by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpoolTagDialog(
    library: List<MaterialDef>?,
    onLoadLibrary: () -> Unit,
    onDismiss: () -> Unit,
    initialMaterialId: String? = null,
    initialColor: String? = null,
) {
    LaunchedEffect(Unit) { if (library == null) onLoadLibrary() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<TagStep>(TagStep.Edit) }
    val initial = remember(library) {
        library?.let { lib ->
            lib.firstOrNull { it.id == initialMaterialId }
                ?: lib.firstOrNull { it.brand == "Generic" && it.type == "PLA" }
                ?: lib.firstOrNull()
        }
    }
    var mat by remember(initial) { mutableStateOf(initial) }
    var color by remember { mutableStateOf(initialColor ?: "#FFFFFF") }
    var weight by remember { mutableStateOf(1000) }
    // ключ пикера: после «Взять из метки» он пересоздаётся с новым пластиком
    var pickerKey by remember { mutableStateOf(0) }

    fun start(op: TagOp) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        step = when {
            adapter == null -> TagStep.Failed(tr(R.string.rfid_no_nfc))
            !adapter.isEnabled -> TagStep.Failed(tr(R.string.rfid_nfc_off), nfcOff = true)
            else -> TagStep.Waiting(op)
        }
    }

    (step as? TagStep.Waiting)?.let { waiting ->
        val activity = context.findActivity()
        DisposableEffect(waiting) {
            val adapter = NfcAdapter.getDefaultAdapter(activity)
            val busy = AtomicBoolean(false)
            val spool = mat?.let { m -> SpoolTag(materialId = m.id, color = color, lengthM = SpoolTag.lengthFor(weight)) }
            val callback = NfcAdapter.ReaderCallback { tag: Tag ->
                if (!busy.compareAndSet(false, true)) return@ReaderCallback
                val result = runCatching {
                    when (waiting.op) {
                        TagOp.Write -> { SpoolTagNfc.write(tag, spool!!); TagStep.Written(spool) }
                        TagOp.Read -> TagStep.ReadResult(SpoolTagNfc.read(tag))
                        TagOp.Erase -> { SpoolTagNfc.erase(tag); TagStep.Erased }
                    }
                }.getOrElse { TagStep.Failed(it.message ?: it.javaClass.simpleName) }
                scope.launch(Dispatchers.Main) { if (step == waiting) step = result }
            }
            adapter?.enableReaderMode(
                activity, callback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null,
            )
            onDispose { runCatching { adapter?.disableReaderMode(activity) } }
        }
    }

    val busy = step is TagStep.Waiting
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Nfc, null, tint = Accent)
                Text(tr(R.string.rfid_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            when (val s = step) {
                TagStep.Edit -> {
                    if (library == null) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        return@AlertDialog
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        key(pickerKey) { MaterialColorPicker(library, mat, { mat = it }, color) { color = it } }
                        Text(tr(R.string.rfid_weight), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SpoolTag.WEIGHTS.keys.forEach { w ->
                                ChipButton(weightLabel(w), selected = w == weight) { weight = w }
                            }
                        }
                        Text(tr(R.string.rfid_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { start(TagOp.Read) }, modifier = Modifier.weight(1f)) { Text(tr(R.string.rfid_read)) }
                            OutlinedButton(onClick = { start(TagOp.Erase) }, modifier = Modifier.weight(1f)) { Text(tr(R.string.rfid_erase)) }
                        }
                    }
                }
                is TagStep.Waiting -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Default.Nfc, null, Modifier.size(56.dp), tint = Accent)
                    Text(
                        when (s.op) {
                            TagOp.Write -> tr(R.string.rfid_hold_write, mat?.name ?: "")
                            TagOp.Read -> tr(R.string.rfid_hold_read)
                            TagOp.Erase -> tr(R.string.rfid_hold_erase)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is TagStep.Written -> ResultLine(true, tr(R.string.rfid_written, mat?.name ?: s.tag.materialId), tr(R.string.rfid_written_hint))
                TagStep.Erased -> ResultLine(true, tr(R.string.rfid_erased), null)
                is TagStep.Failed -> ResultLine(false, s.message, null)
                is TagStep.ReadResult -> TagInfo(s.content, library)
            }
        },
        confirmButton = {
            when (val s = step) {
                TagStep.Edit -> TextButton(onClick = { start(TagOp.Write) }, enabled = mat != null) { Text(tr(R.string.rfid_write)) }
                is TagStep.Waiting -> {}
                is TagStep.Failed -> if (s.nfcOff) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                    step = TagStep.Edit
                }) { Text(tr(R.string.rfid_open_settings)) } else TextButton(onClick = { step = TagStep.Edit }) { Text(tr(R.string.rfid_back)) }
                is TagStep.ReadResult -> {
                    val spool = (s.content as? TagContent.Spool)?.tag
                    val known = spool?.let { t -> library?.firstOrNull { it.id == t.materialId } }
                    if (spool != null && known != null) TextButton(onClick = {
                        mat = known
                        color = spool.color
                        spool.weightG?.let { weight = it }
                        pickerKey++
                        step = TagStep.Edit
                    }) { Text(tr(R.string.rfid_use)) } else TextButton(onClick = { step = TagStep.Edit }) { Text(tr(R.string.rfid_back)) }
                }
                else -> TextButton(onClick = { step = TagStep.Edit }) { Text(tr(R.string.rfid_another)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (busy) step = TagStep.Edit else onDismiss() }) {
                Text(if (busy) tr(R.string.cancel) else tr(R.string.close))
            }
        },
    )
}

@Composable
private fun ResultLine(ok: Boolean, text: String, hint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null,
                tint = if (ok) Accent else MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TagInfo(content: TagContent, library: List<MaterialDef>?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("UID ${content.uid}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (content) {
            is TagContent.Blank -> Text(tr(R.string.rfid_blank))
            is TagContent.Other -> Text(tr(R.string.rfid_other))
            is TagContent.Spool -> {
                val t = content.tag
                val m = library?.firstOrNull { it.id == t.materialId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpoolIcon(parseHexColor(t.color), 40.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(m?.let { "${it.brand} ${it.name}" } ?: tr(R.string.rfid_unknown_material, t.materialId),
                            style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(m?.type, t.color, t.weightG?.let(::weightLabel) ?: "${t.lengthM} m").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr(R.string.rfid_details, t.materialId, t.vendorCode, t.serial),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m == null && library != null) Text(tr(R.string.rfid_unknown_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun weightLabel(g: Int) = if (g >= 1000) tr(R.string.kg_fmt, g / 1000) else tr(R.string.g_fmt, g)

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("no activity")
}
