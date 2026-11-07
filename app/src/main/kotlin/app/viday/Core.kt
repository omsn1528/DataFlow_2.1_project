package app.viday

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A counting window [s, e) in epoch milliseconds. */
data class Win(val s: Long, val e: Long)

/** One alert threshold (percent of limit) that can be switched on or off. */
data class Thr(val pct: Int, val on: Boolean)

object P {
    fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("df", Context.MODE_PRIVATE)

    /** 1024 (binary) or 1000 (decimal). */
    fun base(c: Context): Int = sp(c).getInt("base", 1024)

    fun limitBytes(c: Context): Long {
        val s = sp(c)
        val b = base(c).toDouble()
        val v = s.getFloat("lim_v", 2f).toDouble()
        val unit = if (s.getString("lim_u", "GB") == "MB") b * b else b * b * b
        return (v * unit).toLong().coerceAtLeast(1L)
    }

    fun limitLabel(c: Context): String {
        val s = sp(c)
        val v = s.getFloat("lim_v", 2f)
        val txt = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()
        return txt + " " + (s.getString("lim_u", "GB") ?: "GB")
    }

    fun startMin(c: Context): Int = sp(c).getInt("sm", 720)
    fun endMin(c: Context): Int = sp(c).getInt("em", 0)
    fun mode(c: Context): Int = sp(c).getInt("mode", 0)
    fun cycleDay(c: Context): Int = sp(c).getInt("cday", 1)
    fun notif(c: Context): Boolean = sp(c).getBoolean("notif", false)

    fun thresholds(c: Context): List<Thr> {
        val raw = sp(c).getString("thr", "50:1,75:1,90:1,100:1") ?: ""
        return parseThr(raw)
    }

    fun saveThresholds(c: Context, l: List<Thr>) {
        sp(c).edit().putString("thr", l.joinToString(",") { "${it.pct}:${if (it.on) 1 else 0}" }).apply()
    }

    fun parseThr(raw: String): List<Thr> =
        raw.split(",").mapNotNull {
            val p = it.split(":")
            val pct = p.getOrNull(0)?.toIntOrNull()
            if (p.size == 2 && pct != null && pct in 1..300) Thr(pct, p[1] == "1") else null
        }.distinctBy { it.pct }.sortedBy { it.pct }
}

fun fmt(c: Context, bytes: Long): String {
    val k = P.base(c).toDouble()
    val v = bytes.toDouble()
    return when {
        v >= k * k * k -> String.format("%.2f GB", v / (k * k * k))
        v >= k * k -> String.format("%.1f MB", v / (k * k))
        v >= k -> String.format("%.0f KB", v / k)
        else -> "$bytes B"
    }
}

object Cycle {
    private fun zone(): ZoneId = ZoneId.systemDefault()

    private fun at(d: LocalDate, min: Int): Long =
        d.atStartOfDay(zone()).plusMinutes(min.toLong()).toInstant().toEpochMilli()

    fun dailyDurMin(c: Context): Int {
        val d = P.endMin(c) - P.startMin(c)
        return if (d > 0) d else d + 1440
    }

    private fun daily(c: Context, d: LocalDate): Win {
        val sm = P.startMin(c)
        val s = at(d, sm)
        val e = d.atStartOfDay(zone()).plusMinutes((sm + dailyDurMin(c)).toLong()).toInstant().toEpochMilli()
        return Win(s, e)
    }

    private fun monthly(c: Context, ym: YearMonth): Win {
        val day = P.cycleDay(c).coerceIn(1, 28)
        val sm = P.startMin(c)
        return Win(at(ym.atDay(day), sm), at(ym.plusMonths(1).atDay(day), sm))
    }

    private fun dateOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()

    /** The window containing t, or null when t is in the excluded period. */
    fun containing(c: Context, t: Long): Win? {
        val d = dateOf(t)
        if (P.mode(c) == 1) {
            val ym = YearMonth.from(d)
            var w = monthly(c, ym)
            if (t < w.s) w = monthly(c, ym.minusMonths(1))
            return w
        }
        for (k in 0..1) {
            val w = daily(c, d.minusDays(k.toLong()))
            if (t >= w.s && t < w.e) return w
        }
        return null
    }

    /** Start of the next window strictly after t (daily mode). */
    fun nextStart(c: Context, t: Long): Long {
        if (P.mode(c) == 1) return containing(c, t)?.e ?: (t + 3600_000L)
        val d = dateOf(t)
        for (k in 0..2) {
            val s = daily(c, d.plusDays(k.toLong())).s
            if (s > t) return s
        }
        return t + 3600_000L
    }

    /** Up to n windows, newest first: the current one (or the latest finished one) and earlier ones. */
    fun list(c: Context, n: Int, now: Long): List<Win> {
        val out = ArrayList<Win>()
        val d = dateOf(now)
        if (P.mode(c) == 1) {
            var ym = YearMonth.from(d)
            if (now < monthly(c, ym).s) ym = ym.minusMonths(1)
            for (i in 0 until n) out.add(monthly(c, ym.minusMonths(i.toLong())))
        } else {
            var day = d
            if (daily(c, day).s > now) day = day.minusDays(1)
            for (i in 0 until n) out.add(daily(c, day.minusDays(i.toLong())))
        }
        return out
    }

    fun dateOfWin(w: Win): LocalDate = dateOf(w.s)
}
