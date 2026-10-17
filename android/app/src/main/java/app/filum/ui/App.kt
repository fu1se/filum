package app.filum.ui

import app.filum.tr
import app.filum.R
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filum.AppSettings
import app.filum.CameraMode
import app.filum.PrinterViewModel
import app.filum.SlicerViewModel
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.LaunchedEffect
import app.filum.data.ConnState

/** Подпись — id строки: текст берётся при отрисовке, чтобы смена языка подхватывалась сразу. */
private data class Tab(@androidx.annotation.StringRes val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val title get() = tr(titleRes)
}

private val tabs = listOf(
    Tab(R.string.tab_home, Icons.Default.Dashboard),
    Tab(R.string.tab_control, Icons.Default.Tune),
    Tab(R.string.tab_slicer, Icons.Default.ViewInAr),
    Tab(R.string.tab_files, Icons.AutoMirrored.Filled.List),
    Tab(R.string.tab_timelapses, Icons.Default.VideoLibrary),
)

/** Консоль открывается иконкой в верхней панели — шесть вкладок внизу не помещаются. */
private const val CONSOLE_TAB = 5
private const val SLICER_TAB = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: PrinterViewModel, slicerVm: SlicerViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.client.state.collectAsStateWithLifecycle()
    val conn by vm.client.connection.collectAsStateWithLifecycle()
    val files by vm.client.files.collectAsStateWithLifecycle()
    val log by vm.client.log.collectAsStateWithLifecycle()
    val timelapses by vm.timelapses.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(settings.host.isBlank()) }
    var addingPrinter by rememberSaveable { mutableStateOf(false) }
    var printerMenu by remember { mutableStateOf(false) }
    val printers by vm.printers.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val connected = conn == ConnState.Connected
    val openSlicer by slicerVm.openSlicerTab.collectAsStateWithLifecycle()
    val crealityInfo by vm.creality.info.collectAsStateWithLifecycle()
    LaunchedEffect(state.nozzleDiameter) { slicerVm.setNozzle(state.nozzleDiameter) }
    LaunchedEffect(crealityInfo?.modelName) { slicerVm.setPrinterModel(crealityInfo?.modelName) }
    LaunchedEffect(openSlicer) {
        if (openSlicer) { tab = SLICER_TAB; slicerVm.openSlicerTab.value = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { printerMenu = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(settings.name.ifBlank { null } ?: state.hostname ?: "Filum", maxLines = 1)
                            Icon(Icons.Default.ArrowDropDown, tr(R.string.printers))
                        }
                        // список принтеров: переключение и добавление
                        DropdownMenu(expanded = printerMenu, onDismissRequest = { printerMenu = false }) {
                            printers.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.title + if (p.name.isNotBlank()) "  ·  ${p.host}" else "") },
                                    leadingIcon = { if (p.id == activeId) Icon(Icons.Default.Check, null, tint = Accent) },
                                    onClick = {
                                        printerMenu = false
                                        if (p.id != activeId) {
                                            vm.switchPrinter(p.id); slicerVm.reload()
                                            (context as? app.filum.MainActivity)?.startMonitor()
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(tr(R.string.add_printer)) },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { printerMenu = false; addingPrinter = true; showSettings = true },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(
                                    when (conn) {
                                        ConnState.Connected -> Color(0xFF4CAF50)
                                        ConnState.Connecting -> Color(0xFFFFC107)
                                        ConnState.Disconnected -> Color(0xFFF44336)
                                    }
                                )
                            )
                            Text(
                                "  " + when (conn) {
                                    ConnState.Connected -> listOfNotNull(crealityInfo?.modelName, settings.host).joinToString(" · ")
                                    ConnState.Connecting -> tr(R.string.connecting)
                                    ConnState.Disconnected -> tr(R.string.not_connected)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (conn == ConnState.Disconnected && settings.host.isNotBlank()) {
                        TextButton(onClick = { vm.reconnect() }) { Text(tr(R.string.connect)) }
                    }
                    IconButton(onClick = { tab = if (tab == CONSOLE_TAB) 0 else CONSOLE_TAB }) {
                        Icon(Icons.Default.Terminal, tr(R.string.console),
                            tint = if (tab == CONSOLE_TAB) Accent else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, tr(R.string.settings)) }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> {
                    val screenFault by vm.creality.fault.collectAsStateWithLifecycle()
                    val gcodeFault by vm.client.fault.collectAsStateWithLifecycle()
                    DashboardScreen(state, connected, settings, vm.client, pickFault(screenFault, gcodeFault, state),
                        crealityInfo?.webrtc, noCamera = crealityInfo?.camera == false)
                }
                1 -> ControlScreen(state, connected, vm.client, slicerVm)
                2 -> SlicerScreen(slicerVm, connected)
                3 -> FilesScreen(files, state, connected, vm.client)
                4 -> TimelapseScreen(vm, timelapses, settings.hostOnly())
                CONSOLE_TAB -> ConsoleScreen(log, connected, vm.client)
            }
        }
    }

    if (showSettings) {
        val adding = addingPrinter || settings.host.isBlank()
        SettingsDialog(
            initial = if (addingPrinter) AppSettings(slicerUrl = settings.slicerUrl, notifications = settings.notifications) else settings,
            adding = adding,
            canDelete = !adding && printers.size > 1,
            canCancel = printers.isNotEmpty(),
            onDismiss = { showSettings = false; addingPrinter = false },
            onDelete = {
                activeId?.let { vm.deletePrinter(it) }
                showSettings = false; slicerVm.reload()
                (context as? app.filum.MainActivity)?.startMonitor()
            },
            onSave = {
                val slicerChanged = it.slicerUrl != settings.slicerUrl || it.host != settings.host
                vm.saveSettings(it, asNew = addingPrinter); showSettings = false; addingPrinter = false
                if (slicerChanged) slicerVm.reload()
                (context as? app.filum.MainActivity)?.startMonitor()
            },
        )
    }
}

