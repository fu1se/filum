package app.filum.ui

import app.filum.tr
import app.filum.R
import app.filum.data.MwDesign
import app.filum.data.ToolpathData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filum.SlicerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Loaded(
    val scene: Scene, val layerZ: FloatArray?, val types: List<Int>, val info: String,
    val paths: ToolpathData? = null, val tools: List<Int> = emptyList(),
)

// ====================================================================== предпросмотр

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewPanel(vm: SlicerViewModel, target: PreviewTarget, printerConnected: Boolean, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bedW = ui.presets?.bedW ?: 350f
    val bedH = ui.presets?.bedH ?: 350f
    var confirmPrint by remember { mutableStateOf(false) }

    val loaded by produceState<Result<Loaded>?>(null, target) {
        value = runCatching {
            when (target) {
                is PreviewTarget.Model -> {
                    val mesh = vm.mesh(target.model.id)
                    withContext(Dispatchers.Default) {
                        val color = target.model.slots.firstOrNull()?.color
                            ?.let { runCatching { Color(android.graphics.Color.parseColor(it.take(7))) }.getOrNull() } ?: Color(0xFF3DD9C1)  // фон просмотра всегда тёмный
                        val recenter = !target.model.name.endsWith(".3mf", true)
                        val s = prepareMesh(mesh, bedW, bedH, recenter, color)
                        Loaded(s, null, emptyList(),
                            tr(R.string.mesh_info).format(
                                s.max[0] - s.min[0], s.max[1] - s.min[1], s.max[2] - s.min[2], mesh.triangles))
                    }
                }
                is PreviewTarget.Gcode -> {
                    val tp = vm.toolpaths(target.job.id, target.result.plate)
                    withContext(Dispatchers.Default) {
                        val types = tp.types.map { it.toInt() and 0x0F }.toSortedSet().toList()
                        val tools = tp.types.filter { it.toInt() and 0x0F != 0 }.map { (it.toInt() and 0xFF) shr 4 }.toSortedSet().toList()
                        Loaded(preparePaths(tp, bedW, bedH), tp.layerZ, types, tr(R.string.segments_info).format(tp.segments), tp, tools)
                    }
                }
            }
        }
    }
    val data = loaded?.getOrNull()
    val layers = data?.layerZ?.size ?: 0
    // Цвета инструментов: катушки CFS из задания, иначе цвета из модели
    val toolColors: List<Color> = remember(target, ui.recent) {
        if (target !is PreviewTarget.Gcode) return@remember emptyList()
        val model = ui.recent.firstOrNull { it.name == target.job.modelName }
        List(16) { i ->
            parseHexColor(target.job.cfsColors.getOrNull(i)) ?: parseHexColor(model?.slots?.getOrNull(i)?.color)
                ?: FeatureColors[(i % 14) + 1]
        }
    }
    var byColor by remember(data) { mutableStateOf((data?.tools?.size ?: 0) > 1) }
    val scene by produceState<Scene?>(null, data, byColor) {
        value = data?.let { d ->
            if (d.paths == null) d.scene
            else withContext(Dispatchers.Default) { preparePaths(d.paths, bedW, bedH, if (byColor) toolColors else null) }
        }
    }
    var layer by remember(data) { mutableIntStateOf(layers - 1) }

    // Панель на месте вкладки, а не Dialog: окно диалога обрезалось системными панелями
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121416))) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (target) {
                            is PreviewTarget.Model -> target.model.displayName
                            is PreviewTarget.Gcode -> target.result.file
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(
                            if (target is PreviewTarget.Gcode) tr(R.string.gcode_preview) else tr(R.string.model),
                            data?.info,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, tr(R.string.close)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (data != null) Viewer3D(scene ?: data.scene, layer, Modifier.fillMaxSize())
                when {
                    loaded == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(if (target is PreviewTarget.Gcode) tr(R.string.loading_toolpaths) else tr(R.string.loading_model),
                            Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    loaded?.isFailure == true -> Text(
                        tr(R.string.preview_failed, loaded?.exceptionOrNull()?.message),
                        Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(tr(R.string.viewer_hint),
                    Modifier.align(Alignment.TopCenter).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (layers > 0) {
                    val z = data!!.layerZ!![layer.coerceIn(0, layers - 1)]
                    Text(tr(R.string.layer_of_z, layer + 1, layers, z), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = layer.toFloat(),
                        onValueChange = { layer = it.toInt() },
                        valueRange = 0f..(layers - 1).coerceAtLeast(1).toFloat(),
                    )
                    if (data.paths != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChipButton(tr(R.string.line_types), selected = !byColor) { byColor = false }
                            ChipButton(if (data.tools.size > 1) tr(R.string.colors_n, data.tools.size) else tr(R.string.color), selected = byColor) { byColor = true }
                        }
                    }
                    if (byColor) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        data.tools.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SpoolIcon(toolColors.getOrNull(t), 14.dp)
                                Text(" T$t", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        data.types.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(FeatureColors.getOrElse(t) { FeatureColors[0] }))
                                Text(" " + FeatureNames.getOrElse(t) { "?" }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (target is PreviewTarget.Gcode) {
                    val r = ui.job?.takeIf { it.id == target.job.id }?.results?.firstOrNull { it.plate == target.result.plate } ?: target.result
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        InfoCell(tr(R.string.time), r.time?.let(::fmtOrcaTime) ?: "—")
                        InfoCell(tr(R.string.filament), r.grams?.let { tr(R.string.grams, it) } ?: "—")
                        r.layers?.let { InfoCell(tr(R.string.layers), it) }
                    }
                    if (r.printing) {
                        Text(tr(R.string.print_started_check), color = Color(0xFF4CAF50))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!r.uploaded) {
                                OutlinedButton(onClick = { vm.sendToPrinter(target.job.id, r.plate, false) },
                                    enabled = !ui.busy, modifier = Modifier.weight(1f)) { Text(tr(R.string.to_printer)) }
                            }
                            Button(onClick = { confirmPrint = true }, enabled = printerConnected && !ui.busy,
                                modifier = Modifier.weight(1f)) {
                                Text(if (printerConnected) tr(R.string.start_print) else tr(R.string.printer_not_connected))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmPrint && target is PreviewTarget.Gcode) {
        AlertDialog(
            onDismissRequest = { confirmPrint = false },
            title = { Text(tr(R.string.start_print_q)) },
            text = { Text(tr(R.string.start_print_desc, target.result.file)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrint = false
                    vm.sendToPrinter(target.job.id, target.result.plate, start = true) { onClose() }
                }) { Text(tr(R.string.action_print)) }
            },
            dismissButton = { TextButton(onClick = { confirmPrint = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

// ====================================================================== MakerWorld

@Composable
fun MakerWorldPanel(vm: SlicerViewModel, onClose: () -> Unit) {
    val mw by vm.mw.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(mw.query) }
    LaunchedEffect(Unit) { vm.mwRefreshStatus() }

    // Панель на месте вкладки, а не Dialog: окно диалога обрезалось системными панелями
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mw.design != null) {
                    IconButton(onClick = { vm.mwCloseDesign() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
                } else Spacer(Modifier.width(12.dp))
                Text("MakerWorld", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, tr(R.string.close)) }
            }
            AccountBlock(vm, mw.loggedIn, mw.account, mw.needCode, mw.loading)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(tr(R.string.mw_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.mwSearch(query) }),
                )
                IconButton(onClick = { vm.mwSearch(query) }) { Icon(Icons.Default.Search, tr(R.string.find)) }
            }
            mw.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (mw.loading && mw.design == null && mw.hits.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Box(Modifier.weight(1f)) {
                val design = mw.design
                if (design != null) {
                    DesignView(vm, design, mw.selectedInstance, mw.loggedIn, mw.importing, onImported = onClose)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (mw.hits.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(tr(R.string.found_n, mw.total), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(mw.hits, key = { it.id }) { h ->
                            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { vm.openDesign(h.id) }) {
                                RemoteImage(h.cover, Modifier.fillMaxWidth().aspectRatio(1f))
                                Column(Modifier.padding(8.dp)) {
                                    Text(h.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("⬇ ${h.downloads}" + (h.author?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (mw.hits.isNotEmpty() && mw.hits.size < mw.total) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OutlinedButton(onClick = { vm.mwSearch(mw.query, more = true) }, enabled = !mw.loading) { Text(tr(R.string.more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountBlock(vm: SlicerViewModel, loggedIn: Boolean, account: String?, needCode: Boolean, loading: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf(account.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (loggedIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.bambu_account, account ?: tr(R.string.token_saved)), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.mwLogout() }) { Text(tr(R.string.log_out)) }
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.bambu_login_needed), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) tr(R.string.hide) else tr(R.string.log_in)) }
        }
        if (expanded || needCode) {
            OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.email)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            if (needCode) {
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.email_code)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } else {
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(tr(R.string.password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Button(onClick = {
                    if (needCode) vm.mwLogin(email, null, code, null) else vm.mwLogin(email, password, null, null)
                    password = ""
                }, enabled = !loading && email.isNotBlank()) { Text(tr(R.string.log_in)) }
            }
            OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr(R.string.bambu_token_hint)) })
            if (token.isNotBlank()) {
                TextButton(onClick = { vm.mwLogin(email, null, null, token); token = "" }) { Text(tr(R.string.save_token)) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DesignView(
    vm: SlicerViewModel, d: MwDesign, selected: Long?, loggedIn: Boolean,
    importing: Boolean, onImported: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(d.cover, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold)
                    d.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(tr(R.string.mw_profile_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(d.instances, key = { it.id }) { i ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (i.id == selected) Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { vm.mwSelectInstance(i.id) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(i.thumb, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Fit)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(i.title ?: tr(R.string.profile_n, i.id), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(i.printer, tr(R.string.plates_n, i.plates), tr(R.string.colors_count, i.materials)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(
                onClick = { vm.mwImport(onImported) },
                enabled = loggedIn && selected != null && !importing,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                if (importing) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (loggedIn) tr(R.string.import_to_slicer) else tr(R.string.bambu_login_to_download))
            }
        }
    }
}
