package app.filum.ui

import app.filum.tr
import app.filum.R
import app.filum.data.ActiveFault
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.filum.AppSettings
import app.filum.CameraMode
import app.filum.data.PrinterClient
import app.filum.data.PrinterState

@Composable
fun DashboardScreen(
    state: PrinterState, connected: Boolean, settings: AppSettings, client: PrinterClient,
    fault: ActiveFault? = null,
    /** Поддержка WebRTC по данным служб Creality — для режима камеры «Авто». */
    webrtc: Boolean? = null,
    /** Службы Creality сообщили, что камеры нет (K1 SE, Ender-3 V3): в режиме «Авто» блок не показываем. */
    noCamera: Boolean = false,
) {
    val camMode = settings.effectiveCameraMode(webrtc)
    val camUrl = settings.resolvedCameraUrl(webrtc)
    var fullscreen by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf<Long?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fault != null && fault.since != dismissed) FaultCard(fault) { dismissed = fault.since }

        val showCamera = settings.cameraMode != CameraMode.OFF && settings.host.isNotBlank() &&
            !(settings.cameraMode == CameraMode.AUTO && noCamera)
        if (showCamera) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            ) {
                // Второй поток камера K2 может не выдать, поэтому в полноэкранном режиме маленькую выгружаем
                if (!fullscreen) {
                    CameraView(camMode, camUrl, reloadKey, Modifier.fillMaxSize())
                }
                Row(Modifier.align(Alignment.TopEnd)) {
                    OverlayIcon(Icons.Default.Refresh) { reloadKey++ }
                    OverlayIcon(Icons.Default.Fullscreen) { fullscreen = true }
                }
            }
        }

        Section(tr(R.string.print_section)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.statusText,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = when {
                        state.isError -> MaterialTheme.colorScheme.error
                        state.hasActiveJob -> Accent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                state.progress?.let { Text("$it%", style = MaterialTheme.typography.titleLarge) }
            }
            (state.printMessage ?: state.klippyMessage?.takeIf { state.isError })?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.fileName?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            LinearProgressIndicator(
                progress = { (state.progress ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                InfoCell(tr(R.string.elapsed), fmtDuration(state.jobTimeSec), Modifier.weight(1f))
                InfoCell(tr(R.string.remaining), fmtDuration(state.leftTimeSec), Modifier.weight(1f))
                InfoCell(
                    tr(R.string.layer),
                    if (state.layer != null) "${state.layer} / ${state.totalLayers ?: "?"}" else "—",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { client.pause() },
                    enabled = connected && state.isPrinting,
                    modifier = Modifier.weight(1f),
                    contentPadding = BtnPadding,
                ) { ActionLabel(Icons.Default.Pause, tr(R.string.pause)) }
                FilledTonalButton(
                    onClick = { client.resume() },
                    enabled = connected && state.isPaused,
                    modifier = Modifier.weight(1f),
                    contentPadding = BtnPadding,
                ) { ActionLabel(Icons.Default.PlayArrow, tr(R.string.resume_short)) }
                Button(
                    onClick = { confirmStop = true },
                    enabled = connected && state.hasActiveJob,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                    contentPadding = BtnPadding,
                ) { ActionLabel(Icons.Default.Stop, tr(R.string.stop)) }
            }
        }

        Section(tr(R.string.temperatures)) {
            Row {
                InfoCell(tr(R.string.nozzle), "${fmtTemp(state.nozzleTemp)} / ${fmtTemp(state.nozzleTarget)}", Modifier.weight(1f))
                InfoCell(tr(R.string.bed), "${fmtTemp(state.bedTemp)} / ${fmtTemp(state.bedTarget)}", Modifier.weight(1f))
                if (state.caps.chamberTemp) InfoCell(tr(R.string.chamber), fmtTemp(state.chamberTemp), Modifier.weight(1f))
            }
        }

        Section(tr(R.string.speed_and_flow)) {
            PercentSlider(tr(R.string.speed), state.speedPct, 10..300, connected) { client.setSpeedPct(it) }
            PercentSlider(tr(R.string.flow), state.flowPct, 50..150, connected) { client.setFlowPct(it) }
        }

    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CameraView(camMode, camUrl, reloadKey, Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    OverlayIcon(Icons.Default.FullscreenExit) { fullscreen = false }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(tr(R.string.stop_print_q)) },
            text = { Text(tr(R.string.stop_print_desc)) },
            confirmButton = {
                TextButton(onClick = { client.stop(); confirmStop = false }) {
                    Text(tr(R.string.stop_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** Узкие отступы: три кнопки в ряд, подписи на английском длиннее. */
private val BtnPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun ActionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(4.dp))
    Text(text, maxLines = 1, softWrap = false)
}

@Composable
private fun OverlayIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
        modifier = Modifier.padding(4.dp),
    ) { Icon(icon, null) }
}

@Composable
fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
