package app.filum.ui

import app.filum.tr
import app.filum.R
import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import app.filum.data.MeshData
import app.filum.data.ToolpathData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Цвета типов линий (как в предпросмотре Orca). Индекс = код типа с сервера. */
val FeatureColors = listOf(
    Color(0xFF8A8A8A), // 0 прочее / стартовый код
    Color(0xFFFF8A3D), // 1 внешняя стенка
    Color(0xFFFFD24A), // 2 внутренняя стенка
    Color(0xFF3D6BFF), // 3 нависающая стенка
    Color(0xFFB03A2E), // 4 заполнение
    Color(0xFF9A3DBE), // 5 сплошное заполнение
    Color(0xFFFF4D4D), // 6 верх
    Color(0xFF6E8BFF), // 7 низ
    Color(0xFF4AA3B8), // 8 мост
    Color(0xFF3FCF8E), // 9 заполнение щелей
    Color(0xFF37A142), // 10 поддержка
    Color(0xFF1E7A3A), // 11 интерфейс поддержки
    Color(0xFF00C0C0), // 12 кайма / юбка
    Color(0xFFFF9DAA), // 13 разглаживание
    Color(0xFFBBBBBB), // 14 башня очистки
)

val FeatureNames = listOf(
    tr(R.string.feat_other), tr(R.string.feat_outer_wall), tr(R.string.feat_inner_wall), tr(R.string.feat_overhang), tr(R.string.feat_infill), tr(R.string.feat_solid_infill),
    tr(R.string.feat_top), tr(R.string.feat_bottom), tr(R.string.feat_bridge), tr(R.string.feat_gap), tr(R.string.feat_support), tr(R.string.feat_support_iface), tr(R.string.feat_skirt), tr(R.string.feat_ironing), tr(R.string.feat_tower),
)

/** Подготовленная сцена: буферы собраны вне GL-потока. */
sealed class Scene(val bedW: Float, val bedH: Float, val min: FloatArray, val max: FloatArray) {
    class Mesh(val buffer: FloatBuffer, val vertices: Int, val color: Color,
               bedW: Float, bedH: Float, min: FloatArray, max: FloatArray) : Scene(bedW, bedH, min, max)

    class Paths(val buffer: ByteBuffer, val layerEnd: IntArray, bedW: Float, bedH: Float,
                min: FloatArray, max: FloatArray) : Scene(bedW, bedH, min, max)
}

