package com.venice.ffesp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tan

/* =========================================================================
 *  OFFSETS  —  EDITE SOMENTE ESTE BLOCO QUANDO O FREE FIRE ATUALIZAR
 *  RVA = endereço relativo ao início de libil2cpp.so (veja OFFSETS.md)
 *  0x0 = "não tenho / não usar"
 * ========================================================================= */
object O {
    val PACKAGES = listOf("com.dts.freefireth", "com.dts.freefiremax", "com.garena.game.ffvn")

    // --- jogador ---------------------------------------------------------
    var PLAYER_LIST_RVA = 0x0L   // static List<Player> (ex: PlayerManager.s_allPlayerList)
    var LOCAL_PLAYER_RVA = 0x0L  // static Player do jogador local (opcional)
    var P_AVATAR        = 0x0L   // Player -> Avatar
    var P_DEAD          = 0x0L   // Player -> bool isDead
    var P_LOCAL         = 0x0L   // Player -> bool isLocal
    var P_TEAM          = 0x0L   // Player -> int team
    var P_NAME          = 0x0L   // Player -> System.String name
    var P_HP            = 0x0L   // Player -> float hp

    // --- avatar / transform ---------------------------------------------
    var A_TRANSFORM = 0x0L       // Avatar -> Transform
    var T_FATHER    = 0x58L      // Transform -> Transform pai   (Unity padrão)
    var T_POS       = 0x38L      // Transform -> Vector3 local   (Unity padrão)
    var T_ROT       = 0x28L      // Transform -> Quaternion local(Unity padrão)

    // --- camera ----------------------------------------------------------
    var CAMERA_RVA  = 0x0L       // static Camera (opcional)
    var C_TRANSFORM = 0x0L       // Camera -> Transform

    // --- ajustes ---------------------------------------------------------
    var FOV       = 60.0
    var MAX_DIST  = 200f
    var FRAME_MS  = 16L
    var CAM_H     = 1.55f        // usado só no modo câmera-fallback
    var CAM_BACK  = 2.2f
    var ALTURA    = 1.75f        // altura da hitbox em metros
}

/* ------------------------------- LOG ---------------------------------- */
object L {
    var bus: ((String) -> Unit)? = null
    fun i(s: String) { android.util.Log.i("FFESP", s); bus?.invoke(s) }
}

/* ------------------------------- ROOT --------------------------------- */
object Root {
    fun exec(cmd: String, timeoutMs: Long = 8000): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val t = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) { sb.append(line).append('\n'); line = r.readLine() }
                }
            } catch (_: Exception) {}
        }
        t.start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy()
        t.join(800)
        sb.toString()
    } catch (_: Exception) { "" }

    private var cached: Boolean? = null

    fun hasRoot(): Boolean {
        cached?.let { return it }
        val ok = exec("id").contains("uid=0")
        cached = ok
        return ok
    }

    fun findPid(pkgs: List<String>): Int? {
        for (pkg in pkgs) {
            val out = exec("pidof $pkg").trim()
            if (out.isNotEmpty()) out.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.let { return it }
        }
        for (line in exec("ps -A -o PID,NAME").lines()) {
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 2 && c[1] in pkgs) c[0].toIntOrNull()?.let { return it }
        }
        return null
    }

    fun isAlive(pid: Int) = exec("test -d /proc/$pid && echo y").contains("y")
}

/* ------------------------------- MAPS --------------------------------- */
object Maps {
    class Region(val start: Long, val end: Long, val perms: String, val offset: Long, val path: String)

    fun regions(pid: Int): List<Region> {
        val txt = Root.exec("cat /proc/$pid/maps")
        val out = ArrayList<Region>(512)
        for (line in txt.lineSequence()) {
            val p = line.split(' ')
            if (p.size < 6) continue
            val r = p[0].split('-')
            if (r.size != 2) continue
            val s = r[0].toLongOrNull(16) ?: continue
            val e = r[1].toLongOrNull(16) ?: continue
            out.add(Region(s, e, p[1], p[2].toLongOrNull(16) ?: 0L, p[5].trim()))
        }
        return out
    }

    fun base(pid: Int, module: String): Long =
        regions(pid).filter { it.offset == 0L && it.path.endsWith(module) }
            .minByOrNull { it.start }?.start ?: 0L
}

/* ---------------------------- MEM READER ------------------------------ */
interface MemReader : Closeable {
    fun read(addr: Long, size: Int): ByteArray
    fun readLong(a: Long): Long
    fun readInt(a: Long): Int
    fun readFloat(a: Long): Float
    fun readFloats(a: Long, n: Int): FloatArray
    fun alive(): Boolean
}

