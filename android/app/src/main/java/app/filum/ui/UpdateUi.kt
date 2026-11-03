package app.filum.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.filum.R
import app.filum.tr
import app.filum.update.AppRelease
import app.filum.update.AppUpdater
import app.filum.update.Versions
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateStep {
    data object Checking : UpdateStep
    data object UpToDate : UpdateStep
    data class Available(val r: AppRelease) : UpdateStep
    data class Downloading(val r: AppRelease, val progress: Float) : UpdateStep
    data class Ready(val r: AppRelease, val apk: File, val sameKey: Boolean?) : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

private const val PREFS = "updates"
private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

/** Тихая проверка при запуске: не чаще раза в 12 ч; null — показывать нечего (нет новой или версия пропущена). */
suspend fun backgroundUpdateCheck(ctx: Context): AppRelease? {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (System.currentTimeMillis() - p.getLong("checked", 0) < CHECK_EVERY_MS) return null
    val r = runCatching { AppUpdater.latest() }.getOrNull() ?: return null
    p.edit().putLong("checked", System.currentTimeMillis()).apply()
    return r.takeIf { Versions.isNewer(it.version, AppUpdater.current) && it.version != p.getString("skipped", null) }
}

/**
 * Обновление приложения. [found] — релиз, найденный тихой проверкой; null — проверить сейчас (кнопка в настройках).
 */
@Composable
fun UpdateDialog(found: AppRelease?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<UpdateStep>(found?.let { UpdateStep.Available(it) } ?: UpdateStep.Checking) }
    // разрешение на установку меняют в системных настройках — перепроверяем при каждом возвращении в приложение
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumes++; onPauseOrDispose {} }
    val canInstall = remember(resumes) { AppUpdater.canInstall(ctx) }

    LaunchedEffect(Unit) {
        if (step != UpdateStep.Checking) return@LaunchedEffect
        step = try {
            val r = AppUpdater.latest()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis()).apply()
            if (r != null && Versions.isNewer(r.version, AppUpdater.current)) UpdateStep.Available(r) else UpdateStep.UpToDate
        } catch (e: Exception) {
            UpdateStep.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun download(r: AppRelease) = scope.launch {
        step = UpdateStep.Downloading(r, 0f)
        step = try {
            val apk = AppUpdater.download(ctx, r) { p -> step = UpdateStep.Downloading(r, p) }
            UpdateStep.Ready(r, apk, AppUpdater.signatureMatches(ctx, apk))
        } catch (e: Exception) {
            UpdateStep.Failed(tr(R.string.update_download_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    val busy = step is UpdateStep.Checking || step is UpdateStep.Downloading
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = {
            Text(when (val s = step) {
                is UpdateStep.Available -> tr(R.string.update_available, s.r.version)
                is UpdateStep.Downloading -> tr(R.string.update_downloading, s.r.version)
                is UpdateStep.Ready -> tr(R.string.update_ready, s.r.version)
                else -> tr(R.string.update_title)
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = step) {
                    UpdateStep.Checking -> CircularProgressIndicator()
                    UpdateStep.UpToDate -> Text(tr(R.string.update_latest, AppUpdater.current))
                    is UpdateStep.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is UpdateStep.Available -> {
                        Text(tr(R.string.update_current, AppUpdater.current, s.r.version, s.r.apkSize / 1_000_000.0),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.r.notes.isNotBlank()) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(s.r.notes.replace(Regex("[#*`]"), "").trim(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is UpdateStep.Downloading ->
                        if (s.progress >= 0) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    is UpdateStep.Ready -> when {
                        s.sameKey == false -> Text(tr(R.string.update_other_key), color = MaterialTheme.colorScheme.error)
                        !canInstall -> Text(tr(R.string.update_need_permission))
                        else -> Text(tr(R.string.update_install_hint))
                    }
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                is UpdateStep.Available -> TextButton(onClick = { download(s.r) }) { Text(tr(R.string.update_now)) }
                is UpdateStep.Ready -> when {
                    s.sameKey == false -> TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.r.page)))
                    }) { Text(tr(R.string.update_open_page)) }
                    !canInstall -> TextButton(onClick = { ctx.startActivity(AppUpdater.installPermissionIntent(ctx)) }) {
                        Text(tr(R.string.update_allow))
                    }
                    else -> TextButton(onClick = { AppUpdater.install(ctx, s.apk) }) { Text(tr(R.string.update_install)) }
                }
                is UpdateStep.Failed -> TextButton(onClick = { step = UpdateStep.Checking; onClose() }) { Text(tr(R.string.close)) }
                else -> {}
            }
        },
        dismissButton = {
            if (!busy) androidx.compose.foundation.layout.Row {
                (step as? UpdateStep.Available)?.let { s ->
                    TextButton(onClick = {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", s.r.version).apply()
                        onClose()
                    }) { Text(tr(R.string.update_skip)) }
                }
                TextButton(onClick = onClose) {
                    Text(if (step is UpdateStep.Available) tr(R.string.update_later) else tr(R.string.close))
                }
            }
        },
    )
}