/** Сетка → вершины с нормалями; модель без привязки к столу ставится в его центр. */
fun prepareMesh(d: MeshData, bedW: Float, bedH: Float, recenter: Boolean, color: Color): Scene.Mesh {
    val outside = d.min[0] < 0 || d.min[1] < 0 || d.max[0] > bedW || d.max[1] > bedH
    val dx = if (recenter || outside) bedW / 2 - (d.min[0] + d.max[0]) / 2 else 0f
    val dy = if (recenter || outside) bedH / 2 - (d.min[1] + d.max[1]) / 2 else 0f
    val dz = -d.min[2]
    val n = d.triangles
    val buf = ByteBuffer.allocateDirect(n * 3 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val p = d.positions
    for (t in 0 until n) {
        val o = t * 9
        val ux = p[o + 3] - p[o]; val uy = p[o + 4] - p[o + 1]; val uz = p[o + 5] - p[o + 2]
        val vx = p[o + 6] - p[o]; val vy = p[o + 7] - p[o + 1]; val vz = p[o + 8] - p[o + 2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
        nx /= l; ny /= l; nz /= l
        for (k in 0 until 3) {
            buf.put(p[o + k * 3] + dx); buf.put(p[o + k * 3 + 1] + dy); buf.put(p[o + k * 3 + 2] + dz)
            buf.put(nx); buf.put(ny); buf.put(nz)
        }
    }
    buf.position(0)
    val min = floatArrayOf(d.min[0] + dx, d.min[1] + dy, 0f)
    val max = floatArrayOf(d.max[0] + dx, d.max[1] + dy, d.max[2] + dz)
    return Scene.Mesh(buf, n * 3, color, bedW, bedH, min, max)
}

/** Траектории → вершины (x,y,z float + цвет RGBA) для GL_LINES.
 *  Байт типа: младшие 4 бита — тип линии, старшие — инструмент (T0…T15).
 *  [toolColors] задан — красим цветами катушек по инструменту, иначе — по типу линии. */
fun preparePaths(d: ToolpathData, bedW: Float, bedH: Float, toolColors: List<Color>? = null): Scene.Paths {
    val verts = d.segments * 2
    val buf = ByteBuffer.allocateDirect(verts * 16).order(ByteOrder.nativeOrder())
    val colors = FeatureColors.map { c ->
        intArrayOf((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    }
    val tc = toolColors?.map { c -> intArrayOf((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()) }
    val p = d.positions
    for (i in 0 until d.segments) {
        val b = d.types[i].toInt() and 0xFF
        val feature = b and 0x0F
        val c = if (tc != null && feature != 0) tc.getOrElse(b shr 4) { colors[0] }
                else colors.getOrElse(feature) { colors[0] }
        for (k in 0 until 2) {
            val o = i * 6 + k * 3
            buf.putFloat(p[o]); buf.putFloat(p[o + 1]); buf.putFloat(p[o + 2])
            buf.put(c[0].toByte()); buf.put(c[1].toByte()); buf.put(c[2].toByte()); buf.put(255.toByte())
        }
    }
    buf.position(0)
    // Кадрируем по модели: стартовый код (тип 0) рисует линию прочистки по краю стола
    val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, 0f)
    val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, d.layerZ.lastOrNull() ?: d.max[2])
    for (i in 0 until d.segments) {
        if (d.types[i].toInt() and 0x0F == 0) continue
        val o = i * 6
        min[0] = min(min[0], min(p[o], p[o + 3])); max[0] = max(max[0], max(p[o], p[o + 3]))
        min[1] = min(min[1], min(p[o + 1], p[o + 4])); max[1] = max(max[1], max(p[o + 1], p[o + 4]))
    }
    if (min[0] > max[0]) { min[0] = 0f; min[1] = 0f; max[0] = bedW; max[1] = bedH }
    return Scene.Paths(buf, d.layerEnd, bedW, bedH, min, max)
}

@Composable
fun Viewer3D(scene: Scene?, layer: Int, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ViewerSurface(it) },
        update = { v ->
            v.setScene(scene)
            v.setLayer(layer)
        },
        onRelease = { it.onPause() },
    )
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ViewerSurface(context: Context) : GLSurfaceView(context) {
    private val renderer = ViewerRenderer()
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var pointers = 0
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.zoom(d.scaleFactor)
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 4)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(scene: Scene?) {
        if (renderer.scene === scene) return
        queueEvent { renderer.load(scene) }
        requestRender()
    }

    fun setLayer(layer: Int) {
        if (renderer.layer == layer) return
        renderer.layer = layer
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; pointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointers = e.pointerCount
                lastMidX = (e.getX(0) + e.getX(1)) / 2; lastMidY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pointers = e.pointerCount - 1
                val idx = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(idx); lastY = e.getY(idx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointers >= 2 && e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    renderer.pan(mx - lastMidX, my - lastMidY, height)
                    lastMidX = mx; lastMidY = my
                } else if (pointers == 1 && !scaleDetector.isInProgress) {
                    renderer.rotate(e.x - lastX, e.y - lastY)
                    lastX = e.x; lastY = e.y
                }
                requestRender()
            }
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

private class ViewerRenderer : GLSurfaceView.Renderer {
    @Volatile var scene: Scene? = null
    @Volatile var layer: Int = Int.MAX_VALUE

    private var meshProg = 0
    private var lineProg = 0
    private var vbo = 0
    private var bedVbo = 0
    private var bedVertices = 0
    private var loaded: Scene? = null
    private var lineWidth = 1f

    // камера
    @Volatile private var yaw = -30f
    @Volatile private var pitch = 35f
    @Volatile private var dist = 500f
    private val target = floatArrayOf(175f, 175f, 0f)
    private var aspect = 1f
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun rotate(dx: Float, dy: Float) {
        yaw -= dx * 0.3f
        pitch = (pitch + dy * 0.3f).coerceIn(-10f, 89f)
    }

    fun zoom(f: Float) {
        dist = (dist / f).coerceIn(10f, 3000f)
    }

    fun pan(dx: Float, dy: Float, height: Int) {
        val k = dist * 0.0018f * (1080f / max(height, 1))
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val cy = cos(yr).toFloat(); val sy = sin(yr).toFloat()
        val cp = cos(pr).toFloat(); val sp = sin(pr).toFloat()
        // R — «вправо» камеры, U — «вверх» камеры; сцена следует за пальцами
        target[0] += -cy * dx * k + (-sy * sp) * dy * k
        target[1] += -sy * dx * k + (cy * sp) * dy * k
        target[2] += cp * dy * k
    }

    fun load(s: Scene?) {
        scene = s
        if (s != null && s !== loaded) {
            // кадрируем модель
            target[0] = (s.min[0] + s.max[0]) / 2
            target[1] = (s.min[1] + s.max[1]) / 2
            target[2] = (s.min[2] + s.max[2]) / 3
            fitSize = max(max(s.max[0] - s.min[0], s.max[1] - s.min[1]), s.max[2] - s.min[2]).coerceAtLeast(20f)
            needFit = true
        }
    }

    // Кадрирование ждёт размеров вида: на узком экране решает горизонтальный угол обзора
    @Volatile private var needFit = false
    private var fitSize = 100f
    private fun fit() {
        val half = Math.toRadians(20.0)
        val hHalf = kotlin.math.atan(kotlin.math.tan(half) * aspect)
        val use = min(half, hHalf)
        dist = (fitSize * 0.62f / kotlin.math.tan(use).toFloat()).coerceIn(10f, 3000f)
        needFit = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.08f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        meshProg = program(MESH_VS, MESH_FS)
        lineProg = program(LINE_VS, LINE_FS)
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]; bedVbo = ids[1]
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        lineWidth = min(3f, max(1f, range[1]))
        loaded = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / max(h, 1)
    }

    private fun upload(s: Scene) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        when (s) {
            is Scene.Mesh -> GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.vertices * 24, s.buffer, GLES20.GL_STATIC_DRAW)
            is Scene.Paths -> GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.buffer.capacity(), s.buffer, GLES20.GL_STATIC_DRAW)
        }
        // сетка стола 50 мм + рамка
        val lines = ArrayList<Float>()
        val w = s.bedW; val h = s.bedH
        var x = 0f
        while (x <= w + 0.1f) { lines += listOf(x, 0f, 0f, x, h, 0f); x += 50f }
        var y = 0f
        while (y <= h + 0.1f) { lines += listOf(0f, y, 0f, w, y, 0f); y += 50f }
        val bb = ByteBuffer.allocateDirect(lines.size / 3 * 16).order(ByteOrder.nativeOrder())
        for (i in lines.indices step 3) {
            bb.putFloat(lines[i]); bb.putFloat(lines[i + 1]); bb.putFloat(lines[i + 2])
            bb.put(70); bb.put(76); bb.put(84); bb.put(255.toByte())
        }
        bb.position(0)
        bedVertices = lines.size / 3
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bb.capacity(), bb, GLES20.GL_STATIC_DRAW)
        loaded = s
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = scene ?: return
        if (s !== loaded) upload(s)
        if (needFit) fit()

        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val ex = target[0] + (dist * cos(pr) * sin(yr)).toFloat()
        val ey = target[1] - (dist * cos(pr) * cos(yr)).toFloat()
        val ez = target[2] + (dist * sin(pr)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 40f, aspect, max(1f, dist * 0.02f), dist * 6f + 800f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        // стол
        GLES20.glUseProgram(lineProg)
        GLES20.glLineWidth(1f)
        drawLines(bedVbo, 0, bedVertices, 1f)

        when (s) {
            is Scene.Mesh -> {
                GLES20.glUseProgram(meshProg)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProg, "uMVP"), 1, false, mvp, 0)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(meshProg, "uColor"), s.color.red, s.color.green, s.color.blue)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                val aPos = GLES20.glGetAttribLocation(meshProg, "aPos")
                val aNrm = GLES20.glGetAttribLocation(meshProg, "aNormal")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glEnableVertexAttribArray(aNrm)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
                GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 24, 12)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, s.vertices)
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aNrm)
            }
            is Scene.Paths -> {
                GLES20.glUseProgram(lineProg)
                GLES20.glLineWidth(lineWidth)
                val n = s.layerEnd.size
                if (n > 0) {
                    val l = layer.coerceIn(0, n - 1)
                    val prevEnd = if (l > 0) s.layerEnd[l - 1] else 0
                    // нижние слои приглушены, текущий — ярко
                    drawLines(vbo, 0, prevEnd * 2, 0.55f)
                    drawLines(vbo, prevEnd * 2, (s.layerEnd[l] - prevEnd) * 2, 1f)
                }
            }
        }
    }

    private fun drawLines(buffer: Int, first: Int, count: Int, dim: Float) {
        if (count <= 0) return
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(lineProg, "uDim"), dim)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        val aPos = GLES20.glGetAttribLocation(lineProg, "aPos")
        val aCol = GLES20.glGetAttribLocation(lineProg, "aColor")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, 16, 12)
        GLES20.glDrawArrays(GLES20.GL_LINES, first, count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        const val MESH_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying float vLight;
            void main() {
                vec3 l = normalize(vec3(0.35, -0.55, 0.76));
                vec3 l2 = normalize(vec3(-0.6, 0.4, 0.3));
                vec3 n = normalize(aNormal);
                vLight = 0.28 + 0.62 * abs(dot(n, l)) + 0.15 * abs(dot(n, l2));
                gl_Position = uMVP * vec4(aPos, 1.0);
            }"""
        const val MESH_FS = """
            precision mediump float;
            uniform vec3 uColor;
            varying float vLight;
            void main() { gl_FragColor = vec4(uColor * vLight, 1.0); }"""
        const val LINE_VS = """
            uniform mat4 uMVP;
            uniform float uDim;
            attribute vec3 aPos;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                vColor = vec4(aColor.rgb * uDim, 1.0);
                gl_Position = uMVP * vec4(aPos, 1.0);
            }"""
        const val LINE_FS = """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vColor; }"""
    }
}
