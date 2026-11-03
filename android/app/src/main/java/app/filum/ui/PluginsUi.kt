package app.filum.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filum.R
import app.filum.plugins.GRANT_DEVICE
import app.filum.plugins.GRANT_GCODE
import app.filum.plugins.GRANT_HTTP
import app.filum.plugins.GRANT_NOTIFY
import app.filum.plugins.PluginItem
import app.filum.plugins.PluginMeta
import app.filum.plugins.PluginSection
import app.filum.plugins.Plugins
import app.filum.update.CatalogPlugin
import app.filum.update.Versions
import app.filum.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Разделы, которые добавили плагины (filum.ui.section), — на вкладке «Управление». Для устройства на драйвере
 * [device] — только разделы его плагина и плагинов без доступа к принтеру (без разрешения gcode): кнопки
 * принтерных плагинов вроде «нагреть до 240°» станку не нужны.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginSections(device: String = "") {
    val sections by Plugins.host.sections.collectAsStateWithLifecycle()
    val plugins by Plugins.host.plugins.collectAsStateWithLifecycle()
    for (s in sections) {
        if (device.isNotEmpty() && s.pluginId != device &&
            plugins.firstOrNull { it.meta.id == s.pluginId }?.meta?.grants?.contains(GRANT_GCODE) != false) continue
        val name = plugins.firstOrNull { it.meta.id == s.pluginId }?.meta?.name ?: s.pluginId
        Section(s.title.ifBlank { name }) {
            if (s.title.isNotBlank() && s.title != name) Text(tr(R.string.plugin_by, name),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PluginItems(s)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginItems(s: PluginSection) {
    fun send(i: Int, v: Any?) = Plugins.host.uiEvent(s.pluginId, s.id, i, v)
    // кнопки подряд — в одну строку с переносом
    val groups = mutableListOf<MutableList<Pair<Int, PluginItem>>>()
    s.items.forEachIndexed { i, item ->
        if (item is PluginItem.Button && groups.lastOrNull()?.lastOrNull()?.second is PluginItem.Button) groups.last() += i to item
        else groups += mutableListOf(i to item)
    }
    for (g in groups) {
        val (i, item) = g.first()
        when (item) {
            is PluginItem.Button -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((bi, b) in g) FilledTonalButton(onClick = { send(bi, null) }) { Text((b as PluginItem.Button).label) }
            }
            is PluginItem.Text -> Text(item.text, style = MaterialTheme.typography.bodyMedium)
            is PluginItem.Switch -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.weight(1f))
                Switch(checked = item.value, onCheckedChange = { send(i, it) })
            }
            is PluginItem.Slider -> {
                var v by remember(s.id, i, item.value) { mutableFloatStateOf(item.value.toFloat()) }
                Text("${item.label}: ${fmtNum(v.toDouble(), item.step)}", style = MaterialTheme.typography.bodyMedium)
                val steps = ((item.max - item.min) / item.step).toInt() - 1
                Slider(
                    value = v, onValueChange = { v = it },
                    onValueChangeFinished = { send(i, v.toDouble()) },
                    valueRange = item.min.toFloat()..item.max.toFloat(),
                    steps = steps.coerceIn(0, 200),
                )
            }
            is PluginItem.Input -> {
                var t by remember(s.id, i, item.value) { mutableStateOf(item.value) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(t, { t = it }, label = { Text(item.label) }, singleLine = true, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { send(i, t) }) { Text(item.button) }
                }
            }
        }
    }
}

private fun fmtNum(v: Double, step: Double) =
    if (step >= 1) v.toLong().toString() else "%.${(-kotlin.math.log10(step)).toInt().coerceIn(1, 4)}f".format(Locale.US, v)

/** Менеджер плагинов: список, включение, установка, удаление. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginsScreen(onClose: () -> Unit) {
    val plugins by Plugins.host.plugins.collectAsStateWithLifecycle()
    val logs by Plugins.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<PluginMeta, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askUrl by remember { mutableStateOf(false) }
    var showExamples by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PluginMeta?>(null) }
    var openLog by remember { mutableStateOf<String?>(null) }

    // каталог проверенных плагинов из репозитория Filum: перечитывается при каждом открытии
    val catalog by Plugins.catalog.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Plugins.refreshCatalog() }
    val installedVersions = plugins.associate { it.meta.id to it.meta.version }
    val updates = catalog.plugins.orEmpty().filter { c ->
        installedVersions[c.id]?.let { Versions.isNewer(c.version, it) } == true
    }
    var busyId by remember { mutableStateOf<String?>(null) }
    var askUpdates by remember { mutableStateOf(false) }
    var prompted by remember { mutableStateOf(false) }
    LaunchedEffect(updates.map { it.id }) { if (updates.isNotEmpty() && !prompted) { askUpdates = true; prompted = true } }

    fun offer(src: String) {
        try { pending = Plugins.inspect(src) to src } catch (e: Exception) { error = tr(R.string.plugin_bad_file, e.message ?: "") }
    }

    /** Скачать плагин из каталога (с проверкой SHA-256) и показать подтверждение с разрешениями. */
    fun fromCatalog(c: CatalogPlugin) = scope.launch {
        busyId = c.id
        val r = withContext(Dispatchers.IO) { runCatching { Plugins.downloadVerified(c) } }
        busyId = null
        r.onSuccess(::offer).onFailure { error = tr(R.string.catalog_failed, c.name, it.message ?: "") }
    }

    /** Обновить всё: без новых разрешений — сразу; с новыми — через подтверждение по одному. */
    fun updateAll() = scope.launch {
        for (c in updates) {
            busyId = c.id
            val r = withContext(Dispatchers.IO) { runCatching { Plugins.downloadVerified(c) } }
            busyId = null
            val src = r.getOrNull()
            if (src == null) { error = tr(R.string.catalog_failed, c.name, r.exceptionOrNull()?.message ?: ""); continue }
            val old = plugins.firstOrNull { it.meta.id == c.id }?.meta?.grants.orEmpty()
            if (PluginMeta.parse(src).grants.all { it in old }) Plugins.install(src) else { offer(src); break }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val src = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            }
            src.onSuccess(::offer).onFailure { error = it.message }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(tr(R.string.plugins)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.close)) } },
            )
        }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(tr(R.string.plugin_from_file)) }
                    OutlinedButton(onClick = { askUrl = true }) { Text(tr(R.string.plugin_from_url)) }
                    OutlinedButton(onClick = { showExamples = true }) { Text(tr(R.string.plugin_examples)) }
                }
                Text(tr(R.string.plugins_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CatalogSection(catalog, installedVersions, busyId, updates.size, ::fromCatalog, ::updateAll)
                if (plugins.isEmpty()) Text(tr(R.string.plugins_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (p in plugins) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, null, tint = Accent)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(p.meta.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(p.meta.version, p.meta.author).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { p.meta.id },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = p.enabled, onCheckedChange = { Plugins.setEnabled(p.meta.id, it) })
                        }
                        if (p.meta.description.isNotBlank()) Text(p.meta.description, style = MaterialTheme.typography.bodySmall)
                        GrantsLine(p.meta.grants)
                        p.error?.let { Text(tr(R.string.plugin_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val lines = logs[p.meta.id].orEmpty()
                            TextButton(onClick = { openLog = if (openLog == p.meta.id) null else p.meta.id }, enabled = lines.isNotEmpty()) {
                                Text(tr(R.string.plugin_log, lines.size))
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { confirmDelete = p.meta }) { Icon(Icons.Default.Delete, tr(R.string.delete)) }
                        }
                        if (openLog == p.meta.id) Text(
                            logs[p.meta.id].orEmpty().joinToString("\n"),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }

    if (askUpdates) AlertDialog(
        onDismissRequest = { askUpdates = false },
        title = { Text(tr(R.string.catalog_updates_q, updates.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in updates) Text("• ${c.name}: ${installedVersions[c.id]} → ${c.version}")
            }
        },
        confirmButton = { TextButton(onClick = { askUpdates = false; updateAll() }) { Text(tr(R.string.catalog_update_all)) } },
        dismissButton = { TextButton(onClick = { askUpdates = false }) { Text(tr(R.string.update_later)) } },
    )

    pending?.let { (meta, src) ->
        val update = plugins.firstOrNull { it.meta.id == meta.id }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (update != null) tr(R.string.plugin_update_q, meta.name) else tr(R.string.plugin_install_q, meta.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listOf(meta.version, meta.author).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium)
                    if (meta.description.isNotBlank()) Text(meta.description)
                    if (meta.grants.isEmpty()) Text(tr(R.string.plugin_no_grants), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(tr(R.string.plugin_grants_title), style = MaterialTheme.typography.titleSmall)
                        for (g in meta.grants.sorted()) Text("• " + grantText(g), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr(R.string.plugin_trust), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try { Plugins.install(src) } catch (e: Exception) { error = e.message }
                    pending = null; showExamples = false
                }) { Text(if (update != null) tr(R.string.plugin_update) else tr(R.string.plugin_install)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (askUrl) {
        var url by remember { mutableStateOf("https://") }
        var loading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askUrl = false },
            title = { Text(tr(R.string.plugin_from_url)) },
            text = { OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("URL .js") }) },
            confirmButton = {
                TextButton(enabled = !loading && url.startsWith("http"), onClick = {
                    loading = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                                    resp.body!!.string()
                                }
                            }
                        }
                        loading = false
                        r.onSuccess { askUrl = false; offer(it) }.onFailure { error = it.message }
                    }
                }) { Text(tr(R.string.plugin_download)) }
            },
            dismissButton = { TextButton(onClick = { askUrl = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (showExamples) {
        val examples = remember { Plugins.examples() }
        AlertDialog(
            onDismissRequest = { showExamples = false },
            title = { Text(tr(R.string.plugin_examples)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((m, src) in examples) Column(Modifier.fillMaxWidth().clickable { offer(src) }.padding(vertical = 4.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, color = Accent)
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExamples = false }) { Text(tr(R.string.close)) } },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr(R.string.plugin_delete_q, m.name)) },
            text = { Text(tr(R.string.plugin_delete_hint)) },
            confirmButton = { TextButton(onClick = { Plugins.delete(m.id); confirmDelete = null }) { Text(tr(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    error?.let { e ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(tr(R.string.plugins)) },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(tr(R.string.close)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrantsLine(grants: Set<String>) {
    if (grants.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in grants.sorted()) Text(
            g, style = MaterialTheme.typography.labelSmall,
            color = if (g == GRANT_GCODE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun grantText(g: String) = when (g) {
    GRANT_GCODE -> tr(R.string.grant_gcode)
    GRANT_HTTP -> tr(R.string.grant_http)
    GRANT_NOTIFY -> tr(R.string.grant_notify)
    GRANT_DEVICE -> tr(R.string.grant_device)
    else -> g
}

/** Каталог проверенных плагинов: установить, обновить (версия новее установленной) или «установлен». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogSection(
    catalog: Plugins.CatalogState,
    installed: Map<String, String>,
    busyId: String?,
    updates: Int,
    onInstall: (CatalogPlugin) -> Unit,
    onUpdateAll: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr(R.string.catalog_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (catalog.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else if (updates > 0) FilledTonalButton(onClick = onUpdateAll, enabled = busyId == null) {
            Text(tr(R.string.catalog_update_n, updates))
        }
    }
    catalog.error?.let { Text(tr(R.string.catalog_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val list = catalog.plugins
    if (list != null && list.isEmpty()) Text(tr(R.string.catalog_empty), style = MaterialTheme.typography.bodySmall)
    for (c in list.orEmpty()) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOf(c.version, c.author).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val have = installed[c.id]
                when {
                    busyId == c.id -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    have == null -> FilledTonalButton(onClick = { onInstall(c) }, enabled = busyId == null) { Text(tr(R.string.plugin_install)) }
                    Versions.isNewer(c.version, have) -> FilledTonalButton(onClick = { onInstall(c) }, enabled = busyId == null) {
                        Text(tr(R.string.catalog_update_to, c.version))
                    }
                    else -> Text(tr(R.string.catalog_installed), style = MaterialTheme.typography.labelMedium, color = Accent)
                }
            }
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            GrantsLine(c.grants.toSet())
        }
    }
}
