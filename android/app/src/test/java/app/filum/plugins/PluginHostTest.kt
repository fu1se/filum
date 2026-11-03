package app.filum.plugins

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PluginHostTest {

    private class Fake : PluginServices {
        val sent = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val storage = mutableMapOf<String, String>()
        var state = JSONObject().put("printState", "standby")
        override fun sendGcode(script: String) { sent += script }
        override fun query(objects: List<String>, timeoutMs: Long) = JSONObject().put("extruder", JSONObject().put("temperature", 215.5))
        override fun state() = state
        override fun toast(text: String) { logs += "toast:$text" }
        override fun notify(title: String, text: String) { logs += "notify:$title" }
        val requests = mutableListOf<String>()
        var httpHandler: (String, String, String?) -> Pair<Int, String> = { _, _, _ -> 200 to "ok" }
        override fun http(method: String, url: String, body: String?, contentType: String?): Pair<Int, String> {
            requests += "$method $url ${body.orEmpty()}".trim()
            return httpHandler(method, url, body)
        }
        override fun storageGet(pluginId: String, key: String) = storage["$pluginId/$key"]
        override fun storageSet(pluginId: String, key: String, json: String?) { if (json == null) storage.remove("$pluginId/$key") else storage["$pluginId/$key"] = json }
        override fun log(pluginId: String, level: Char, text: String) { logs += "$level[$pluginId] $text" }
    }

    private lateinit var fake: Fake
    private lateinit var host: PluginHost

    @Before fun setUp() { fake = Fake(); host = PluginHost(fake) }
    @After fun tearDown() { host.shutdown() }

    private fun load(src: String): String? {
        var err: String? = "not called"
        val l = CountDownLatch(1)
        host.load(src) { err = it; l.countDown() }
        assertTrue(l.await(15, TimeUnit.SECONDS))
        return err
    }

    private fun <T> await(block: ((T) -> Unit) -> Unit): T {
        var out: Any? = null
        val l = CountDownLatch(1)
        block { out = it; l.countDown() }
        assertTrue(l.await(15, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun header(id: String, vararg grants: String) =
        "// @plugin $id\n// @name Test $id\n" + grants.joinToString("") { "// @grant $it\n" } + "\n"

    @Test fun parsesHeader() {
        val m = PluginMeta.parse("// @plugin my-plugin\n// @name My\n// @version 1.2\n// @grant gcode, http\n// @description a\n// @description b\ncode()")
        assertEquals("my-plugin", m.id)
        assertEquals("My", m.name)
        assertEquals(setOf("gcode", "http"), m.grants)
        assertEquals("a b", m.description)
        assertTrue(runCatching { PluginMeta.parse("var x = 1") }.isFailure)
        assertTrue(runCatching { PluginMeta.parse("// @plugin Bad Id") }.isFailure)
        assertTrue(runCatching { PluginMeta.parse("// @plugin ok\n// @grant root") }.isFailure)
    }

    @Test fun gcodeSendCanChangeAndBlock() {
        assertNull(load(header("send", "gcode") + """
            filum.on('gcode.send', s => s.startsWith('M104') ? s + ' ; via plugin' : undefined)
            filum.on('gcode.send', function (s) { if (s === 'M112') return false })
        """))
        assertEquals("M104 S200 ; via plugin", await<String?> { host.filterGcode("M104 S200", it) })
        assertEquals("G28", await<String?> { host.filterGcode("G28", it) })
        assertNull(await<String?> { host.filterGcode("M112", it) })
    }

    @Test fun hooksNeedGrant() {
        val err = load(header("nogrant") + "filum.on('gcode.send', s => s)")
        assertNotNull(err)
        assertTrue(err!!, err.contains("gcode"))
        assertFalse(host.hasHook("gcode.send"))
        assertNotNull(load(header("nogrant2") + "filum.printer.gcode('G28')"))
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun gcodeFileAndSlice() {
        assertNull(load(header("pa", "gcode") + """
            filum.on('gcode.file', (text, info) => text.split('\n').map(l =>
                l.startsWith('EXCLUDE_OBJECT_START NAME=PA_') ? l + '\nSET_PRESSURE_ADVANCE ADVANCE=' + l.split('PA_')[1].slice(0, 4) : l
            ).join('\n') + '\n; ' + info.file)
            filum.on('slice', r => { r.arrange = false; r.plate = 2; return r })
        """))
        val out = await<String> { host.transformGcodeFile("G1\nEXCLUDE_OBJECT_START NAME=PA_0.05.stl_id_1\nG1 X1", JSONObject().put("file", "x.gcode"), it) }
        assertEquals("G1\nEXCLUDE_OBJECT_START NAME=PA_0.05.stl_id_1\nSET_PRESSURE_ADVANCE ADVANCE=0.05\nG1 X1\n; x.gcode", out)
        val req = await<JSONObject> { host.transformSlice(JSONObject().put("arrange", true).put("plate", 1).put("model_id", "m"), it) }
        assertEquals(false, req.getBoolean("arrange"))
        assertEquals(2, req.getInt("plate"))
        assertEquals("m", req.getString("model_id"))
    }

    @Test fun printStartCanBeBlocked() {
        assertNull(load(header("guard", "gcode") + "filum.on('print.start', f => !f.includes('test'))"))
        assertTrue(await<Boolean> { host.allowPrint("benchy.gcode", it) })
        assertFalse(await<Boolean> { host.allowPrint("test.gcode", it) })
    }

    @Test fun noJavaAccessAndTimeouts() {
        assertNotNull(load(header("java") + "java.lang.System.exit(1)"))
        assertNotNull(load(header("pkg") + "Packages.java.io.File"))
        val err = load(header("loop") + "while (true) {}")
        assertTrue(err!!, err.contains("timeout"))
        // зависший хук не блокирует данные: идут дальше без изменений
        assertNull(load(header("slow", "gcode") + "filum.on('gcode.send', s => { while (true) {} })"))
        assertEquals("G28", await<String?> { host.filterGcode("G28", it) })
    }

    @Test fun eventsStorageAndUi() {
        assertNull(load(header("ev", "gcode") + """
            filum.on('print.started', f => filum.printer.gcode('M117 start ' + f))
            filum.on('print.finished', f => { filum.storage.set('count', filum.storage.get('count', 0) + 1) })
            const t = filum.printer.query(['extruder']).extruder.temperature
            const sid = filum.ui.section('Quick', [
                { type: 'text', text: 'T=' + t },
                { type: 'button', label: 'Home', onClick: () => filum.printer.gcode('G28') },
                { type: 'slider', label: 'Fan', min: 0, max: 100, step: 5, value: 50, onChange: v => filum.printer.gcode('M106 S' + Math.round(v * 2.55)) },
            ])
        """))
        val s = host.sections.value.single()
        assertEquals("Quick", s.title)
        assertEquals(PluginItem.Text("T=215.5"), s.items[0])
        host.uiEvent("ev", s.id, 1, null)
        host.uiEvent("ev", s.id, 2, 100.0)
        host.flush()
        assertEquals(listOf("G28", "M106 S255"), fake.sent)

        fake.sent.clear()
        host.onState(JSONObject().put("printState", "standby")); host.flush()
        host.onState(JSONObject().put("printState", "printing").put("fileName", "cube.gcode")); host.flush()
        host.onState(JSONObject().put("printState", "complete").put("fileName", "cube.gcode")); host.flush()
        assertEquals(listOf("M117 start cube.gcode"), fake.sent)
        assertEquals("1", fake.storage["ev/count"])

        host.unload("ev"); host.flush()
        assertTrue(host.sections.value.isEmpty())
    }

    @Test fun bundledExamplesLoad() {
        val files = java.io.File("src/main/assets/plugins").listFiles { f -> f.extension == "js" }.orEmpty()
        assertTrue(files.size >= 4)
        for (f in files) assertNull(f.name, load(f.readText()))
        val gcode = "G1 X1\nEXCLUDE_OBJECT_START NAME=PA_0.07.stl_id_3_copy_0\nG1 X2\nEXCLUDE_OBJECT_END NAME=PA_0.07.stl_id_3_copy_0"
        val out = await<String> { host.transformGcodeFile(gcode, JSONObject().put("file", "pa.gcode"), it) }
        assertTrue(out, out.contains("EXCLUDE_OBJECT_START NAME=PA_0.07.stl_id_3_copy_0\nSET_PRESSURE_ADVANCE ADVANCE=0.07"))
        assertEquals("M104 S240", await<String?> { host.filterGcode("M104 S240", it) })
        assertNull(await<String?> { host.filterGcode("M104 S400", it) })
        assertEquals(3, host.sections.value.size)  // quick-actions, temperature-guard, print-webhook
    }

    @Test fun deviceDriver() {
        val driver = header("toy", "device", "http") + """
            let pos = [0, 0, 0], addr = '', running = null
            const send = extra => filum.device.update(Object.assign({ connected: true, state: running ? 'printing' : 'standby',
                status: 'Toy ' + addr, position: pos, file: running }, extra || {}))
            filum.device.register({
              name: 'Toy machine', addressHint: '10.0.0.1:80',
              connect(a) { addr = a; send() },
              disconnect() { addr = '' },
              command(s) { filum.device.log('<', 'ok ' + s) },
              jog(axis, d, feed) { pos['XYZ'.indexOf(axis)] += d; send() },
              home() { pos = [0, 0, 0]; send() },
              files() { return [{ path: 'part.nc', size: 10 }] },
              start(p) { running = p; send({ progress: 0 }) },
              stop() {
                try { filum.http.get('http://down/') } catch (e) { send({ message: 'caught: ' + e.message }); return }
              },
            })
        """
        val states = mutableListOf<JSONObject>()
        val logs = mutableListOf<String>()
        val listener = object : app.filum.data.DeviceListener {
            override fun onState(state: JSONObject) { synchronized(states) { states += state } }
            override fun onLog(dir: Char, text: String) { synchronized(logs) { logs += "$dir $text" } }
        }
        // устройство выбрано раньше, чем загрузился плагин: подключится после register()
        host.deviceConnect("toy", "10.0.0.7:80", listener); host.flush()
        assertFalse(synchronized(states) { states.last() }.getBoolean("connected"))
        assertNull(load(driver)); host.flush()
        assertEquals(listOf(DeviceType("toy", "Toy machine", "10.0.0.1:80")), host.devices.value)
        assertEquals("Toy 10.0.0.7:80", synchronized(states) { states.last() }.getString("status"))

        host.deviceCall("toy", "jog", arrayOf("Y", 2.5, 1000)); host.flush()
        assertEquals(2.5, synchronized(states) { states.last() }.getJSONArray("position").getDouble(1), 1e-9)
        host.deviceCall("toy", "command", arrayOf("G0 X1")); host.flush()
        assertEquals(listOf("< ok G0 X1"), synchronized(logs) { logs.toList() })
        val files = await<Any?> { cb -> host.deviceCall("toy", "files", emptyArray(), cb) } as org.json.JSONArray
        assertEquals("part.nc", files.getJSONObject(0).getString("path"))
        host.deviceCall("toy", "start", arrayOf("part.nc")); host.flush()
        assertEquals("printing", synchronized(states) { states.last() }.getString("state"))

        // ошибка сервиса (сеть) — обычный JS Error, драйвер его ловит
        fake.httpHandler = { _, _, _ -> throw java.io.IOException("no route to host") }
        host.deviceCall("toy", "stop", emptyArray()); host.flush()
        assertEquals("caught: no route to host", synchronized(states) { states.last() }.getString("message"))

        // плагин выключили — устройство «не подключено», тип пропал из списка
        host.unload("toy"); host.flush()
        assertFalse(synchronized(states) { states.last() }.getBoolean("connected"))
        assertTrue(host.devices.value.isEmpty())
        // без разрешения device зарегистрировать устройство нельзя
        assertNotNull(load(header("nodev") + "filum.device.register({ connect() {} })"))
    }
}