@Composable
private fun SettingsDialog(
    initial: AppSettings,
    adding: Boolean,
    canDelete: Boolean,
    canCancel: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var name by remember { mutableStateOf(initial.name) }
    var mode by remember { mutableStateOf(initial.cameraMode) }
    var camUrl by remember { mutableStateOf(initial.cameraUrl) }
    var slicerUrl by remember { mutableStateOf(initial.slicerUrl) }
    var notifications by remember { mutableStateOf(initial.notifications) }
    val preview = AppSettings(host.trim(), mode, "").resolvedCameraUrl()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) tr(R.string.new_printer) else tr(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(tr(R.string.printer_ip)) },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr(R.string.printer_name)) },
                    placeholder = { Text(tr(R.string.printer_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr(R.string.printer_ip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(tr(R.string.camera), style = MaterialTheme.typography.titleSmall)
                CameraMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { mode = m }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(m.title)
                    }
                }
                if (mode != CameraMode.OFF) {
                    OutlinedTextField(
                        value = camUrl,
                        onValueChange = { camUrl = it.trim() },
                        label = { Text(tr(R.string.camera_url)) },
                        placeholder = { Text(preview, maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(tr(R.string.tab_slicer), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = slicerUrl,
                    onValueChange = { slicerUrl = it.trim() },
                    label = { Text(tr(R.string.slicer_url)) },
                    placeholder = { Text("http://192.168.1.10:8090") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.notifications), style = MaterialTheme.typography.titleSmall)
                        Text(tr(R.string.notifications_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = notifications, onCheckedChange = { notifications = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(AppSettings(host.trim(), mode, camUrl.trim(),
                        slicerUrl.trim().ifBlank { SlicerViewModel.DEFAULT_SLICER_URL }, notifications, name.trim()))
                },
                enabled = host.isNotBlank(),
            ) { Text(tr(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text(tr(R.string.delete), color = MaterialTheme.colorScheme.error) }
                if (canCancel) TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
                else Box(Modifier.width(1.dp))
            }
        },
    )
}
