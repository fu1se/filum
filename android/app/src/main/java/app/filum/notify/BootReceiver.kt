package app.filum.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Мониторинг печати после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (PrintMonitorService.enabled(context)) runCatching { PrintMonitorService.sync(context) }
        }
    }
}
