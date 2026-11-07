package app.viday

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

class AppUse(val uid: Int, val rx: Long, val tx: Long, val fg: Long, val bg: Long) {
    val total: Long get() = rx + tx
}

class NetRes(val rx: Long, val tx: Long, val apps: List<AppUse>)

/** Usage for the current window. src: 0 = system statistics, 1 = counter snapshots. */
class Usage(
    val win: Win?, val rx: Long, val tx: Long, val src: Int, val flags: Int,
    val unc: Long, val apps: List<AppUse>, val ok: Boolean, val at: Long
) {
    val total: Long get() = rx + tx
}

object Eng {
    const val F_LATE_START = 1
    const val F_REBOOT = 2
    const val F_RESET = 4
    const val F_LATE_END = 8
    const val F_CUT = 16

    private const val TOLERANCE_MS = 120_000L

    // ---------- permissions / mode ----------

    @Suppress("DEPRECATION")
    fun hasUsageAccess(c: Context): Boolean {
        return try {
            val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** True when exact figures from Android's own statistics can be used. */
    fun netMode(c: Context): Boolean = Build.VERSION.SDK_INT >= 29 && hasUsageAccess(c)

    // ---------- system network statistics ----------

    @Suppress("DEPRECATION")
    fun netQuery(c: Context, s: Long, e: Long): NetRes? {
        if (e <= s) return NetRes(0, 0, emptyList())
        return try {
            val m = c.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val st = m.querySummary(ConnectivityManager.TYPE_MOBILE, null, s, e)
            val map = HashMap<Int, LongArray>()
            val b = NetworkStats.Bucket()
            while (st.hasNextBucket()) {
                st.getNextBucket(b)
                val a = map.getOrPut(b.uid) { LongArray(4) }
                a[0] += b.rxBytes
                a[1] += b.txBytes
                val t = b.rxBytes + b.txBytes
                if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) a[2] += t
                else if (b.state == NetworkStats.Bucket.STATE_DEFAULT) a[3] += t
            }
            st.close()
            var rx = 0L
            var tx = 0L
            val apps = ArrayList<AppUse>()
            for ((uid, a) in map) {
                rx += a[0]; tx += a[1]
                apps.add(AppUse(uid, a[0], a[1], a[2], a[3]))
            }
            NetRes(rx, tx, apps)
        } catch (ex: Exception) {
            null
        }
    }

    // ---------- counter snapshots (fallback) ----------

    private fun bootCount(c: Context): Int =
        try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (e: Exception) { -1 }

    private fun clearSession(c: Context) {
        P.sp(c).edit().putLong("ts_s", 0L).putLong("ts_e", 0L).putLong("ts_rx", 0L).putLong("ts_tx", 0L)
            .putInt("ts_fl", 0).putLong("ts_unc", 0L).apply()
    }

    @Synchronized
    fun trafficSample(c: Context) {
        val sp = P.sp(c)
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0 || tx < 0) {
            sp.edit().putBoolean("ts_na", true).apply()
            return
        }
        sp.edit().putBoolean("ts_na", false).apply()
        val boot = bootCount(c)
        val el = SystemClock.elapsedRealtime()
        var s = sp.getLong("ts_s", 0L)
        if (s != 0L) {
            val end = sp.getLong("ts_e", 0L)
            var aR = sp.getLong("ts_rx", 0L)
            var aT = sp.getLong("ts_tx", 0L)
            var fl = sp.getInt("ts_fl", 0)
            var unc = sp.getLong("ts_unc", 0L)
            var dR = rx - sp.getLong("ts_lr", 0L)
            var dT = tx - sp.getLong("ts_lt", 0L)
            val lb = sp.getInt("ts_lb", -1)
            val reboot = (boot != -1 && lb != -1 && boot != lb) || el < sp.getLong("ts_le", 0L)
            if (reboot) {
                // Counters restarted from zero: everything since boot belongs to this session.
                dR = rx; dT = tx; fl = fl or F_REBOOT
            } else if (dR < 0 || dT < 0) {
                // Counter went backwards without a reboot: skip this interval and say so.
                dR = 0; dT = 0; fl = fl or F_RESET
            }
            if (now > end) {
                if (now - end <= TOLERANCE_MS) {
                    aR += dR; aT += dT
                } else {
                    unc += dR + dT; fl = fl or F_LATE_END
                }
                if (!netMode(c)) {
                    Db.get(c).save(Day(s, end, aR, aT, P.limitBytes(c), 1, fl, unc, 0))
                }
                clearSession(c)
                s = 0L
            } else {
                aR += dR; aT += dT
                sp.edit().putLong("ts_rx", aR).putLong("ts_tx", aT).putInt("ts_fl", fl).putLong("ts_unc", unc).apply()
            }
        }
        if (s == 0L) {
            val w = Cycle.containing(c, now)
            if (w != null) {
                sp.edit().putLong("ts_s", w.s).putLong("ts_e", w.e).putLong("ts_rx", 0L).putLong("ts_tx", 0L)
                    .putInt("ts_fl", if (now - w.s > TOLERANCE_MS) F_LATE_START else 0).putLong("ts_unc", 0L).apply()
            }
        }
        sp.edit().putLong("ts_lr", rx).putLong("ts_lt", tx).putInt("ts_lb", boot).putLong("ts_le", el).apply()
    }

