package app.viday

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View

class Pal(val dark: Boolean) {
    val bg = if (dark) 0xFF0B1220.toInt() else 0xFFF3F6FA.toInt()
    val card = if (dark) 0xFF141E33.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFE8EEF8.toInt() else 0xFF0F172A.toInt()
    val sub = if (dark) 0xFF9AA8BF.toInt() else 0xFF5B6B80.toInt()
    val line = if (dark) 0xFF24324D.toInt() else 0xFFE3E9F0.toInt()
    val accent = if (dark) 0xFF3DB2FF.toInt() else 0xFF0A84E6.toInt()
    val tint = if (dark) 0xFF1B2B47.toInt() else 0xFFE6F2FD.toInt()
    val good = if (dark) 0xFF2FD6A0.toInt() else 0xFF0E8F63.toInt()
    val warn = if (dark) 0xFFFFB84D.toInt() else 0xFFA86400.toInt()
    val bad = if (dark) 0xFFFF6B6B.toInt() else 0xFFD02F2F.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun Context.round(color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dpf(radiusDp.toFloat())
    return g
}

fun Context.rippled(color: Int, radiusDp: Int, rippleColor: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), round(color, radiusDp), round(0xFFFFFFFF.toInt(), radiusDp))

/** Simple line icons drawn with Canvas (no image assets needed). */
class IconView(c: Context, private val kind: Int, var tint: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat()
        val u = s / 24f
        p.color = tint
        p.strokeWidth = 2f * u
        cv.save()
        cv.translate((width - s) / 2f, (height - s) / 2f)
        when (kind) {
            0 -> { // home
                val path = Path()
                path.moveTo(4 * u, 11 * u); path.lineTo(12 * u, 4 * u); path.lineTo(20 * u, 11 * u)
                path.lineTo(20 * u, 20 * u); path.lineTo(4 * u, 20 * u); path.close()
                cv.drawPath(path, p)
            }
            1 -> { // apps
                for (i in 0..1) for (j in 0..1) {
                    val l = (4 + i * 9) * u
                    val t = (4 + j * 9) * u
                    cv.drawRoundRect(RectF(l, t, l + 7 * u, t + 7 * u), 1.5f * u, 1.5f * u, p)
                }
            }
            2 -> { // chart
                cv.drawLine(6 * u, 20 * u, 6 * u, 13 * u, p)
                cv.drawLine(12 * u, 20 * u, 12 * u, 5 * u, p)
                cv.drawLine(18 * u, 20 * u, 18 * u, 9 * u, p)
            }
            3 -> { // history
                cv.drawCircle(12 * u, 12 * u, 8 * u, p)
                val path = Path()
                path.moveTo(12 * u, 7 * u); path.lineTo(12 * u, 12 * u); path.lineTo(15.5f * u, 14 * u)
                cv.drawPath(path, p)
            }
            4 -> { // settings gear
                cv.drawCircle(12 * u, 12 * u, 3.2f * u, p)
                cv.drawCircle(12 * u, 12 * u, 7f * u, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cs = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    cv.drawLine((12 + 7 * cs) * u, (12 + 7 * sn) * u, (12 + 9.5f * cs) * u, (12 + 9.5f * sn) * u, p)
                }
            }
            5 -> { // back
                val path = Path()
                path.moveTo(15 * u, 5 * u); path.lineTo(8 * u, 12 * u); path.lineTo(15 * u, 19 * u)
                cv.drawPath(path, p)
            }
            8 -> { // close
                cv.drawLine(6 * u, 6 * u, 18 * u, 18 * u, p)
                cv.drawLine(18 * u, 6 * u, 6 * u, 18 * u, p)
            }
            9 -> { // code brackets
                val a = Path()
                a.moveTo(9 * u, 7 * u); a.lineTo(4 * u, 12 * u); a.lineTo(9 * u, 17 * u)
                cv.drawPath(a, p)
                val b = Path()
                b.moveTo(15 * u, 7 * u); b.lineTo(20 * u, 12 * u); b.lineTo(15 * u, 17 * u)
                cv.drawPath(b, p)
                cv.drawLine(13.5f * u, 5 * u, 10.5f * u, 19 * u, p)
            }
            6 -> { // chevron down
                val path = Path()
                path.moveTo(6 * u, 9 * u); path.lineTo(12 * u, 15 * u); path.lineTo(18 * u, 9 * u)
                cv.drawPath(path, p)
            }
            else -> { // chevron up
                val path = Path()
                path.moveTo(6 * u, 15 * u); path.lineTo(12 * u, 9 * u); path.lineTo(18 * u, 15 * u)
                cv.drawPath(path, p)
            }
        }
        cv.restore()
    }
}

