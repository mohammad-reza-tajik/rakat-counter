package com.rakat.counter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {

    private val prayers = listOf("صبح" to 2, "ظهر" to 4, "عصر" to 4, "مغرب" to 3, "عشاء" to 4)
    private val ord = listOf("اول", "دوم", "سوم", "چهارم")

    private var prayer = 3
    // 0 = تکبیر | هر بار نزدیک شدن به سنسور = یک سجده (هر رکعت ۲ سجده)
    private var step = 0

    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var isNear = false
    private var tick: AudioTrack? = null
    private var lastCount = 0L

    private lateinit var counter: CounterView
    private val buttons = mutableListOf<TextView>()
    private var dp = 1f
    private var ready = false
    private var tf: Typeface = Typeface.DEFAULT

    private fun total() = 2 * prayers[prayer].second

    /** وضعیت نمایش: تعداد فلش‌های بالا = شماره رکعت، تعداد فلش‌های پایین = شماره سجده */
    class Scene(
        val takbir: Boolean,
        val up: Int,
        val down: Int,
        val rak: String,
        val saj: String,
        val done: Boolean
    )

    private fun scene(): Scene {
        if (step == 0) return Scene(true, 0, 0, "", "", false)
        val k = step - 1
        return Scene(
            false, k / 2 + 1, k % 2 + 1,
            "رکعت " + ord[k / 2],
            "سجده " + if (k % 2 == 0) "اول" else "دوم",
            step >= total()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        tf = try {
            Typeface.createFromAsset(assets, "fonts/primary.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }

        tick = buildTick()

        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        prayer = prefs.getInt("prayer", 3).coerceIn(0, prayers.size - 1)
        step = prefs.getInt("step", 0).coerceIn(0, total())

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#1565C0")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFBDBDBD.toInt())
        }
        counter = CounterView(this)
        root.addView(counter, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        prayers.forEachIndexed { i, p ->
            val t = TextView(this).apply {
                text = p.first
                typeface = tf
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { prayer = i; step = 0; refresh() }
            }
            buttons.add(t)
            val lp = LinearLayout.LayoutParams(0, (56 * dp).toInt(), 1f)
            lp.setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            row.addView(t, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        counter.setOnLongClickListener { step = 0; refresh(); true }

        setContentView(root)
        refresh()
    }

    /** صدای تیک با اسیلاتور (موج سینوسی کوتاه با افت سریع) ساخته می‌شود، بدون فایل صوتی */
    private fun buildTick(): AudioTrack? = try {
        val sr = 44100
        val n = sr * 60 / 1000
        val pcm = ShortArray(n) { i ->
            val time = i.toDouble() / sr
            val env = exp(-time / 0.008) * minOf(1.0, i / 40.0)
            (sin(2 * PI * 2000 * time) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC
        )
        track.write(pcm, 0, pcm.size)
        track
    } catch (e: Exception) {
        null
    }

    private fun playTick() {
        try {
            tick?.let { it.stop(); it.reloadStaticData(); it.play() }
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        isNear = false
        proximity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        tick?.release()
        tick = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val near = e.values[0] < (proximity?.maximumRange ?: 5f).coerceAtMost(5f)
        val now = System.currentTimeMillis()
        if (near && !isNear && now - lastCount > 700) {
            lastCount = now
            step = if (step >= total()) 0 else step + 1
            refresh()
            playTick()
        }
        isNear = near
    }

    private fun refresh() {
        buttons.forEachIndexed { i, t ->
            val sel = i == prayer
            t.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setStroke((3 * dp).toInt(), 0xFF333333.toInt())
                setColor(if (sel) 0xFF333333.toInt() else Color.TRANSPARENT)
            }
            t.setTextColor(if (sel) Color.WHITE else 0xFF333333.toInt())
        }
        counter.show(scene(), ready)
        ready = true
        getSharedPreferences("state", MODE_PRIVATE).edit()
            .putInt("prayer", prayer).putInt("step", step).apply()
    }

    inner class CounterView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = tf
        }

        private var from = scene()
        private var to = from
        private var t = 1f

        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        fun show(s: Scene, animate: Boolean) {
            anim.cancel()
            if (!animate) {
                from = s; to = s; t = 1f
            } else {
                from = to; to = s; t = 0f
                anim.start()
            }
            invalidate()
        }

        private fun wt(a: Boolean, b: Boolean) = (if (a) 1f - t else 0f) + (if (b) t else 0f)

        private fun drawT(c: Canvas, s: String, x: Float, y: Float, alpha: Float) {
            val a = (255 * alpha.coerceIn(0f, 1f)).toInt()
            if (a == 0) return
            text.color = Color.argb(a, 0, 0, 0)
            c.drawText(s, x, y, text)
        }

        private fun drawSwap(c: Canvas, x: Float, y: Float, a: String, b: String, aOn: Boolean, bOn: Boolean) {
            if (aOn && bOn && a == b) { drawT(c, a, x, y, 1f); return }
            val dy = 26 * dp
            if (aOn) drawT(c, a, x, y - dy * t, 1f - t)
            if (bOn) drawT(c, b, x, y + dy * (1f - t), t)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2

            // پس‌زمینه با هاله‌ی سفید
            c.drawColor(0xFFBDBDBD.toInt())
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, h * 0.3f, w * 0.9f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null

            // محراب
            val l = w * 0.08f
            val r = w - l
            val top = h * 0.1f
            val sy = h * 0.31f
            val s = w * 0.04f
            val arch = Path().apply {
                moveTo(l, h)
                lineTo(l, sy)
                lineTo(l + s, sy)
                cubicTo(l + s, sy - h * 0.14f, cx - w * 0.28f, top + h * 0.07f, cx, top)
                cubicTo(cx + w * 0.28f, top + h * 0.07f, r - s, sy - h * 0.14f, r - s, sy)
                lineTo(r, sy)
                lineTo(r, h)
            }
            p.color = 0xFFBEBEBE.toInt()
            c.drawPath(arch, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * dp
            p.color = 0xFFA67C2E.toInt()
            c.drawPath(arch, p)
            p.style = Paint.Style.FILL

            // عنوان
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 22 * dp
            drawT(c, "رکعت شمار", w - 24 * dp, 38 * dp, 0.85f)
            text.textAlign = Paint.Align.CENTER

            // تکبیر
            val wTakbir = wt(from.takbir, to.takbir)
            if (wTakbir > 0f) {
                text.textSize = w * 0.15f
                drawT(c, "اللّٰه أکبر", cx, h * 0.55f, wTakbir)
            }

            // برچسب رکعت و سجده (جابه‌جایی نرم)
            text.textSize = w * 0.075f
            drawSwap(c, cx, h * 0.30f, from.rak, to.rak, !from.takbir, !to.takbir)
            drawSwap(c, cx, h * 0.77f, from.saj, to.saj, !from.takbir, !to.takbir)

            // فلش‌ها: تعداد فلش بالا = رکعت ، تعداد فلش پایین = سجده
            val up = from.up + (to.up - from.up) * t
            val down = from.down + (to.down - from.down) * t
            drawArrows(c, cx, h * 0.47f, up, true, h * 0.17f)
            drawArrows(c, cx, h * 0.64f, down, false, h * 0.065f)

            // پایان نماز
            val wDone = wt(from.done, to.done)
            if (wDone > 0f) {
                text.textSize = w * 0.055f
                drawT(c, "نماز تمام شد", cx, h * 0.88f, wDone)
            }
        }

        private fun drawArrows(c: Canvas, cx: Float, cy: Float, count: Float, up: Boolean, size: Float) {
            val spacing = if (up) size * 0.5f else size * 1.6f
            val eff = max(count, 1f)
            for (i in 0 until ceil(count).toInt()) {
                val k = (count - i).coerceIn(0f, 1f)
                val x = cx + (i - (eff - 1f) / 2f) * spacing
                p.color = Color.argb((255 * k).toInt(), 0, 0, 0)
                c.save()
                c.translate(x, cy)
                c.scale(k, k)
                if (up) arrowUp(c, size) else arrowDown(c, size)
                c.restore()
            }
        }

        private fun arrowUp(c: Canvas, len: Float) {
            val tip = -len / 2
            val hb = tip + len * 0.28f
            val hw = len * 0.17f
            val sw = len * 0.05f
            val path = Path().apply {
                moveTo(0f, tip)
                lineTo(hw, hb); lineTo(sw, hb); lineTo(sw, len / 2)
                lineTo(-sw, len / 2); lineTo(-sw, hb); lineTo(-hw, hb)
                close()
            }
            c.drawPath(path, p)
        }

        private fun arrowDown(c: Canvas, size: Float) {
            val path = Path().apply {
                moveTo(0f, size / 2)
                lineTo(size * 0.55f, -size / 2)
                lineTo(0f, -size * 0.15f)
                lineTo(-size * 0.55f, -size / 2)
                close()
            }
            c.drawPath(path, p)
        }
    }
}
