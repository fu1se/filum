package app.filum

import android.app.Application
import androidx.annotation.StringRes
import app.filum.plugins.Plugins

/** Приложение: даёт контекст для строковых ресурсов вне Compose (ViewModel, сервис, справочник ошибок). */
class FilumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // без аргументов — строка как есть: в некоторых свой формат («%.1f мм».format(d))
        Tr.resolve = { id, args -> if (args.isEmpty()) applicationContext.getString(id) else applicationContext.getString(id, *args) }
        Plugins.init(this)
    }
}

/** Как получать строки: в приложении — из ресурсов, в юнит-тестах — подменяется. */
object Tr {
    @Volatile var resolve: (Int, Array<out Any?>) -> String = { id, _ -> "#$id" }
}

/** Строка из ресурсов на языке приложения; работает и вне Compose. */
fun tr(@StringRes id: Int, vararg args: Any?): String = Tr.resolve(id, args)