    /** Closes the running snapshot session now (used when the schedule changes). */
    @Synchronized
    fun cutSession(c: Context) {
        trafficSample(c)
        val sp = P.sp(c)
        val s = sp.getLong("ts_s", 0L)
        if (s != 0L) {
            if (!netMode(c)) {
                Db.get(c).save(
                    Day(s, System.currentTimeMillis(), sp.getLong("ts_rx", 0L), sp.getLong("ts_tx", 0L),
                        P.limitBytes(c), 1, sp.getInt("ts_fl", 0) or F_CUT, sp.getLong("ts_unc", 0L), 0)
                )
            }
            clearSession(c)
        }
        trafficSample(c)
    }

    // ---------- live usage ----------

    fun live(c: Context): Usage {
        val now = System.currentTimeMillis()
        val w = Cycle.containing(c, now)
        val net = netMode(c)
        if (w == null) return Usage(null, 0, 0, if (net) 0 else 1, 0, 0, emptyList(), true, now)
        if (net) {
            val r = netQuery(c, w.s, now)
            if (r != null) return Usage(w, r.rx, r.tx, 0, 0, 0, r.apps, true, now)
        }
        trafficSample(c)
        val sp = P.sp(c)
        val inSession = sp.getLong("ts_s", 0L) == w.s
        return Usage(
            w, sp.getLong("ts_rx", 0L), sp.getLong("ts_tx", 0L), 1, sp.getInt("ts_fl", 0),
            sp.getLong("ts_unc", 0L), emptyList(), inSession && !sp.getBoolean("ts_na", false), now
        )
    }

