package app.filum.ui

import app.filum.tr
import app.filum.R
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.filum.PrinterViewModel
import app.filum.data.Timelapse

@Composable
fun TimelapseScreen(vm: PrinterViewModel, ui: PrinterViewModel.TimelapseUi, host: String) {
    var playing by remember { mutableStateOf<Timelapse?>(null) }

    LaunchedEffect(host) { if (ui.items == null) vm.loadTimelapses() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.items?.let { tr(R.string.timelapses_count, it.size) } ?: tr(R.string.tab_timelapses),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalButton(onClick = { vm.loadTimelapses() }, enabled = !ui.loading && host.isNotBlank()) {
                Icon(Icons.Default.Refresh, null); Text(tr(R.string.refresh_spaced))
            }
        }
        HorizontalDivider()
        when {
            ui.loading && ui.items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null && ui.items == null -> Message(tr(R.string.list_failed, ui.error))
            ui.items.isNullOrEmpty() -> Message(tr(R.string.timelapses_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.items, key = { it.id }) { t ->
                    TimelapseCard(vm, host, t, onPlay = { playing = t }, onDownload = { vm.downloadTimelapse(t) })
                }
            }
        }
    }

    playing?.let { t ->
        VideoPlayerDialog(vm, host, t, onClose = { playing = null })
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelapseCard(
    vm: PrinterViewModel,
    host: String,
    t: Timelapse,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val thumb by produceState<android.graphics.Bitmap?>(null, t.id, host) {
        value = vm.timelapseRepo.thumbnail(host, t)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0E1013)), contentAlignment = Alignment.Center) {
            thumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            }
            Icon(
                Icons.Default.PlayCircle, null,
                Modifier.size(44.dp).align(Alignment.Center),
                tint = Color.White.copy(alpha = 0.85f),
            )
            Text(
                tr(R.string.seconds_n, t.durationSec),
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.gcodeName.removeSuffix(".gcode"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t.dateTime.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr(R.string.timelapse_meta, fmtDuration(t.printTimeSec), fmtMb(t.sizeBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload) { Icon(Icons.Default.Download, tr(R.string.download)) }
        }
    }
}

private fun fmtMb(bytes: Long) = tr(R.string.size_mb).format(bytes / 1048576.0)

private sealed interface VideoLoad {
    data class Loading(val progress: Float) : VideoLoad
    data class Ready(val file: java.io.File) : VideoLoad
    data class Failed(val message: String) : VideoLoad
}

@Composable
private fun VideoPlayerDialog(vm: PrinterViewModel, host: String, t: Timelapse, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val load by produceState<VideoLoad>(VideoLoad.Loading(0f), t.id) {
        value = try {
            VideoLoad.Ready(vm.timelapseRepo.cachedVideo(context.cacheDir, host, t) { value = VideoLoad.Loading(it) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VideoLoad.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    val title = t.gcodeName
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val l = load) {
                is VideoLoad.Loading -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(tr(R.string.video_loading_pct, (l.progress * 100).toInt()), color = Color.White)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { l.progress }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                is VideoLoad.Failed -> Text(
                    tr(R.string.video_failed, l.message),
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = Color.White, textAlign = TextAlign.Center,
                )
                is VideoLoad.Ready -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { mp -> mp.isLooping = true; start(); controller.show(2000) }
                            setVideoURI(Uri.fromFile(l.file))
                        }
                    },
                    onRelease = { it.stopPlayback() },
                )
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, Modifier.weight(1f).padding(start = 12.dp),
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) { Icon(Icons.Default.Close, tr(R.string.close)) }
            }
        }
    }
}
