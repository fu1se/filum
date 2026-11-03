package app.filum.data

import org.json.JSONObject

/**
 * Устройство, которым управляет не Moonraker, а драйвер (плагин): например, ЧПУ на GRBL. Клиент принтера
 * в этом режиме отправляет движение, паузу/стоп, файлы и консоль сюда, а состояние получает через [DeviceListener].
 * Все вызовы асинхронные и не блокируют поток вызывающего.
 */
interface DeviceBackend {
    fun connect(address: String, listener: DeviceListener)
    fun disconnect()
    fun command(script: String)
    fun jog(axis: Char, distance: Double, feed: Int)
    /** [axes] — "" (все) или «X Y Z» через пробел. */
    fun home(axes: String)
    fun pause()
    fun resume()
    fun stop()
    fun files(onResult: (List<GcodeFile>?) -> Unit)
    fun start(path: String)
}

interface DeviceListener {
    /**
     * Состояние: connected, state (standby / printing / paused / complete / cancelled / error), status (свой текст
     * статуса, например «АВАРИЯ»), message, file, progress (%), position [x,y,z], homed («xyz»), printTime,
     * timeLeft (с), camera (URL потока MJPEG).
     */
    fun onState(state: JSONObject)
    fun onLog(dir: Char, text: String)
}