    /** Fills in finished windows from Android's own statistics. Only used when netMode is on. */
    fun finalizeNet(c: Context, force: Boolean) {
        val sp = P.sp(c)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("fin_at", 0L) < 10 * 60_000L) return
        sp.edit().putLong("fin_at", now).apply()
        val db = Db.get(c)
        val n = if (P.mode(c) == 1) 3 else 14
        for (w in Cycle.list(c, n, now)) {
            if (w.e > now) continue
            val ex = db.get(w.s)
            if (ex != null && ex.cor == 1) continue
            val recent = now - w.e < 3 * 86_400_000L
            if (ex != null && ex.src == 0 && !recent) continue
            val r = netQuery(c, w.s, w.e) ?: continue
            db.save(Day(w.s, w.e, r.rx, r.tx, ex?.lim ?: P.limitBytes(c), 0, 0, 0L, 0))
        }
    }

    // ---------- alarms ----------

    fun tick(c: Context) {
        try {
            trafficSample(c)
            if (netMode(c)) finalizeNet(c, true)
            checkAlerts(c, live(c))
        } catch (e: Exception) {
            // Never crash a background receiver.
        }
        schedule(c)
    }

    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * Next wake-up: the window boundary, plus (only while alerts are on and a threshold is still
     * pending) a light check every 15 minutes, or every 5 minutes when within 10 points of the next one.
     */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sp = P.sp(c)
        val now = System.currentTimeMillis()
        val w = Cycle.containing(c, now)
        val alertsOn = P.notif(c) && P.thresholds(c).any { it.on }
        var at = (w?.e ?: Cycle.nextStart(c, now)) + 1000L
        var boundary = true
        if (w != null && alertsOn) {
            val pct = if (sp.getLong("c_w", 0L) == w.s) sp.getInt("c_pct", -1) else -1
            val next = P.thresholds(c).filter { it.on && it.pct > pct }.minByOrNull { it.pct }
            if (next != null) {
                val near = pct >= 0 && next.pct - pct <= 10
                val minutes = if (P.mode(c) == 1) (if (near) 30L else 60L) else (if (near) 5L else 15L)
                val step = minutes * 60_000L
                if (now + step < at) { at = now + step; boundary = false }
            }
        }
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (boundary && canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC, at, pi)
        }
    }

    // ---------- alerts ----------

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("alerts", "Data usage alerts", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Shown only when your data usage passes a threshold you set."
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    fun canNotify(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /** Called from the alarm and whenever the app refreshes. Each threshold fires once per window. */
    @Synchronized
    fun checkAlerts(c: Context, u: Usage) {
        val w = u.win ?: return
        if (!u.ok) return
        val sp = P.sp(c)
        val lim = P.limitBytes(c)
        val pct = (u.total * 100 / lim).toInt()
        sp.edit().putInt("c_pct", pct).putLong("c_w", w.s).apply()
        if (!sp.getBoolean("notif", false)) return
        if (!canNotify(c)) return
        if (sp.getLong("al_w", 0L) != w.s) sp.edit().putLong("al_w", w.s).putString("al_s", "").apply()
        val done = (sp.getString("al_s", "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
        // If the limit was raised (or usage corrected), thresholds above the new percentage may fire again.
        done.removeAll { (it.toIntOrNull() ?: 0) > pct }
        val hit = P.thresholds(c).filter { it.on && pct >= it.pct && it.pct.toString() !in done }
        hit.forEach { done.add(it.pct.toString()) }
        sp.edit().putString("al_s", done.joinToString(",")).apply()
        if (hit.isEmpty()) return
        notifyUser(c, hit.maxOf { it.pct }, u.total, lim, pct, false)
    }

    /** User-initiated check that notifications can be shown. Returns false if Android blocks them. */
    fun sendTest(c: Context): Boolean {
        if (!canNotify(c)) return false
        val lim = P.limitBytes(c)
        notifyUser(c, 50, lim / 2, lim, 50, true)
        return true
    }

    private fun notifyUser(c: Context, threshold: Int, used: Long, lim: Long, pct: Int, test: Boolean) {
        ensureChannel(c)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val left = if (used > lim) "Exceeded by ${fmt(c, used - lim)}" else "${fmt(c, lim - used)} remaining"
        val head = if (test) "DataFlow: test alert" else "DataFlow: $threshold% of data used"
        val line = "${fmt(c, used)} of ${fmt(c, lim)} used. $left."
        val big = "Used: ${fmt(c, used)} ($pct%)\nLimit: ${fmt(c, lim)}\n$left"
        val n = android.app.Notification.Builder(c, "alerts")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(head)
            .setContentText(line)
            .setStyle(android.app.Notification.BigTextStyle().bigText(big))
            .setCategory(android.app.Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(if (test) 999 else 1000 + threshold, n)
    }
}
