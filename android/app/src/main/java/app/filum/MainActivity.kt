package app.filum

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import app.filum.notify.PrintMonitorService
import app.filum.ui.App
import app.filum.ui.K2Theme

class MainActivity : ComponentActivity() {
    private val vm: PrinterViewModel by viewModels()
    private val slicerVm: SlicerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        // Экран не гаснет, пока смотрим на камеру/печать
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val theme = vm.settings.collectAsState().value.theme
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            K2Theme(dark) { App(vm, slicerVm) }
        }
        if (savedInstanceState == null) handleIntent(intent)
        startMonitor()
    }

    private val notifPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            PrintMonitorService.sync(this)
        }

    /** Уведомления о печати: разрешение (Android 13+) и фоновый сервис. */
    fun startMonitor() {
        if (!PrintMonitorService.enabled(this)) { PrintMonitorService.sync(this); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else PrintMonitorService.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** STL/3MF из других приложений («Поделиться» или «Открыть с помощью») → слайсер. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            slicerVm.importUri(uri)
            slicerVm.openSlicerTab.value = true
        }
    }
}