class RingView(c: Context) : View(c) {
    var frac = 0f
        set(v) { field = v; invalidate() }
    var ringColor = 0
    var trackColor = 0
    var t1 = ""
    var t2 = ""
    var c1 = 0
    var c2 = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var anim: ValueAnimator? = null

    fun animateTo(target: Float) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(frac, target)
        a.duration = 600
        a.addUpdateListener { frac = it.animatedValue as Float }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val sw = w * 0.075f
        p.strokeWidth = sw
        val r = RectF(sw / 2, sw / 2, w - sw / 2, w - sw / 2)
        p.color = trackColor
        cv.drawArc(r, 0f, 360f, false, p)
        p.color = ringColor
        val sweep = 360f * minOf(frac, 1f)
        if (sweep > 0f) cv.drawArc(r, -90f, sweep, false, p)
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.color = c1
        tp.textSize = w * 0.2f
        cv.drawText(t1, w / 2, w / 2 + tp.textSize * 0.2f, tp)
        tp.typeface = Typeface.DEFAULT
        tp.color = c2
        tp.textSize = w * 0.075f
        cv.drawText(t2, w / 2, w / 2 + w * 0.2f, tp)
    }
}

class BarView(c: Context, private val pal: Pal) : View(c) {
    var vals: List<Double> = emptyList()
    var labels: List<String> = emptyList()
    var limit = 0.0
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(cv: Canvas) {
        val n = vals.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = context.dpf(20f)
        val top = context.dpf(8f)
        val chartH = h - labelH - top
        var mx = maxOf(vals.maxOrNull() ?: 0.0, limit)
        if (mx <= 0.0) mx = 1.0
        mx *= 1.1
        val slot = w / n
        val bw = slot * 0.62f
        tp.textSize = context.spf(10f)
        tp.color = pal.sub
        val step = if (n <= 8) 1 else if (n <= 16) 2 else 5
        for (i in 0 until n) {
            val v = vals[i]
            val bh = (v / mx * chartH).toFloat()
            val l = i * slot + (slot - bw) / 2
            bp.color = if (limit > 0 && v > limit) pal.bad else pal.accent
            val rect = RectF(l, top + chartH - maxOf(bh, 2f), l + bw, top + chartH)
            cv.drawRoundRect(rect, context.dpf(3f), context.dpf(3f), bp)
            if (i % step == 0 && i < labels.size) cv.drawText(labels[i], i * slot + slot / 2, h - context.dpf(5f), tp)
        }
        if (limit > 0) {
            val y = top + chartH - (limit / mx * chartH).toFloat()
            lp.color = pal.warn
            lp.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            cv.drawLine(0f, y, w, y, lp)
        }
    }
}

class ProgBar(c: Context) : View(c) {
    var frac = 0f
        set(v) { field = v; invalidate() }
    var fillColor = 0
    var trackColor = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null

    fun animateTo(target: Float) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, target)
        a.duration = 500
        a.addUpdateListener { frac = it.animatedValue as Float }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = trackColor
        cv.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        val fw = w * minOf(frac, 1f)
        if (fw > 0f) {
            p.color = fillColor
            cv.drawRoundRect(RectF(0f, 0f, maxOf(fw, h), h), h / 2, h / 2, p)
        }
    }
}