class DirectMemReader(pid: Int) : MemReader {
    private val raf = RandomAccessFile("/proc/$pid/mem", "r")

    override fun read(addr: Long, size: Int): ByteArray {
        val o = ByteArray(size)
        try { raf.seek(addr); raf.readFully(o) } catch (_: Exception) {}
        return o
    }
    override fun readLong(a: Long) = ByteBuffer.wrap(read(a, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong()
    override fun readInt(a: Long) = ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt()
    override fun readFloat(a: Long) = ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    override fun readFloats(a: Long, n: Int): FloatArray {
        val bb = ByteBuffer.wrap(read(a, n * 4)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.getFloat() }
    }
    override fun alive() = true
    override fun close() { try { raf.close() } catch (_: Exception) {} }
}

/** Lê através de um shell `su` persistente — funciona quando o acesso direto é bloqueado pelo SELinux. */
class ShellMemReader(private val pid: Int) : MemReader {
    private val proc = ProcessBuilder("su").start()
    private val inp: InputStream = proc.inputStream
    private val out: OutputStream = proc.outputStream
    private val buf = ByteArray(1 shl 17)
    private var len = 0
    private val sent = "@@E@@\n".toByteArray(Charsets.US_ASCII)

    init {
        Thread { try { proc.errorStream.readBytes() } catch (_: Exception) {} }
            .also { it.isDaemon = true; it.start() }
        cmd("printf '@@E@@\\n'")
        readUntil()
    }

    private fun cmd(s: String) { out.write((s + "\n").toByteArray()); out.flush() }

    private fun readUntil(): ByteArray {
        val acc = ByteArrayOutputStream()
        while (true) {
            var i = 0
            while (i + sent.size <= len) {
                var ok = true
                for (k in sent.indices) if (buf[i + k] != sent[k]) { ok = false; break }
                if (ok) {
                    acc.write(buf, 0, i)
                    val rest = len - (i + sent.size)
                    System.arraycopy(buf, i + sent.size, buf, 0, rest)
                    len = rest
                    return acc.toByteArray()
                }
                i++
            }
            if (len == buf.size) { acc.write(buf, 0, len); len = 0 }
            val n = inp.read(buf, len, buf.size - len)
            if (n <= 0) return acc.toByteArray()
            len += n
        }
    }

    override fun read(addr: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        cmd("dd if=/proc/$pid/mem bs=1 skip=$addr count=$size 2>/dev/null; printf '@@E@@\\n'")
        val raw = readUntil()
        if (raw.size == size) return raw
        val o = ByteArray(size)
        System.arraycopy(raw, 0, o, 0, minOf(raw.size, size))
        return o
    }
    override fun readLong(a: Long) = ByteBuffer.wrap(read(a, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong()
    override fun readInt(a: Long) = ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt()
    override fun readFloat(a: Long) = ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    override fun readFloats(a: Long, n: Int): FloatArray {
        val bb = ByteBuffer.wrap(read(a, n * 4)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.getFloat() }
    }
    override fun alive() = proc.isAlive
    override fun close() { try { proc.destroy() } catch (_: Exception) {} }
}

object Mem {
    fun open(pid: Int, base: Long): MemReader = try {
        val d = DirectMemReader(pid)
        val m = d.read(base, 4)
        if (m.size == 4 && m[0] == 0x7F.toByte() && m[1] == 'E'.code.toByte()) {
            L.i("mem: acesso direto OK (sem ptrace)")
            d
        } else { d.close(); L.i("mem: direto falhou -> shell root"); ShellMemReader(pid) }
    } catch (e: Exception) {
        L.i("mem: direto falhou (${e.message}) -> shell root")
        ShellMemReader(pid)
    }
}

/* ------------------------------ MATH ---------------------------------- */
object Q {
    fun mul(ax: Float, ay: Float, az: Float, aw: Float, bx: Float, by: Float, bz: Float, bw: Float) =
        floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )

    fun rot(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): FloatArray {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        )
    }
}

fun valid(p: Long) = p > 0x1000L && p < 0x800000000000L && (p and 7L) == 0L

/** Devolve [px,py,pz, qx,qy,qz,qw] em espaço de mundo, subindo a hierarquia de Transform. */
fun worldTransform(m: MemReader, t0: Long): FloatArray {
    val chain = ArrayList<Long>(8)
    var t = t0
    var g = 0
    while (t != 0L && g < 24) { chain.add(t); t = m.readLong(t + O.T_FATHER); g++ }
    var px = 0f; var py = 0f; var pz = 0f
    var qx = 0f; var qy = 0f; var qz = 0f; var qw = 1f
    for (i in chain.indices.reversed()) {
        val n = chain[i]
        val lp = m.readFloats(n + O.T_POS, 3)
        val lq = m.readFloats(n + O.T_ROT, 4)
        val r = Q.rot(qx, qy, qz, qw, lp[0], lp[1], lp[2])
        px += r[0]; py += r[1]; pz += r[2]
        val nq = Q.mul(qx, qy, qz, qw, lq[0], lq[1], lq[2], lq[3])
        qx = nq[0]; qy = nq[1]; qz = nq[2]; qw = nq[3]
    }
    return floatArrayOf(px, py, pz, qx, qy, qz, qw)
}

fun project(w: FloatArray, cp: FloatArray, cr: FloatArray, sw: Int, sh: Int): FloatArray? {
    val c = Q.rot(-cr[0], -cr[1], -cr[2], cr[3], w[0] - cp[0], w[1] - cp[1], w[2] - cp[2])
    if (c[2] <= 0.05f) return null
    val f = (sh / 2f) / tan(Math.toRadians(O.FOV / 2.0)).toFloat()
    return floatArrayOf(sw / 2f + c[0] / c[2] * f, sh / 2f - c[1] / c[2] * f, c[2])
}

/* ------------------------------ ENTITY -------------------------------- */
class Ent {
    var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
    var dist = 0f
    var hp = -1f
    var name = ""
    var color = Color.RED
    var local = false
}

/* ------------------------------ ENGINE -------------------------------- */
class Engine(private val size: () -> IntArray, private val sink: (List<Ent>) -> Unit) : Thread("ffesp") {
    @Volatile var running = true
    @Volatile var status = "iniciando"
    var fps = 0

    private var pid = 0
    private var mem: MemReader? = null
    private var base = 0L
    private var frames = 0
    private var lastFps = 0L

    override fun run() {
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            try { tick() } catch (e: Throwable) { status = "erro: ${e.message}" }
            val dt = SystemClock.elapsedRealtime() - t0
            frames++
            if (t0 - lastFps >= 1000) { fps = frames; frames = 0; lastFps = t0 }
            try { sleep((O.FRAME_MS - dt).coerceAtLeast(1)) } catch (_: Exception) {}
        }
        try { mem?.close() } catch (_: Exception) {}
    }

    private fun attach(): Boolean {
        try { mem?.close() } catch (_: Exception) {}
        mem = null; pid = 0; base = 0L
        val p = Root.findPid(O.PACKAGES) ?: run { status = "Free Fire nao esta aberto"; return false }
        Root.exec("chmod 666 /proc/$p/mem")
        val b = Maps.base(p, "libil2cpp.so")
        if (b == 0L) { status = "libil2cpp.so nao encontrado"; return false }
        mem = Mem.open(p, b)
        pid = p; base = b
        status = "conectado pid=$p base=0x${b.toString(16)}"
        L.i(status)
        return true
    }

    private fun readList(m: MemReader, list: Long): LongArray {
        val items = m.readLong(list + 0x10)
        val n = m.readInt(list + 0x18)
        if (!valid(items) || n <= 0) return LongArray(0)
        val c = n.coerceAtMost(128)
        return LongArray(c) { m.readLong(items + 0x20 + it * 8L) }
    }

    private fun readStr(m: MemReader, s: Long): String {
        if (!valid(s)) return ""
        val n = m.readInt(s + 0x10)
        if (n <= 0 || n > 48) return ""
        return String(m.read(s + 0x14, n * 2), Charsets.UTF_16LE).trim()
    }

    private fun tick() {
        if (mem == null || pid == 0 || base == 0L || !Root.isAlive(pid)) if (!attach()) return
        val m = mem ?: return
        if (O.PLAYER_LIST_RVA == 0L) { status = "defina PLAYER_LIST_RVA"; return }

        val listPtr = m.readLong(base + O.PLAYER_LIST_RVA)
        if (!valid(listPtr)) { status = "PLAYER_LIST_RVA invalido"; return }
        val players = readList(m, listPtr)
        if (players.isEmpty()) { status = "lista de players vazia"; return }

        var localPtr = 0L
        if (O.LOCAL_PLAYER_RVA != 0L) localPtr = m.readLong(base + O.LOCAL_PLAYER_RVA)

        var localT = 0L
        var localTeam = -1
        for (p in players) {
            if (!valid(p)) continue
            val isLocal = (O.P_LOCAL != 0L && m.readInt(p + O.P_LOCAL) != 0) || (localPtr != 0L && p == localPtr)
            if (!isLocal) continue
            if (O.P_AVATAR != 0L && O.A_TRANSFORM != 0L) {
                val av = m.readLong(p + O.P_AVATAR)
                if (valid(av)) localT = m.readLong(av + O.A_TRANSFORM)
            }
            if (O.P_TEAM != 0L) localTeam = m.readInt(p + O.P_TEAM)
            break
        }

        val camPos: FloatArray
        val camRot: FloatArray
        if (O.CAMERA_RVA != 0L && O.C_TRANSFORM != 0L) {
            val c = m.readLong(base + O.CAMERA_RVA)
            val ct = if (valid(c)) m.readLong(c + O.C_TRANSFORM) else 0L
            if (!valid(ct)) { status = "camera nao encontrada"; return }
            val w = worldTransform(m, ct)
            camPos = floatArrayOf(w[0], w[1], w[2]); camRot = floatArrayOf(w[3], w[4], w[5], w[6])
        } else if (valid(localT)) {
            val w = worldTransform(m, localT)
            camRot = floatArrayOf(w[3], w[4], w[5], w[6])
            val fw = Q.rot(w[3], w[4], w[5], w[6], 0f, 0f, 1f)
            camPos = floatArrayOf(w[0] - fw[0] * O.CAM_BACK, w[1] + O.CAM_H, w[2] - fw[2] * O.CAM_BACK)
        } else { status = "sem camera / sem local player"; return }

        val sz = size(); val sw = sz[0]; val sh = sz[1]
        if (sw <= 0 || sh <= 0) return

        val out = ArrayList<Ent>(16)
        for (p in players) {
            if (!valid(p)) continue
            if (O.P_DEAD != 0L && m.readInt(p + O.P_DEAD) != 0) continue
            if (O.P_AVATAR == 0L || O.A_TRANSFORM == 0L) continue
            val av = m.readLong(p + O.P_AVATAR)
            if (!valid(av)) continue
            val tr = m.readLong(av + O.A_TRANSFORM)
            if (!valid(tr)) continue
            val w = worldTransform(m, tr)
            if (abs(w[0]) > 1e6f || w[1].isNaN()) continue

            val pf = project(floatArrayOf(w[0], w[1], w[2]), camPos, camRot, sw, sh) ?: continue
            val ph = project(floatArrayOf(w[0], w[1] + O.ALTURA, w[2]), camPos, camRot, sw, sh) ?: continue
            if (pf[2] > O.MAX_DIST) continue

            val e = Ent()
            val h = abs(pf[1] - ph[1])
            val bw = (h * 0.45f).coerceAtLeast(6f)
            e.x1 = ph[0] - bw / 2f; e.x2 = ph[0] + bw / 2f
            e.y1 = ph[1]; e.y2 = pf[1]
            e.dist = pf[2]
            if (O.P_HP != 0L) e.hp = m.readFloat(p + O.P_HP)
            if (O.P_NAME != 0L) e.name = readStr(m, m.readLong(p + O.P_NAME))
            var team = -1
            if (O.P_TEAM != 0L) team = m.readInt(p + O.P_TEAM)
            e.local = (O.P_LOCAL != 0L && m.readInt(p + O.P_LOCAL) != 0) || (localPtr != 0L && p == localPtr)
            e.color = when {
                e.local -> Color.YELLOW
                localTeam >= 0 && team == localTeam -> Color.CYAN
                else -> Color.RED
            }
            out.add(e)
        }
        out.sortByDescending { it.dist }
        sink(out)
        status = "players=${players.size} alvos=${out.size}"
    }
}

/* ------------------------------- VIEW --------------------------------- */
class EspView(c: Context) : View(c) {
    var ents: List<Ent> = emptyList()
    var on = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        if (!on) return
        for (e in ents) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = e.color
            canvas.drawRect(e.x1, e.y1, e.x2, e.y2, p)
            canvas.drawLine(width / 2f, height.toFloat(), (e.x1 + e.x2) / 2f, e.y2, p)

