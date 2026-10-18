package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.filum.data.CfsSlot
import app.filum.data.FilamentSlot

fun parseHexColor(hex: String?): Color? =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it.take(7))) }.getOrNull() }

/** Катушка: кольцо пластика цвета нити, ступица и отверстие. Пустой слот — пунктир. */
@Composable
fun SpoolIcon(color: Color?, size: Dp, selected: Boolean = false, empty: Boolean = false) {
    val accent = Accent
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = Offset(this.size.width / 2, this.size.height / 2)
        if (empty || color == null) {
            drawCircle(Color(0xFF5A6068), r * 0.92f, c,
                style = Stroke(width = r * 0.08f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.2f, r * 0.15f))))
            drawCircle(Color(0xFF2A2F35), r * 0.32f, c)
            return@Canvas
        }
        if (selected) drawCircle(accent, r, c)
        drawCircle(Color(0xFF15181C), r * (if (selected) 0.9f else 1f), c)          // щёчка катушки
        drawCircle(color, r * 0.82f, c)                                          // намотанный пластик
        // лёгкие «витки», чтобы белая и чёрная катушки читались на тёмном фоне
        drawCircle(Color.Black.copy(alpha = 0.18f), r * 0.68f, c, style = Stroke(width = r * 0.03f))
        drawCircle(Color.White.copy(alpha = 0.12f), r * 0.55f, c, style = Stroke(width = r * 0.03f))
        drawCircle(Color(0xFF2A2F35), r * 0.36f, c)                              // ступица
        drawCircle(Color(0xFF0E1013), r * 0.16f, c)                              // отверстие
    }
}

/** Строка «цвет модели → катушка», по нажатию открывается выбор катушки. */
@Composable
fun CfsAssignRow(
    index: Int,
    total: Int,
    modelSlot: FilamentSlot?,
    spool: CfsSlot?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (total > 1) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(parseHexColor(modelSlot?.color) ?: Color.Gray)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
                Text(tr(R.string.color_n, index + 1), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("→", Modifier.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SpoolIcon(parseHexColor(spool?.color), 46.dp, empty = spool == null)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            if (spool == null) {
                Text(tr(R.string.no_spool), fontWeight = FontWeight.SemiBold)
                Text(tr(R.string.tap_to_pick_cfs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(tr(R.string.cfs_box_slot, spool.box, spool.letter), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOfNotNull(spool.type, spool.name?.takeIf { it != spool.type }).joinToString(" · "),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                spool.remain?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        LinearProgressIndicator(progress = { r / 100f }, modifier = Modifier.weight(1f).height(4.dp),
                            color = if (r < 15) MaterialTheme.colorScheme.error else Accent)
                        Text("  $r%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("▾", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Выбор катушки: боксы CFS как на принтере — по 4 слота A–D. */
@Composable
fun CfsPickerDialog(
    title: String,
    spools: List<CfsSlot>,
    selected: String?,
    usedElsewhere: Set<String>,
    onPick: (String?) -> Unit,
    onEdit: (CfsSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                spools.groupBy { it.box }.toSortedMap().forEach { (box, list) ->
                    Column {
                        Text("CFS $box", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            list.sortedBy { it.letter }.forEach { s ->
                                SpoolCard(s, s.id == selected, s.id in usedElsewhere, Modifier.weight(1f),
                                    onEdit = { onEdit(s) }) {
                                    if (!s.empty) { onPick(s.id); onDismiss() }
                                }
                            }
                        }
                    }
                }
                Text(tr(R.string.cfs_picker_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.close)) } },
        dismissButton = { TextButton(onClick = { onPick(null); onDismiss() }) { Text(tr(R.string.unassign)) } },
    )
}

@Composable
fun SpoolCard(
    s: CfsSlot,
    selected: Boolean,
    used: Boolean,
    modifier: Modifier,
    clickEmpty: Boolean = false,
    onEdit: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (selected) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
                .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
                .clickable(enabled = clickEmpty || !s.empty, onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SpoolIcon(parseHexColor(s.color), 48.dp, selected = selected, empty = s.empty)
            Text(s.letter, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            if (s.empty) {
                Text(tr(R.string.empty), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(s.type ?: "?", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(s.name?.takeIf { it != s.type } ?: (s.brand ?: ""), style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.remain?.let {
                    Text("$it%", style = MaterialTheme.typography.labelSmall,
                        color = if (it < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (used && !selected) Text(tr(R.string.already_used), style = MaterialTheme.typography.labelSmall, color = Accent)
            }
        }
        if (onEdit != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { EditBadge(onEdit) }
    }
}
