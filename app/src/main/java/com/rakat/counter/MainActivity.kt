package com.rakat.counter

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), SensorEventListener {

    private val prayers = listOf("صبح" to 2, "ظهر" to 4, "عصر" to 4, "مغرب" to 3, "عشاء" to 4)
    private val ord = listOf("اول", "دوم", "سوم", "چهارم")

    private var prayer = 3
    // 0 = تکبیر | هر بار نزدیک شدن به سنسور = یک سجده (هر رکعت ۲ سجده)
    private var step = 0
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var isNear = false
    private var lastCount = 0L

    private lateinit var counter: CounterView
    private val buttons = mutableListOf<TextView>()
    private var dp = 1f

    private fun total() = 2 * prayers[prayer].second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        prayer = prefs.getInt("prayer", 3).coerceIn(0, prayers.size - 1)
        step = prefs.getInt("step", 0).coerceIn(0, total())

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        window.statusBarColor = Color.parseColor("#1565C0")

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

    override fun onResume() {
        super.onResume()
        isNear = false
        proximity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val near = e.values[0] < (proximity?.maximumRange ?: 5f).coerceAtMost(5f)
        val now = System.currentTimeMillis()
        if (near && !isNear && now - lastCount > 600) {
            lastCount = now
            step = if (step >= total()) 0 else step + 1
            refresh()
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
        counter.invalidate()
        getSharedPreferences("state", MODE_PRIVATE).edit()
            .putInt("prayer", prayer).putInt("step", step).apply()
    }

    inner class CounterView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

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
            text.textSize = 20 * dp
            text.color = 0xFF333333.toInt()
            text.typeface = Typeface.DEFAULT_BOLD
            c.drawText("رکعت شمار", w - 24 * dp, 36 * dp, text)
            text.textAlign = Paint.Align.CENTER

            val n = prayers[prayer].second
            when {
                step == 0 -> {
                    text.color = Color.BLACK
                    text.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    text.textSize = w * 0.17f
                    c.drawText("اللّٰه أکبر", cx, h * 0.55f, text)
                }
                step >= 2 * n -> {
                    text.color = Color.BLACK
                    text.typeface = Typeface.DEFAULT_BOLD
                    text.textSize = w * 0.09f
                    c.drawText("نماز تمام شد", cx, h * 0.55f, text)
                }
                else -> {
                    val k = step - 1
                    val rak = "رکعت " + ord[k / 2]
                    val saj = "سجده " + if (k % 2 == 0) "اول" else "دوم"
                    text.typeface = Typeface.DEFAULT
                    text.textSize = w * 0.075f
                    text.color = Color.BLACK
                    c.drawText(rak, cx, h * 0.37f, text)
                    arrowUp(c, cx, h * 0.47f, h * 0.17f, Color.BLACK)
                    arrowDown(c, cx, h * 0.64f, h * 0.065f, Color.BLACK)
                    c.drawText(saj, cx, h * 0.77f, text)
                }
            }
        }

        private fun arrowUp(c: Canvas, cx: Float, cy: Float, len: Float, color: Int) {
            val tip = cy - len / 2
            val hb = tip + len * 0.28f
            val hw = len * 0.17f
            val sw = len * 0.05f
            val path = Path().apply {
                moveTo(cx, tip)
                lineTo(cx + hw, hb); lineTo(cx + sw, hb); lineTo(cx + sw, cy + len / 2)
                lineTo(cx - sw, cy + len / 2); lineTo(cx - sw, hb); lineTo(cx - hw, hb)
                close()
            }
            p.color = color
            c.drawPath(path, p)
        }

        private fun arrowDown(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
            val path = Path().apply {
                moveTo(cx, cy + size / 2)
                lineTo(cx + size * 0.55f, cy - size / 2)
                lineTo(cx, cy - size * 0.15f)
                lineTo(cx - size * 0.55f, cy - size / 2)
                close()
            }
            p.color = color
            c.drawPath(path, p)
        }
    }
}