            if (e.hp >= 0f) {
                val f = (e.hp / 200f).coerceIn(0f, 1f)
                p.style = Paint.Style.FILL; p.color = Color.GREEN
                val bx = e.x1 - 12f
                canvas.drawRect(bx, e.y1, bx + 8f, e.y1 + (e.y2 - e.y1) * f, p)
                p.color = Color.DKGRAY
                canvas.drawRect(bx, e.y1 + (e.y2 - e.y1) * f, bx + 8f, e.y2, p)
            }

            p.style = Paint.Style.FILL; p.color = e.color
            p.textSize = 26f
            val label = "${e.dist.toInt()}m" + if (e.name.isNotEmpty()) "  ${e.name}" else ""
            canvas.drawText(label, e.x1, e.y1 - 8f, p)
        }
    }
}

/* ------------------------------ SERVICE ------------------------------- */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var esp: EspView? = null
    private var engine: Engine? = null
    private var toggle: TextView? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val v = EspView(this)
        esp = v
        wm.addView(v, params(true))

        val t = TextView(this)
        toggle = t
        t.text = "ESP ON"
        t.setTextColor(Color.WHITE)
        t.setBackgroundColor(0xAA000000.toInt())
        t.setPadding(28, 18, 28, 18)
        t.textSize = 15f
        val tp = params(false)
        tp.gravity = Gravity.TOP or Gravity.START
        tp.x = 30; tp.y = 260
        wm.addView(t, tp)

        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var moved = false
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; dx = tp.x.toFloat(); dy = tp.y.toFloat(); moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    tp.x = (dx + e.rawX - sx).toInt(); tp.y = (dy + e.rawY - sy).toInt()
                    if (abs(e.rawX - sx) > 14 || abs(e.rawY - sy) > 14) moved = true
                    wm.updateViewLayout(t, tp); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.on = !v.on; v.invalidate(); t.text = if (v.on) "ESP ON" else "ESP OFF" }
                    true
                }
                else -> false
            }
        }

        engine = Engine({ intArrayOf(v.width, v.height) }) { list ->
            v.post { v.ents = list; v.invalidate() }
        }.also { it.start() }
    }

    private fun params(full: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (full) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        val lp = WindowManager.LayoutParams(size, size, type, flags, PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun startFg() {
        val ch = "ffesp"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "ESP", NotificationManager.IMPORTANCE_LOW))
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else Notification.Builder(this)
        startForeground(1, b.setContentTitle("FF ESP")
            .setContentText("ESP ativo").setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build())
    }

    override fun onDestroy() {
        engine?.running = false
        try { esp?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { toggle?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}

/* ------------------------------ ACTIVITY ------------------------------ */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        ll.addView(TextView(this).apply { text = "FF ESP — Venice"; textSize = 22f })
        statusView = TextView(this).apply { textSize = 14f; setPadding(0, 20, 0, 20) }
        ll.addView(statusView)

        ll.addView(btn("1) Permissao de sobreposicao") { askOverlay() })
        ll.addView(btn("2) INICIAR ESP") { startEsp() })
        ll.addView(btn("3) PARAR ESP") { stopService(Intent(this, OverlayService::class.java)) })
        ll.addView(btn("Abrir Free Fire") { openFF() })
        ll.addView(btn("Diagnostico") { diag() })

        logView = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(0, 24, 0, 24)
        }
        ll.addView(logView)

        setContentView(ScrollView(this).apply { addView(ll) })
        L.bus = { line -> runOnUiThread { logView.append("$line\n") } }
    }

    override fun onResume() {
        super.onResume()
        statusView.text = "root: " + if (Root.hasRoot()) "OK" else "NAO ENCONTRADO\n" +
                "sobreposicao: " + if (Settings.canDrawOverlays(this)) "concedida" else "pendente\n" +
                "Free Fire rodando: " + if (Root.findPid(O.PACKAGES) != null) "sim" else "nao"
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

    private fun askOverlay() = startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
    )

    private fun openFF() {
        for (p in O.PACKAGES) packageManager.getLaunchIntentForPackage(p)?.let { startActivity(it); return }
        logView.append("Free Fire nao instalado\n")
    }

    private fun startEsp() {
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        logView.append("servico iniciado\n")
    }

    private fun diag() = Thread {
        val log = { s: String -> runOnUiThread { logView.append("$s\n") } }
        log("root = ${Root.hasRoot()}")
        val pid = Root.findPid(O.PACKAGES)
        log("pid = $pid")
        if (pid == null) { log("abra o Free Fire primeiro"); return@Thread }
        Root.exec("chmod 666 /proc/$pid/mem")
        val b = Maps.base(pid, "libil2cpp.so")
        log("libil2cpp base = 0x${b.toString(16)}")
        val m = Mem.open(pid, b)
        log("leitura = ${m.javaClass.simpleName}")
        val magic = m.read(b, 4).joinToString(" ") { "%02x".format(it) }
        log("elf magic = $magic  (esperado 7f 45 4c 46)")
        if (O.PLAYER_LIST_RVA != 0L) {
            val lp = m.readLong(b + O.PLAYER_LIST_RVA)
            log("playerList ptr = 0x${lp.toString(16)}")
            if (valid(lp)) log("playerList size = ${m.readInt(lp + 0x18)}")
        } else log("PLAYER_LIST_RVA ainda nao preenchido")
        m.close()
    }.start()
}
