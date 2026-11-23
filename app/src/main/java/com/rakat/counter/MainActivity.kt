package com.rakat.counter

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {

    private val prayers = listOf("صبح" to 2, "ظهر" to 4, "عصر" to 4, "مغرب" to 3, "عشاء" to 4)
    private val ord = listOf("اول", "دوم", "سوم", "چهارم")

    private var prayer = 3
    // 0 = تکبیر | هر شمارش = یک سجده (هر رکعت ۲ سجده)
    private var step = 0
    private var dark = true
    private var touchMode = false

    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var isNear = false
    private var lastCount = 0L
    private var tick: AudioTrack? = null

    private lateinit var counter: CounterView
    private val buttons = mutableListOf<TextView>()
    private var dp = 1f
    private var ready = false
    private var tf: Typeface = Typeface.DEFAULT
    private var tfB: Typeface = Typeface.DEFAULT_BOLD

    private fun total() = 2 * prayers[prayer].second
    private fun d(v: Int) = (v * dp).toInt()

    // رنگ‌های تم
    private val bgColor: Int get() = if (dark) 0xFF000000.toInt() else 0xFFBDBDBD.toInt()
    private val barFg: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
    private val ink: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

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
        tfB = Typeface.create(tf, Typeface.BOLD)

        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        prayer = prefs.getInt("prayer", 3).coerceIn(0, prayers.size - 1)
        step = prefs.getInt("step", 0).coerceIn(0, total())
        dark = prefs.getBoolean("dark", true)
        touchMode = prefs.getBoolean("touch", false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        tick = buildTick()
        buildUi()
    }

    // ---------------------------------------------------------------
    // رابط
    // ---------------------------------------------------------------
    private fun iconBtn(res: Int, color: Int, onClick: (View) -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(color)
        v.setPadding(d(12), d(12), d(12), d(12))
        v.setOnClickListener { onClick(it) }
        return v
    }

    private fun buildUi() {
        buttons.clear()
        ready = false
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(bgColor)

        // نوار بالا: بازگشت، عنوان، راهنما، منو
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(d(6), d(8), d(6), d(2))
        val title = TextView(this)
        title.text = "رکعت شمار"
        title.textSize = 19f
        title.setTextColor(barFg)
        title.typeface = tfB
        val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tlp.setMargins(0, 0, d(14), 0)
        bar.addView(title, tlp)
        bar.addView(iconBtn(R.drawable.ic_more, barFg) { showMenu(it) }, LinearLayout.LayoutParams(d(48), d(48)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        counter = CounterView(this)
        root.addView(counter, LinearLayout.LayoutParams(-1, 0, 1f))
        counter.setOnClickListener { if (touchMode) advance() }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL
        row.setPadding(d(12), d(8), d(12), d(12))
        prayers.forEachIndexed { i, p ->
            val t = TextView(this)
            t.text = p.first
            t.typeface = tf
            t.textSize = 20f
            t.gravity = Gravity.CENTER
            t.setOnClickListener { prayer = i; step = 0; refresh() }
            buttons.add(t)
            val lp = LinearLayout.LayoutParams(0, d(56), 1f)
            lp.setMargins(d(4), 0, d(4), 0)
            row.addView(t, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        applyBars()
        refresh()
        ready = true
    }

    @Suppress("DEPRECATION")
    private fun applyBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        var f = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= 23) {
            f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= 26) {
            f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
    }

    private fun refresh() {
        buttons.forEachIndexed { i, t ->
            val sel = i == prayer
            val g = GradientDrawable()
            g.cornerRadius = 8 * dp
            if (sel) {
                g.setColor(0xFF333333.toInt())
            } else {
                g.setColor(Color.TRANSPARENT)
                g.setStroke((3 * dp).toInt(), if (dark) 0xFF8E8E8E.toInt() else 0xFF333333.toInt())
            }
            t.background = g
            t.setTextColor(if (sel) Color.WHITE else if (dark) 0xFFD0D0D0.toInt() else 0xFF333333.toInt())
        }
        counter.show(scene(), ready)
        getSharedPreferences("state", MODE_PRIVATE).edit()
            .putInt("prayer", prayer).putInt("step", step).apply()
    }

    private fun advance() {
        step = if (step >= total()) 0 else step + 1
        refresh()
        playTick()
    }

    // ---------------------------------------------------------------
    // منو، راهنما، تنظیمات
    // ---------------------------------------------------------------
    private fun showMenu(anchor: View) {
        val bg = if (dark) 0xFF3A3A3A.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val shape = GradientDrawable()
        shape.setColor(bg)
        shape.cornerRadius = 10 * dp
        card.background = shape
        card.elevation = 12 * dp
        val popup = PopupWindow(card, d(190), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 12 * dp

        fun item(icon: Int, label: String, onClick: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(d(18), d(15), d(18), d(15))
            val iv = ImageView(this)
            iv.setImageResource(icon)
            iv.setColorFilter(fg)
            r.addView(iv, LinearLayout.LayoutParams(d(22), d(22)))
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 15.5f
            tv.setTextColor(fg)
            tv.typeface = tf
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(0, 0, d(12), 0)
            r.addView(tv, lp)
            r.setOnClickListener { popup.dismiss(); onClick() }
            card.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        item(R.drawable.ic_reset, "بازنشانی") { step = 0; refresh() }
        item(if (dark) R.drawable.ic_sun else R.drawable.ic_moon, if (dark) "روز" else "شب") { toggleTheme() }
        item(if (touchMode) R.drawable.ic_sensor else R.drawable.ic_touch, if (touchMode) "سنسور" else "لمسی") { toggleMode() }
        popup.showAsDropDown(anchor, 0, 0, Gravity.LEFT)
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("dark", dark).apply()
        buildUi()
    }

    private fun toggleMode() {
        touchMode = !touchMode
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("touch", touchMode).apply()
        if (touchMode) {
            sensorManager?.unregisterListener(this)
            Toast.makeText(this, "حالت لمسی: برای شمارش، صفحه را لمس کنید", Toast.LENGTH_SHORT).show()
        } else {
            isNear = false
            proximity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            Toast.makeText(this, "حالت سنسور: با نزدیک کردن پیشانی به سنسور می‌شمارد", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------
    // سنسور و صدا
    // ---------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        isNear = false
        if (!touchMode) proximity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
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
        if (touchMode) return
        val near = e.values[0] < (proximity?.maximumRange ?: 5f).coerceAtMost(5f)
        val now = System.currentTimeMillis()
        if (near && !isNear && now - lastCount > 700) {
            lastCount = now
            advance()
        }
        isNear = near
    }

    /** صدای تیک با اسیلاتور (موج سینوسی کوتاه با افت سریع) */
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

    // ---------------------------------------------------------------
    // صفحه‌ی شمارش
    // ---------------------------------------------------------------
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
            text.color = (ink and 0x00FFFFFF) or (a shl 24)
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

            // پس‌زمینه با هاله
            c.drawColor(bgColor)
            p.style = Paint.Style.FILL
            val glow = if (dark) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()
            p.shader = RadialGradient(cx, h * 0.3f, w * 0.9f, glow, bgColor and 0x00FFFFFF, Shader.TileMode.CLAMP)
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
            p.color = if (dark) 0xFF000000.toInt() else 0xFFBEBEBE.toInt()
            c.drawPath(arch, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * dp
            p.color = 0xFFA67C2E.toInt()
            c.drawPath(arch, p)
            p.style = Paint.Style.FILL

            // تکبیر
            val wTakbir = wt(from.takbir, to.takbir)
            if (wTakbir > 0f) {
                text.typeface = tfB
                text.textSize = w * 0.15f
                drawT(c, "اللّٰه أکبر", cx, h * 0.55f, wTakbir)
            }

            // برچسب‌ها
            text.typeface = tf
            text.textSize = w * 0.075f
            drawSwap(c, cx, h * 0.29f, from.rak, to.rak, !from.takbir, !to.takbir)
            drawSwap(c, cx, h * 0.81f, from.saj, to.saj, !from.takbir, !to.takbir)

            // فلش‌ها: تعداد فلش بالا = رکعت ، تعداد فلش پایین = سجده
            val up = from.up + (to.up - from.up) * t
            val down = from.down + (to.down - from.down) * t
            drawArrows(c, cx, h * 0.47f, up, true, h * 0.17f)
            drawArrows(c, cx, h * 0.64f, down, false, h * 0.065f)

            // پایان نماز
            val wDone = wt(from.done, to.done)
            if (wDone > 0f) {
                text.textSize = w * 0.055f
                drawT(c, "نماز تمام شد", cx, h * 0.91f, wDone)
            }
        }

        private fun drawArrows(c: Canvas, cx: Float, cy: Float, count: Float, up: Boolean, size: Float) {
            val spacing = if (up) size * 0.5f else size * 1.6f
            val eff = max(count, 1f)
            for (i in 0 until ceil(count).toInt()) {
                val k = (count - i).coerceIn(0f, 1f)
                val off = i - (eff - 1f) / 2f
                val x = cx + off * spacing
                p.color = (ink and 0x00FFFFFF) or ((255 * k).toInt() shl 24)
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
