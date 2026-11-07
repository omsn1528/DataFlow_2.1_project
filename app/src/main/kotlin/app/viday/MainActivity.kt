package app.viday

import android.Manifest
import android.app.Activity
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.app.usage.NetworkStats
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class AInfo(val name: String, val icon: Drawable?)

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val HORIZ = LinearLayout.HORIZONTAL
    private val VERT = LinearLayout.VERTICAL

    private var dark = false
    private lateinit var pal: Pal
    private lateinit var body: FrameLayout
    private lateinit var nav: LinearLayout
    private val navIcons = ArrayList<IconView>()
    private val navLabels = ArrayList<TextView>()

    private var tab = 0
    private var onboarding = false
    private var obPage = 0
    private var usage: Usage? = null
    private var days: List<Day> = emptyList()
    private var appRows: List<Pair<AInfo, AppUse>> = emptyList()
    private var hourly: List<Double>? = null
    private var hourlyWin: Win? = null
    private var more = false
    private var speedTv: TextView? = null
    private var lastFrac = 0f
    private var pendingText: String? = null
    private var backCb: Any? = null

    private val hd = Handler(Looper.getMainLooper())
    private var lastR = -1L
    private var lastX = -1L
    private var lastT = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(s: Bundle?) {
        val sp = P.sp(this)
        val th = sp.getInt("theme", 0)
        dark = th == 2 || (th == 0 &&
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) R.style.Theme_DataFlow_Dark else R.style.Theme_DataFlow_Light)
        super.onCreate(s)
        pal = Pal(dark)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.card
        if (!dark) {
            var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
        tab = s?.getInt("tab", 0) ?: 0
        val root = LinearLayout(this)
        root.orientation = VERT
        root.setBackgroundColor(pal.bg)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        nav = buildNav()
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        // Edge-to-edge (required when targeting Android 15+): keep content clear of system bars.
        root.setOnApplyWindowInsetsListener { _, ins ->
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                l = ins.systemWindowInsetLeft; t = ins.systemWindowInsetTop
                r = ins.systemWindowInsetRight; b = ins.systemWindowInsetBottom
            }
            root.setPadding(l, t, r, 0)
            nav.setPadding(0, 0, 0, b)
            ins
        }
        setContentView(root)
        Eng.ensureChannel(this)
        if (!sp.getBoolean("onboarded", false)) showOnboarding() else show(tab)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        if (!onboarding) {
            Eng.schedule(this)
            load()
            if (tab == 0 && more) startSpeed()
        }
    }

    override fun onPause() {
        stopSpeed()
        super.onPause()
    }

    override fun onBackPressed() {
        if (!onboarding && tab != 0) go(0) else super.onBackPressed()
    }

    /** Android 13+ delivers back through a dispatcher callback (required when targeting Android 16). */
    private fun syncBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = !onboarding && tab != 0
        val d = onBackInvokedDispatcher
        val cur = backCb as? android.window.OnBackInvokedCallback
        if (want && cur == null) {
            val cb = android.window.OnBackInvokedCallback { go(0) }
            backCb = cb
            d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else if (!want && cur != null) {
            d.unregisterOnBackInvokedCallback(cur)
            backCb = null
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    /** Re-evaluates alerts and re-arms the alarm without touching the UI. */
    private fun bgTick() {
        Thread { Eng.tick(this) }.start()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No app on this phone can open $url")
        }
    }

    private fun tx(text: String, sp: Float = 14f, color: Int = pal.text, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    private fun ct(text: String, sp: Float = 14f, color: Int = pal.text, bold: Boolean = false): TextView {
        val v = tx(text, sp, color, bold)
        v.gravity = Gravity.CENTER
        return v
    }

    private fun LinearLayout.add(v: View, top: Int = 0): LinearLayout {
        addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) })
        return this
    }

    private fun card(pad: Int = 16): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = VERT
        c.background = round(pal.card, 20)
        c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        return c
    }

    private fun screen(build: (LinearLayout) -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = VERT
        col.setPadding(dp(16), dp(12), dp(16), dp(24))
        build(col)
        val sv = ScrollView(this)
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun iconBtn(kind: Int, desc: String, onClick: () -> Unit): View {
        val f = FrameLayout(this)
        f.contentDescription = desc
        f.isClickable = true
        f.background = rippled(0, 24, pal.line)
        f.addView(IconView(this, kind, pal.text), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit): TextView {
        val b = tx(label, 14f, if (filled) 0xFFFFFFFF.toInt() else pal.accent, true)
        b.gravity = Gravity.CENTER
        b.minHeight = dp(48)
        b.setPadding(dp(16), 0, dp(16), 0)
        b.isClickable = true
        b.background = rippled(if (filled) pal.accent else pal.tint, 14, pal.line)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun titleBar(col: LinearLayout, title: String, back: Boolean) {
        val r = LinearLayout(this)
        r.orientation = HORIZ
        r.gravity = Gravity.CENTER_VERTICAL
        if (back) r.addView(iconBtn(5, "Back") { go(0) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        r.addView(tx(title, 22f, pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.add(r)
    }

    private fun kv(k: String, v: String, vc: Int = pal.text): View {
        val r = LinearLayout(this)
        r.orientation = HORIZ
        r.minimumHeight = dp(36)
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(tx(k, 14f, pal.sub), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(tx(v, 14f, vc))
        return r
    }

    private fun tm(min: Int): String = LocalTime.of(min / 60, min % 60).format(DateTimeFormatter.ofPattern("h:mm a"))
    private fun tms(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
    private fun dts(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
    private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun dateLabel(ms: Long): String = dateOf(ms).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))

    private fun ver(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun notes(flags: Int, unc: Long): List<String> {
        val l = ArrayList<String>()
        if (flags and Eng.F_LATE_START != 0) l.add("Counting began after the window started, so earlier usage in this window is not included.")
        if (flags and Eng.F_REBOOT != 0) l.add("The phone restarted. Usage between the last check and the restart was not captured.")
        if (flags and Eng.F_RESET != 0) l.add("A counter reset was detected. That interval was skipped, not guessed.")
        if (flags and Eng.F_LATE_END != 0 || unc > 0) l.add("The window was closed late. About ${fmt(this, unc)} could not be attributed.")
        if (flags and Eng.F_CUT != 0) l.add("The schedule was changed during this window, so it was saved as a partial record.")
        return l
    }

    private fun hasGap(flags: Int, unc: Long): Boolean =
        (flags and (Eng.F_REBOOT or Eng.F_RESET or Eng.F_LATE_END)) != 0 || unc > 0

    // ---------------------------------------------------------------- navigation

    private fun buildNav(): LinearLayout {
        val n = LinearLayout(this)
        n.orientation = HORIZ
        n.setBackgroundColor(pal.card)
        val names = listOf("Home", "Apps", "Analytics", "History")
        for (i in 0..3) {
            val item = LinearLayout(this)
            item.orientation = VERT
            item.gravity = Gravity.CENTER
            item.isClickable = true
            item.contentDescription = names[i]
            item.background = rippled(0, 0, pal.line)
            val ic = IconView(this, i, pal.sub)
            val lb = tx(names[i], 11f, pal.sub)
            item.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            item.addView(lb, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            item.setOnClickListener { go(i) }
            navIcons.add(ic)
            navLabels.add(lb)
            n.addView(item, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        return n
    }

    private fun updateNav() {
        for (i in 0..3) {
            val sel = tab == i
            navIcons[i].tint = if (sel) pal.accent else pal.sub
            navIcons[i].invalidate()
            navLabels[i].setTextColor(if (sel) pal.accent else pal.sub)
            navLabels[i].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun show(t: Int) {
        tab = t
        stopSpeed()
        body.removeAllViews()
        val v = when (t) {
            0 -> homeView()
            1 -> appsView()
            2 -> analyticsView()
            3 -> historyView()
            else -> settingsView()
        }
        body.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        updateNav()
        syncBack()
        if (t == 0 && more) startSpeed()
    }

    private fun go(t: Int) {
        show(t)
        load()
    }

    // ---------------------------------------------------------------- data loading

    private fun info(uid: Int): AInfo {
        if (uid == NetworkStats.Bucket.UID_TETHERING) return AInfo("Hotspot (tethering)", null)
        if (uid == NetworkStats.Bucket.UID_REMOVED) return AInfo("Removed apps", null)
        if (uid == 1000) return AInfo("Android system", null)
        val pm = packageManager
        val pk = pm.getPackagesForUid(uid)
        if (pk == null || pk.isEmpty()) return AInfo("Other system services (UID $uid)", null)
        return try {
            val ai = pm.getApplicationInfo(pk[0], 0)
            AInfo(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
        } catch (e: Exception) {
            AInfo(pk[0], null)
        }
    }

    private fun hourlyOf(w: Win): List<Double> {
        val out = ArrayList<Double>()
        val now = System.currentTimeMillis()
        var s = w.s
        while (s < w.e && out.size < 48) {
            val e = minOf(s + 3_600_000L, w.e)
            if (s >= now) out.add(0.0) else {
                val r = Eng.netQuery(this, s, minOf(e, now))
                out.add(((r?.rx ?: 0L) + (r?.tx ?: 0L)).toDouble())
            }
            s = e
        }
        return out
    }

    private fun load() {
        val needHourly = tab == 2
        Thread {
            val u = Eng.live(this)
            val net = Eng.netMode(this)
            Eng.checkAlerts(this, u)
            if (net) Eng.finalizeNet(this, false)
            val d = Db.get(this).all()
            val rows = u.apps.filter { it.total > 0 }.sortedByDescending { it.total }.take(50)
                .map { Pair(info(it.uid), it) }
            val hw: Win? = if (needHourly && net && P.mode(this) == 0)
                (u.win ?: Cycle.list(this, 1, System.currentTimeMillis()).firstOrNull()) else null
            val hv: List<Double>? = if (hw != null) hourlyOf(hw) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                usage = u
                days = d
                appRows = rows
                if (needHourly) { hourly = hv; hourlyWin = hw }
                if (!onboarding) show(tab)
            }
        }.start()
    }

    /** Window start -> total bytes, from saved records plus the live window. */
    private fun series(): Map<Long, Long> {
        val m = HashMap<Long, Long>()
        for (d in days) m[d.s] = d.total
        val u = usage
        val w = u?.win
        if (u != null && w != null && u.ok) m[w.s] = u.total
        return m
    }

    private fun forecast(u: Usage?): Long? {
        if (u == null) return null
        val w = u.win ?: return null
        if (!u.ok || (u.flags and Eng.F_LATE_START) != 0) return null
        val el = u.at - w.s
        if (el < 30 * 60_000L) return null
        return (u.total.toDouble() * (w.e - w.s) / el).toLong()
    }

    // ---------------------------------------------------------------- speed (only while visible)

    private val speedTick = object : Runnable {
        override fun run() {
            val tv = speedTv
            if (tv != null && tab == 0 && more) {
                val r = TrafficStats.getMobileRxBytes()
                val x = TrafficStats.getMobileTxBytes()
                val t = SystemClock.elapsedRealtime()
                if (lastT > 0 && lastR >= 0 && r >= 0 && x >= 0 && t > lastT) {
                    val dr = ((r - lastR) * 1000 / (t - lastT)).coerceAtLeast(0)
                    val dx = ((x - lastX) * 1000 / (t - lastT)).coerceAtLeast(0)
                    tv.text = "Down ${fmt(this@MainActivity, dr)}/s  Up ${fmt(this@MainActivity, dx)}/s"
                }
                lastR = r; lastX = x; lastT = t
                hd.postDelayed(this, 2000)
            }
        }
    }

    private fun startSpeed() {
        lastT = 0
        hd.removeCallbacks(speedTick)
        hd.post(speedTick)
    }

    private fun stopSpeed() {
        hd.removeCallbacks(speedTick)
    }

    // ---------------------------------------------------------------- HOME

    private fun status(u: Usage?): Pair<String, Int> {
        if (u == null) return Pair("Loading", pal.sub)
        if (!u.ok) return Pair("Data unavailable", pal.bad)
        val w = u.win ?: return Pair("Not counting. Resumes ${tms(Cycle.nextStart(this, u.at))}", pal.sub)
        if (hasGap(u.flags, u.unc)) return Pair("Monitoring gap detected", pal.warn)
        if ((u.flags and Eng.F_LATE_START) != 0) return Pair("Counting. Started mid-window", pal.good)
        return Pair("Counting until ${tms(w.e)}", pal.good)
    }

    private fun stat(label: String, value: String, sub: String?): View {
        val c = card(14)
        c.add(tx(label, 12f, pal.sub))
        c.add(tx(value, 18f, pal.text, true), 2)
        if (sub != null) c.add(tx(sub, 11f, pal.sub), 2)
        return c
    }

    private fun connText(): String {
        return try {
            val cm = getSystemService(Context_CONNECTIVITY) as ConnectivityManager
            val n = cm.activeNetwork
            val cap = if (n != null) cm.getNetworkCapabilities(n) else null
            when {
                cap == null -> "Offline"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data (counted)"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (not counted)"
                else -> "Other network (not counted)"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun operatorName(): String {
        return try {
            val n = (getSystemService(TELEPHONY) as TelephonyManager).networkOperatorName
            if (n.isNullOrEmpty()) "Unknown" else n
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun homeView(): View = screen { col ->
        val u = usage
        val monthly = P.mode(this) == 1

        val top = LinearLayout(this)
        top.orientation = HORIZ
        top.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        logo.contentDescription = "DataFlow logo"
        top.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)))
        val ttl = LinearLayout(this)
        ttl.orientation = VERT
        ttl.setPadding(dp(10), 0, 0, 0)
        ttl.addView(tx("DataFlow", 20f, pal.text, true))
        ttl.addView(tx(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM")), 12f, pal.sub))
        top.addView(ttl, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(iconBtn(4, "Settings") { go(4) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.add(top)

        val st = status(u)
        val pill = tx(st.first, 13f, st.second, true)
        pill.setPadding(dp(12), dp(6), dp(12), dp(6))
        pill.background = round(pal.tint, 16)
        col.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })

        val lim = P.limitBytes(this)
        val used = u?.total ?: 0L
        val frac = (used.toDouble() / lim).toFloat()
        val pct = (used * 100 / lim).toInt()
        val main = card()
        main.gravity = Gravity.CENTER_HORIZONTAL
        val ring = RingView(this)
        ring.ringColor = if (frac >= 1f) pal.bad else if (frac >= 0.75f) pal.warn else pal.good
        ring.trackColor = pal.line
        ring.t1 = if (u == null) "-" else "$pct%"
        ring.t2 = "consumed"
        ring.c1 = pal.text
        ring.c2 = pal.sub
        ring.contentDescription = "$pct percent of your data limit used"
        ring.frac = lastFrac
        ring.animateTo(minOf(frac, 1f))
        lastFrac = minOf(frac, 1f)
        main.addView(ring, LinearLayout.LayoutParams(dp(200), dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        main.add(ct(if (monthly) "Used this cycle" else "Used today", 13f, pal.sub), 10)
        main.add(ct(fmt(this, used), 30f, pal.text, true))
        main.add(ct("of ${fmt(this, lim)} limit", 14f, pal.sub))
        if (used > lim) main.add(ct("Exceeded by ${fmt(this, used - lim)}", 15f, pal.bad, true), 6)
        else main.add(ct("${fmt(this, lim - used)} remaining", 15f, pal.text, true), 6)
        val est = forecast(u)
        if (est != null) {
            val over = est > lim
            main.add(
                ct("Estimated by end of window: ${fmt(this, est)}" + (if (over) ". May exceed your limit." else ""),
                    12f, if (over) pal.warn else pal.sub), 8
            )
        }
        if (u != null) {
            main.add(ct("Last updated ${tms(u.at)}", 12f, pal.sub), 8)
            main.addView(btn("Refresh") { load() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        col.add(main, 12)

        val m = series()
        val now = System.currentTimeMillis()
        val cur = u?.win
        val prevKey = m.keys.filter { cur == null || it < cur.s }.maxOrNull()
        val wk = Cycle.list(this, 7, now).mapNotNull { m[it.s] }
        val ymNow = YearMonth.now()
        val month = m.filter { YearMonth.from(dateOf(it.key)) == ymNow }.values.sum()
        val high = m.entries.sortedByDescending { it.key }.take(30).maxByOrNull { it.value }
        fun row(a: View, b: View, top: Int) {
            val r = LinearLayout(this)
            r.orientation = HORIZ
            r.addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
            r.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            col.add(r, top)
        }
        row(
            stat(if (monthly) "Last cycle" else "Yesterday", if (prevKey != null) fmt(this, m[prevKey] ?: 0L) else "-", null),
            stat("Average", if (wk.isEmpty()) "-" else fmt(this, wk.sum() / wk.size), "recent windows"), 12
        )
        row(
            stat("This month", fmt(this, month), null),
            stat("Highest", if (high != null) fmt(this, high.value) else "-", if (high != null) dateOf(high.key).format(DateTimeFormatter.ofPattern("d MMM")) else null), 12
        )

        val moreCard = card()
        val head = LinearLayout(this)
        head.orientation = HORIZ
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(40)
        head.isClickable = true
        head.addView(tx("More info", 15f, pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val toggle = tx(if (more) "Hide" else "Show", 14f, pal.accent, true)
        head.addView(toggle)
        val box = LinearLayout(this)
        box.orientation = VERT
        box.visibility = if (more) View.VISIBLE else View.GONE
        if (u != null && u.ok) {
            box.add(kv("Downloaded", fmt(this, u.rx)))
            box.add(kv("Uploaded", fmt(this, u.tx)))
        }
        val sv = tx("Measuring", 14f, pal.text)
        val srow = LinearLayout(this)
        srow.orientation = HORIZ
        srow.minimumHeight = dp(36)
        srow.gravity = Gravity.CENTER_VERTICAL
        srow.addView(tx("Live speed", 14f, pal.sub), LinearLayout.LayoutParams(0, WRAP, 1f))
        srow.addView(sv)
        box.add(srow)
        speedTv = sv
        box.add(kv("Connection", connText()))
        box.add(kv("Network", operatorName()))
        if (cur != null) box.add(kv("Window", "${tms(cur.s)} to ${tms(cur.e)}"))
        if (u != null) box.add(kv("Measured with", if (u.src == 0) "System statistics" else "Counter snapshots (approx.)"))
        if (u != null) for (n in notes(u.flags, u.unc)) box.add(tx(n, 12f, pal.warn), 6)
        head.setOnClickListener {
            more = !more
            box.visibility = if (more) View.VISIBLE else View.GONE
            toggle.text = if (more) "Hide" else "Show"
            if (more) startSpeed() else stopSpeed()
        }
        moreCard.add(head)
        moreCard.add(box)
        col.add(moreCard, 12)

        col.add(ct("Always ON", 13f, pal.sub, true), 24)
        col.add(ct("DataFlow ${ver()}", 11f, pal.sub))
    }

    // ---------------------------------------------------------------- APPS

    private fun appsView(): View = screen { col ->
        titleBar(col, "App usage", false)
        val u = usage
        val w = u?.win
        col.add(tx(if (w != null) "Mobile data only, since ${dts(w.s)}" else "Mobile data only", 12f, pal.sub))
        if (Build.VERSION.SDK_INT < 29) {
            col.add(card().add(tx("Per-app mobile data is not available on this Android version.", 14f, pal.sub)), 12)
        } else if (!Eng.hasUsageAccess(this)) {
            val c = card()
            c.add(tx("Per-app figures come from Android's own network statistics, which needs Usage access. DataFlow only reads data counters, never what you do inside apps.", 14f, pal.text))
            c.add(btn("Open Usage access settings", true) { openUsage() }, 12)
            col.add(c, 12)
        } else if (appRows.isEmpty()) {
            col.add(card().add(tx(if (w == null) "No counting window is active right now." else "No app has used mobile data in this window yet.", 14f, pal.sub)), 12)
        } else {
            val mx = appRows[0].second.total.toDouble()
            for ((ai, au) in appRows) {
                val r = LinearLayout(this)
                r.orientation = HORIZ
                r.gravity = Gravity.CENTER_VERTICAL
                r.minimumHeight = dp(64)
                r.setPadding(dp(14), dp(8), dp(14), dp(8))
                r.background = rippled(pal.card, 16, pal.line)
                r.isClickable = true
                if (ai.icon != null) {
                    val iv = ImageView(this)
                    iv.setImageDrawable(ai.icon)
                    r.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
                } else {
                    val lt = ct(ai.name.take(1).uppercase(), 16f, pal.accent, true)
                    lt.background = round(pal.tint, 20)
                    r.addView(lt, LinearLayout.LayoutParams(dp(40), dp(40)))
                }
                val mid = LinearLayout(this)
                mid.orientation = VERT
                mid.setPadding(dp(12), 0, dp(8), 0)
                mid.addView(tx(ai.name, 15f, pal.text, true))
                val frac = (au.total / mx).toFloat().coerceIn(0.02f, 1f)
                val bar = LinearLayout(this)
                bar.orientation = HORIZ
                bar.addView(View(this).apply { background = round(pal.accent, 3) }, LinearLayout.LayoutParams(0, dp(6), frac))
                bar.addView(View(this), LinearLayout.LayoutParams(0, dp(6), 1f - frac + 0.0001f))
                mid.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(tx(fmt(this, au.total), 14f, pal.text, true))
                r.setOnClickListener { appDialog(ai, au) }
                col.add(r, 8)
            }
        }
    }

    private fun appDialog(ai: AInfo, au: AppUse) {
        val total = usage?.total ?: 0L
        val share = if (total > 0) au.total * 100.0 / total else 0.0
        val split = if (au.fg + au.bg > 0)
            "Foreground: ${fmt(this, au.fg)}\nBackground: ${fmt(this, au.bg)}"
        else "Foreground and background split is not reported by this device."
        AlertDialog.Builder(this).setTitle(ai.name)
            .setMessage("Total: ${fmt(this, au.total)}\nDownloaded: ${fmt(this, au.rx)}\nUploaded: ${fmt(this, au.tx)}\nShare of window: ${String.format("%.1f", share)}%\n\n$split")
            .setPositiveButton("Close", null).show()
    }

    // ---------------------------------------------------------------- ANALYTICS

    private fun analyticsView(): View = screen { col ->
        titleBar(col, "Analytics", false)
        val m = series()
        val now = System.currentTimeMillis()
        val lim = P.limitBytes(this)
        val monthly = P.mode(this) == 1
        val n = if (monthly) 6 else 7

        val wc = card()
        wc.add(tx(if (monthly) "Recent cycles" else "Last 7 days", 15f, pal.text, true))
        val ws = Cycle.list(this, n, now).reversed()
        val bv = BarView(this, pal)
        bv.vals = ws.map { (m[it.s] ?: 0L).toDouble() }
        bv.labels = ws.map {
            if (monthly) Cycle.dateOfWin(it).format(DateTimeFormatter.ofPattern("MMM"))
            else Cycle.dateOfWin(it).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        bv.limit = lim.toDouble()
        bv.contentDescription = "Bar chart of recent usage with the limit marked by a dashed line"
        wc.addView(bv, LinearLayout.LayoutParams(MATCH, dp(170)).apply { topMargin = dp(8) })
        val have = ws.filter { m[it.s] != null }
        if (have.isEmpty()) wc.add(tx("No saved usage yet.", 13f, pal.sub), 8)
        else {
            val tot = have.map { m[it.s] ?: 0L }
            val hi = have.maxByOrNull { m[it.s] ?: 0L }!!
            val lo = have.minByOrNull { m[it.s] ?: 0L }!!
            wc.add(kv("Average", fmt(this, tot.sum() / tot.size)), 8)
            wc.add(kv("Highest", "${fmt(this, m[hi.s] ?: 0L)} (${Cycle.dateOfWin(hi).format(DateTimeFormatter.ofPattern("d MMM"))})"))
            wc.add(kv("Lowest", "${fmt(this, m[lo.s] ?: 0L)} (${Cycle.dateOfWin(lo).format(DateTimeFormatter.ofPattern("d MMM"))})"))
        }
        col.add(wc, 12)

        if (!monthly) {
            val ym = YearMonth.now()
            val perDay = DoubleArray(ym.lengthOfMonth())
            var monthTotal = 0L
            var active = 0
            for ((k, v) in m) {
                val d = dateOf(k)
                if (YearMonth.from(d) == ym) {
                    perDay[d.dayOfMonth - 1] += v.toDouble()
                    monthTotal += v
                    active++
                }
            }
            val mc = card()
            mc.add(tx("This month", 15f, pal.text, true))
            val mb = BarView(this, pal)
            mb.vals = perDay.toList()
            mb.labels = (1..perDay.size).map { it.toString() }
            mb.limit = lim.toDouble()
            mb.contentDescription = "Bar chart of daily usage this month"
            mc.addView(mb, LinearLayout.LayoutParams(MATCH, dp(150)).apply { topMargin = dp(8) })
            mc.add(kv("Month total", fmt(this, monthTotal)), 8)
            mc.add(kv("Daily average", if (active > 0) fmt(this, monthTotal / active) else "-"))
            mc.add(kv("Days with data", active.toString()))
            col.add(mc, 12)
        }

        val fc = card()
        fc.add(tx("Forecast (estimated)", 15f, pal.text, true))
        val u = usage
        val est = forecast(u)
        if (est == null) fc.add(tx("Needs at least 30 minutes of counting in the current window.", 13f, pal.sub), 6)
        else {
            val over = est > lim
            fc.add(kv("Estimated total", fmt(this, est), if (over) pal.warn else pal.text), 6)
            fc.add(kv("Estimated remaining", if (over) "0 B" else fmt(this, lim - est)))
            fc.add(tx(if (over) "At the current pace you may exceed your limit by about ${fmt(this, est - lim)}." else "At the current pace you should stay within your limit.", 13f, if (over) pal.warn else pal.good), 6)
            fc.add(tx("Estimate = usage so far scaled to the full window. It is not a measurement.", 11f, pal.sub), 6)
        }
        col.add(fc, 12)

        val tc = card()
        tc.add(tx("Hourly timeline", 15f, pal.text, true))
        val hv = hourly
        val hw = hourlyWin
        if (monthly) tc.add(tx("The hourly timeline is shown for daily windows only.", 13f, pal.sub), 6)
        else if (!Eng.netMode(this)) tc.add(tx("The hourly timeline needs Usage access. Without it DataFlow cannot split usage by hour.", 13f, pal.sub), 6)
        else if (hv == null || hw == null) tc.add(tx("Loading", 13f, pal.sub), 6)
        else {
            val hb = BarView(this, pal)
            hb.vals = hv
            hb.labels = hv.indices.map { tms(hw.s + it * 3_600_000L).replace(":00", "").replace(" ", "") }
            hb.contentDescription = "Bar chart of hourly usage"
            tc.addView(hb, LinearLayout.LayoutParams(MATCH, dp(150)).apply { topMargin = dp(8) })
            tc.add(tx("Android stores statistics in time buckets, so hourly figures are approximate. The window total is exact.", 11f, pal.sub), 6)
        }
        col.add(tc, 12)
    }

    // ---------------------------------------------------------------- HISTORY

    private fun quality(d: Day, live: Boolean): Pair<String, Int> {
        val srcTxt = if (d.src == 0) "system statistics" else "counter snapshots"
        if (d.cor == 1) return Pair("Corrected by you", pal.accent)
        if (hasGap(d.fl, d.unc)) return Pair("Gap: some usage not captured ($srcTxt)", pal.warn)
        if ((d.fl and (Eng.F_LATE_START or Eng.F_CUT)) != 0) return Pair("Partial window ($srcTxt)", pal.warn)
        return Pair((if (live) "In progress" else "Complete") + " ($srcTxt)", pal.good)
    }

    private fun historyView(): View = screen { col ->
        titleBar(col, "History", false)
        val u = usage
        val w = u?.win
        val list = ArrayList<Pair<Day, Boolean>>()
        if (u != null && w != null && u.ok) list.add(Pair(Day(w.s, w.e, u.rx, u.tx, P.limitBytes(this), u.src, u.flags, u.unc, 0), true))
        for (d in days) if (w == null || d.s != w.s) list.add(Pair(d, false))
        if (list.isEmpty()) {
            col.add(card().add(tx("No history yet. Finished windows are saved here automatically.", 14f, pal.sub)), 12)
            return@screen
        }
        for ((d, live) in list) {
            val c = card(14)
            c.isClickable = true
            c.background = rippled(pal.card, 20, pal.line)
            val top = LinearLayout(this)
            top.orientation = HORIZ
            top.addView(tx(dateLabel(d.s), 15f, pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(tx(fmt(this, d.total), 15f, pal.text, true))
            c.add(top)
            val pct = d.total * 100 / d.lim
            c.add(tx("Down ${fmt(this, d.rx)}   Up ${fmt(this, d.tx)}   Limit ${fmt(this, d.lim)}   $pct%", 12f, pal.sub), 4)
            val q = quality(d, live)
            c.add(tx(q.first, 12f, q.second), 4)
            c.setOnClickListener { dayDialog(d, live) }
            col.add(c, 10)
        }
    }

    private fun sheetTile(label: String, value: String, glyph: String, color: Int): View {
        val c = LinearLayout(this)
        c.orientation = VERT
        c.background = round(pal.tint, 16)
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val badge = ct(glyph, 16f, color, true)
        badge.background = round(pal.card, 14)
        c.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)))
        c.add(tx(value, 18f, pal.text, true), 8)
        c.add(tx(label, 12f, pal.sub), 2)
        return c
    }

    private fun chip(text: String, color: Int): TextView {
        val v = tx(text, 12f, color, true)
        v.setPadding(dp(10), dp(5), dp(10), dp(5))
        v.background = round(pal.tint, 12)
        return v
    }

    /** Bottom sheet that shows exactly the record the tapped History card shows. */
    private fun dayDialog(d: Day, live: Boolean) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frac = (d.total.toDouble() / d.lim).toFloat()
        val pct = (d.total * 100 / d.lim).toInt()
        val barCol = if (frac >= 1f) pal.bad else if (frac >= 0.75f) pal.warn else pal.good
        val cyc = d.e - d.s > 36 * 3_600_000L
        val q = quality(d, live)
        val short = if (d.cor == 1) "Corrected"
        else if (hasGap(d.fl, d.unc)) "Gap detected"
        else if ((d.fl and (Eng.F_LATE_START or Eng.F_CUT)) != 0) "Partial window"
        else if (live) "In progress" else "Complete"
        val inProgress = live && short == "In progress"

        val rad = dpf(24f)
        val sheet = LinearLayout(this)
        sheet.orientation = VERT
        val bg = GradientDrawable()
        bg.setColor(pal.card)
        bg.cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        sheet.background = bg
        sheet.setPadding(dp(20), dp(10), dp(20), dp(28))

        val grab = View(this)
        grab.background = round(pal.line, 2)
        sheet.addView(grab, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val day = dateOf(d.s)
        val head = LinearLayout(this)
        head.orientation = HORIZ
        head.gravity = Gravity.CENTER_VERTICAL
        val hc = LinearLayout(this)
        hc.orientation = VERT
        hc.addView(tx(day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), 22f, pal.text, true))
        hc.addView(tx(day.format(DateTimeFormatter.ofPattern("d MMMM yyyy")), 14f, pal.sub))
        head.addView(hc, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(iconBtn(8, "Close") { dlg.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.add(head, 4)

        val chips = LinearLayout(this)
        chips.orientation = HORIZ
        val statusChip = chip((if (inProgress) "● " else if (short == "Complete") "✓ " else "! ") + short,
            if (inProgress) pal.accent else q.second)
        chips.addView(statusChip)
        chips.addView(chip(if (cyc) "Billing cycle window" else "Daily window", pal.sub),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        sheet.add(chips, 12)
        if (inProgress) {
            val a = ValueAnimator.ofFloat(1f, 0.5f)
            a.duration = 900
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener { statusChip.alpha = it.animatedValue as Float }
            a.start()
            dlg.setOnDismissListener { a.cancel() }
        }

        val total = LinearLayout(this)
        total.orientation = VERT
        total.background = round(pal.tint, 20)
        total.setPadding(dp(18), dp(16), dp(18), dp(16))
        total.add(tx("Total used", 13f, pal.sub))
        total.add(tx(fmt(this, d.total), 34f, pal.text, true), 2)
        total.add(tx("of ${fmt(this, d.lim)} ${if (cyc) "cycle" else "daily"} limit", 14f, pal.sub), 2)
        val bar = ProgBar(this)
        bar.fillColor = barCol
        bar.trackColor = pal.line
        bar.contentDescription = "$pct percent of the limit used"
        bar.animateTo(minOf(frac, 1f))
        total.addView(bar, LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(14) })
        val pr = LinearLayout(this)
        pr.orientation = HORIZ
        pr.addView(tx("$pct% used", 13f, barCol, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        pr.addView(
            if (d.total > d.lim) tx("Exceeded by ${fmt(this, d.total - d.lim)}", 13f, pal.bad, true)
            else tx("${fmt(this, d.lim - d.total)} remaining", 13f, pal.text, true)
        )
        total.add(pr, 8)
        sheet.add(total, 14)

        val tiles = LinearLayout(this)
        tiles.orientation = HORIZ
        tiles.addView(sheetTile("Downloaded", fmt(this, d.rx), "↓", pal.accent),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        tiles.addView(sheetTile("Uploaded", fmt(this, d.tx), "↑", pal.good),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        sheet.add(tiles, 12)

        sheet.add(kv("Window start", dts(d.s)), 12)
        sheet.add(kv("Window end", dts(d.e)))
        sheet.add(kv("Source", if (d.src == 0) "System statistics" else "Counter snapshots (approx.)"))
        if (live) sheet.add(kv("Last updated", tms(usage?.at ?: System.currentTimeMillis())))
        if (cyc) sheet.add(tx("This record covers a whole billing cycle, not a single day.", 12f, pal.sub), 4)
        for (n in notes(d.fl, d.unc)) sheet.add(tx(n, 12f, pal.warn), 6)

        if (!live) {
            val br = LinearLayout(this)
            br.orientation = HORIZ
            br.addView(btn("Correct usage") { dlg.dismiss(); editDay(d) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
            br.addView(btn("Delete") { dlg.dismiss(); confirmDelete(d) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            sheet.add(br, 16)
        }
        sheet.add(btn("Close", true) { dlg.dismiss() }, if (live) 16 else 8)

        val sv = ScrollView(this)
        sv.addView(sheet, ViewGroup.LayoutParams(MATCH, WRAP))
        dlg.setContentView(sv)
        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(0))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(minOf(resources.displayMetrics.widthPixels, dp(560)), WRAP)
            w.setWindowAnimations(R.style.DfSheetAnim)
            w.setDimAmount(0.5f)
        }
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
    }

    private fun editDay(d: Day) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Total in MB"
        AlertDialog.Builder(this).setTitle("Correct usage").setMessage("Enter the correct total in MB. Only this day changes.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val v = et.text.toString().toDoubleOrNull()
                if (v == null || v < 0 || v > 10_000_000) {
                    toast("Enter a number of MB between 0 and 10,000,000.")
                } else {
                    val b = P.base(this).toDouble()
                    val newTotal = (v * b * b).toLong()
                    val rx = if (d.total > 0) (newTotal * (d.rx.toDouble() / d.total)).toLong() else newTotal
                    Db.get(this).force(d.copy(rx = rx, tx = newTotal - rx, cor = 1))
                    load()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(d: Day) {
        AlertDialog.Builder(this).setTitle("Delete this record?").setMessage("${dateLabel(d.s)} will be removed from history.")
            .setPositiveButton("Delete") { _, _ -> Db.get(this).delete(d.s); load() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- SETTINGS

    private fun row(title: String, value: String?, onClick: (() -> Unit)?): View {
        val r = LinearLayout(this)
        r.orientation = HORIZ
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(52)
        r.setPadding(dp(16), dp(8), dp(16), dp(8))
        r.addView(tx(title, 15f, pal.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (value != null) r.addView(tx(value, 14f, pal.sub))
        if (onClick != null) {
            r.isClickable = true
            r.background = rippled(0, 0, pal.line)
            r.setOnClickListener { onClick() }
        }
        return r
    }

    private fun linkRow(title: String, value: String, icon: Int, url: String): View {
        val r = row(title, value) { openUrl(url) } as LinearLayout
        r.addView(IconView(this, icon, pal.accent), 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        r.contentDescription = "$title profile $value, opens in your browser"
        return r
    }

    private fun switchRow(title: String, sub: String?, on: Boolean, cb: (Boolean) -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = HORIZ
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(56)
        r.setPadding(dp(16), dp(6), dp(16), dp(6))
        val t = LinearLayout(this)
        t.orientation = VERT
        t.addView(tx(title, 15f, pal.text))
        if (sub != null) t.addView(tx(sub, 12f, pal.sub))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = on
        sw.contentDescription = title
        sw.setOnCheckedChangeListener { _, b -> cb(b) }
        r.addView(sw)
        return r
    }

    private fun group(col: LinearLayout, title: String, vararg rows: View) {
        val h = tx(title.uppercase(), 12f, pal.sub, true)
        h.setPadding(dp(4), 0, 0, 0)
        col.add(h, 20)
        val c = LinearLayout(this)
        c.orientation = VERT
        c.background = round(pal.card, 20)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) {
                val line = View(this)
                line.setBackgroundColor(pal.line)
                c.addView(line, LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = dp(16) })
            }
            c.addView(r)
        }
        col.add(c, 6)
    }

    private fun choose(title: String, items: Array<String>, sel: Int, cb: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items, sel) { d, w -> d.dismiss(); cb(w) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun settingsView(): View = screen { col ->
        val sp = P.sp(this)
        titleBar(col, "Settings", true)
        val mode = P.mode(this)

        val gen = ArrayList<View>()
        gen.add(row("Data limit", P.limitLabel(this)) { limitDialog() })
        gen.add(row("Counting mode", if (mode == 0) "Daily window" else "Monthly cycle") {
            choose("Counting mode", arrayOf("Daily window", "Monthly cycle"), mode) { i ->
                if (i != mode) confirmSchedule { sp.edit().putInt("mode", i).apply() }
            }
        })
        if (mode == 0) {
            gen.add(row("Counting starts", tm(P.startMin(this))) {
                pickTime(P.startMin(this)) { v -> confirmSchedule { sp.edit().putInt("sm", v).apply() } }
            })
            gen.add(row("Counting ends", tm(P.endMin(this))) {
                pickTime(P.endMin(this)) { v -> confirmSchedule { sp.edit().putInt("em", v).apply() } }
            })
        } else {
            gen.add(row("Cycle resets on day", P.cycleDay(this).toString()) {
                val items = Array(28) { (it + 1).toString() }
                choose("Cycle resets on day", items, P.cycleDay(this) - 1) { i -> confirmSchedule { sp.edit().putInt("cday", i + 1).apply() } }
            })
            gen.add(row("Cycle resets at", tm(P.startMin(this))) {
                pickTime(P.startMin(this)) { v -> confirmSchedule { sp.edit().putInt("sm", v).apply() } }
            })
        }
        val themeNames = arrayOf("System default", "Light", "Dark")
        gen.add(row("Theme", themeNames[sp.getInt("theme", 0)]) {
            choose("Theme", themeNames, sp.getInt("theme", 0)) { i -> sp.edit().putInt("theme", i).apply(); recreate() }
        })
        gen.add(row("Data units", if (P.base(this) == 1024) "1 GB = 1024 MB" else "1 GB = 1000 MB") {
            choose("Data units", arrayOf("1 GB = 1024 MB (most carriers)", "1 GB = 1000 MB (Android settings)"),
                if (P.base(this) == 1024) 0 else 1) { i ->
                sp.edit().putInt("base", if (i == 0) 1024 else 1000).apply(); load()
            }
        })
        group(col, "General", *gen.toTypedArray())

        val nt = ArrayList<View>()
        val nmOk = (getSystemService(NotificationManager::class.java)).areNotificationsEnabled()
        val alertSub = if (P.notif(this) && !nmOk) "Blocked in Android settings" else "Notify when usage passes a threshold"
        nt.add(switchRow("Data alerts", alertSub, P.notif(this)) { setNotif(it) })
        nt.add(row("Android notification permission", if (nmOk) "Allowed" else "Blocked") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (e: Exception) {
                toast("Open Settings, Apps, DataFlow, Notifications.")
            }
        })
        for (t in P.thresholds(this)) {
            val r = switchRow("Alert at ${t.pct}%", null, t.on) { b ->
                P.saveThresholds(this, P.thresholds(this).map { if (it.pct == t.pct) Thr(it.pct, b) else it })
                bgTick()
            }
            val del = tx("Remove", 13f, pal.bad, true)
            del.gravity = Gravity.CENTER
            del.minHeight = dp(48)
            del.setPadding(dp(12), 0, dp(4), 0)
            del.contentDescription = "Remove ${t.pct} percent alert"
            del.isClickable = true
            del.setOnClickListener {
                P.saveThresholds(this, P.thresholds(this).filter { it.pct != t.pct })
                bgTick()
                show(4)
            }
            (r as LinearLayout).addView(del)
            nt.add(r)
        }
        nt.add(row("Add threshold", "+") { addThreshold() })
        nt.add(row("Send test alert", "Try") { testAlert() })
        group(col, "Notifications", *nt.toTypedArray())

        val precise = Eng.netMode(this)
        val mo = ArrayList<View>()
        mo.add(row("Measurement", if (precise) "Precise (system statistics)" else "Approximate (snapshots)", null))
        mo.add(row("Usage access", if (Eng.hasUsageAccess(this)) "Allowed" else "Not allowed") { openUsage() })
        mo.add(row("Battery optimization", "Open") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e: Exception) { toast("Open Settings, Battery, App battery saver.") }
        })
        mo.add(row("Compare measurement sources", "Run") { compareSources() })
        group(col, "Monitoring", *mo.toTypedArray())
        col.add(tx(
            "Network: ${operatorName()}. Android does not give apps per-SIM statistics, so DataFlow measures all mobile data. " +
                "On dual-SIM phones both SIMs are combined. On Xiaomi/HyperOS, set Battery saver to No restrictions and enable Autostart for DataFlow so alerts arrive on time.",
            12f, pal.sub
        ), 8)

        group(col, "Data",
            row("Export history (CSV)", null) { pendingText = csv(); createDoc(11, "text/csv", "dataflow-history.csv") },
            row("Backup (JSON)", null) { pendingText = backupJson(); createDoc(12, "application/json", "dataflow-backup.json") },
            row("Restore backup", null) {
                AlertDialog.Builder(this).setTitle("Restore backup?")
                    .setMessage("This replaces your current history and settings with the backup file.")
                    .setPositiveButton("Choose file") { _, _ ->
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "*/*"
                        startActivityForResult(i, 13)
                    }.setNegativeButton("Cancel", null).show()
            },
            row("Clear history", null) {
                AlertDialog.Builder(this).setTitle("Clear all history?").setMessage("All saved records will be deleted. This cannot be undone.")
                    .setPositiveButton("Clear") { _, _ -> Db.get(this).clear(); load() }
                    .setNegativeButton("Cancel", null).show()
            })

        group(col, "About",
            row("Version", ver(), null),
            row("Developer", "Always ON", null),
            linkRow("GitHub", "omsn1528", 9, "https://github.com/omsn1528"),
            row("Privacy", "Read") {
                AlertDialog.Builder(this).setTitle("Privacy")
                    .setMessage("DataFlow has no internet permission and sends nothing anywhere. Usage figures, history and settings stay on this phone. Usage access only lets the app read Android's own data counters, not what you do inside other apps. There are no ads and no trackers.")
                    .setPositiveButton("Close", null).show()
            },
            row("Show introduction", null) { obPage = 0; showOnboarding() })
        col.add(ct("Always ON", 13f, pal.sub, true), 24)
    }

    private fun limitDialog() {
        val labels = arrayOf("500 MB", "1 GB", "1.5 GB", "2 GB", "2.5 GB", "3 GB", "4 GB", "5 GB", "Custom")
        val vals = floatArrayOf(500f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
        choose("Data limit", labels, -1) { i ->
            if (i == 8) customLimit() else setLimit(vals[i], if (i == 0) "MB" else "GB")
        }
    }

    private fun setLimit(v: Float, u: String) {
        P.sp(this).edit().putFloat("lim_v", v).putString("lim_u", u).apply()
        Eng.schedule(this)
        load()
    }

    private fun customLimit() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Amount"
        fun apply(unit: String) {
            val v = et.text.toString().toFloatOrNull()
            if (v == null || v <= 0f || v > 100000f) toast("Enter an amount greater than 0.") else setLimit(v, unit)
        }
        AlertDialog.Builder(this).setTitle("Custom limit").setMessage("Enter the amount, then choose its unit.")
            .setView(et)
            .setPositiveButton("GB") { _, _ -> apply("GB") }
            .setNegativeButton("MB") { _, _ -> apply("MB") }
            .setNeutralButton("Cancel", null).show()
    }

    private fun pickTime(cur: Int, cb: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> cb(h * 60 + m) }, cur / 60, cur % 60, false).show()
    }

    private fun confirmSchedule(change: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Change counting schedule?")
            .setMessage("The change applies immediately. The window in progress is saved as a partial record, and a new window starts if the current time falls inside the new schedule. Earlier records keep their original windows.")
            .setPositiveButton("Apply") { _, _ ->
                change()
                Thread {
                    Eng.cutSession(this)
                    Eng.schedule(this)
                    runOnUiThread { load() }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addThreshold() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "Percent, 1 to 300"
        AlertDialog.Builder(this).setTitle("Add threshold").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                val cur = P.thresholds(this)
                if (v == null || v < 1 || v > 300) toast("Enter a whole number from 1 to 300.")
                else if (cur.any { it.pct == v }) toast("That threshold already exists.")
                else { P.saveThresholds(this, cur + Thr(v, true)); bgTick(); show(4) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setNotif(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 21)
            return
        }
        P.sp(this).edit().putBoolean("notif", on).apply()
        bgTick()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            P.sp(this).edit().putBoolean("notif", ok).apply()
            if (!ok) toast("Notifications are not allowed, so alerts cannot be shown.")
            bgTick()
            if (!onboarding) show(tab)
        } else if (requestCode == 22) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) testAlert()
            else toast("Notification permission was not granted.")
        }
    }

    private fun testAlert() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
            return
        }
        toast(
            if (Eng.sendTest(this)) "Test alert sent."
            else "Android is blocking DataFlow notifications. Allow them in the notification permission row."
        )
    }

    private fun openUsage() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings, Apps, Special access, Usage access.")
            }
        }
    }

    private fun compareSources() {
        Thread {
            val now = System.currentTimeMillis()
            val w = Cycle.containing(this, now)
            val msg: String
            if (w == null) {
                msg = "No counting window is active right now, so there is nothing to compare."
            } else {
                Eng.trafficSample(this)
                val sp = P.sp(this)
                val snap = if (sp.getLong("ts_s", 0L) == w.s) sp.getLong("ts_rx", 0L) + sp.getLong("ts_tx", 0L) else -1L
                val net = if (Eng.netMode(this)) Eng.netQuery(this, w.s, now) else null
                val a = if (net != null) fmt(this, net.rx + net.tx) else "not available (needs Usage access)"
                val b = if (snap >= 0) fmt(this, snap) else "not available"
                val diff = if (net != null && snap >= 0) "\nDifference: ${fmt(this, Math.abs(net.rx + net.tx - snap))}" else ""
                msg = "Window: ${dts(w.s)} to now\n\nSystem statistics: $a\nCounter snapshots: $b$diff\n\nUnits: 1 GB = ${P.base(this)} MB. Android's Data usage screen may use 1000. If totals differ by about 2 to 7 percent, try the other unit in Settings. Snapshots only cover the time DataFlow has been running, so they can be lower."
            }
            runOnUiThread { AlertDialog.Builder(this).setTitle("Measurement sources").setMessage(msg).setPositiveButton("Close", null).show() }
        }.start()
    }

    // ---------------------------------------------------------------- export / backup / restore

    private fun createDoc(code: Int, mime: String, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, code)
    }

    private fun csv(): String {
        val sb = StringBuilder("date,window_start,window_end,downloaded_bytes,uploaded_bytes,total_bytes,limit_bytes,percent_used,source,quality,corrected\n")
        val z = ZoneId.systemDefault()
        val f = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        for (d in Db.get(this).all().sortedBy { it.s }) {
            val q = if (d.cor == 1) "corrected" else if (hasGap(d.fl, d.unc)) "gap" else if (d.fl != 0) "partial" else "complete"
            sb.append(dateOf(d.s)).append(',')
                .append(Instant.ofEpochMilli(d.s).atZone(z).format(f)).append(',')
                .append(Instant.ofEpochMilli(d.e).atZone(z).format(f)).append(',')
                .append(d.rx).append(',').append(d.tx).append(',').append(d.total).append(',').append(d.lim).append(',')
                .append(d.total * 100 / d.lim).append(',')
                .append(if (d.src == 0) "system_stats" else "snapshots").append(',')
                .append(q).append(',').append(d.cor).append('\n')
        }
        return sb.toString()
    }

    private fun backupJson(): String {
        val sp = P.sp(this)
        val o = JSONObject()
        o.put("v", 1)
        val s = JSONObject()
        s.put("lim_v", sp.getFloat("lim_v", 2f).toDouble())
        s.put("lim_u", sp.getString("lim_u", "GB") ?: "GB")
        s.put("sm", P.startMin(this))
        s.put("em", P.endMin(this))
        s.put("mode", P.mode(this))
        s.put("cday", P.cycleDay(this))
        s.put("theme", sp.getInt("theme", 0))
        s.put("base", P.base(this))
        s.put("notif", P.notif(this))
        s.put("thr", P.thresholds(this).joinToString(",") { "${it.pct}:${if (it.on) 1 else 0}" })
        o.put("settings", s)
        val arr = JSONArray()
        for (d in Db.get(this).all()) {
            val j = JSONObject()
            j.put("s", d.s); j.put("e", d.e); j.put("rx", d.rx); j.put("tx", d.tx); j.put("lim", d.lim)
            j.put("src", d.src); j.put("fl", d.fl); j.put("unc", d.unc); j.put("cor", d.cor)
            arr.put(j)
        }
        o.put("days", arr)
        return o.toString()
    }

    private fun restore(text: String) {
        try {
            val o = JSONObject(text)
            require(o.getInt("v") == 1)
            val s = o.getJSONObject("settings")
            val limV = s.getDouble("lim_v")
            val limU = s.getString("lim_u")
            require(limV > 0 && limV <= 100000 && (limU == "GB" || limU == "MB"))
            val sm = s.getInt("sm")
            val em = s.getInt("em")
            require(sm in 0..1439 && em in 0..1439)
            val mode = s.getInt("mode")
            require(mode in 0..1)
            val cd = s.getInt("cday")
            require(cd in 1..28)
            val th = s.getInt("theme")
            require(th in 0..2)
            val base = s.getInt("base")
            require(base == 1000 || base == 1024)
            val notif = s.getBoolean("notif")
            val thrRaw = s.getString("thr")
            require(thrRaw.length < 200)
            val thr = P.parseThr(thrRaw)
            val arr = o.getJSONArray("days")
            require(arr.length() <= 5000)
            val list = ArrayList<Day>()
            val maxB = 10_000_000_000_000L
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val d = Day(j.getLong("s"), j.getLong("e"), j.getLong("rx"), j.getLong("tx"), j.getLong("lim"),
                    j.getInt("src"), j.getInt("fl"), j.getLong("unc"), j.getInt("cor"))
                require(d.s > 0 && d.e > d.s && d.rx in 0..maxB && d.tx in 0..maxB && d.lim > 0 && d.lim <= maxB &&
                    d.src in 0..1 && d.fl in 0..63 && d.unc in 0..maxB && d.cor in 0..1)
                list.add(d)
            }
            P.sp(this).edit().putFloat("lim_v", limV.toFloat()).putString("lim_u", limU).putInt("sm", sm).putInt("em", em)
                .putInt("mode", mode).putInt("cday", cd).putInt("theme", th).putInt("base", base).putBoolean("notif", notif).apply()
            P.saveThresholds(this, thr)
            Db.get(this).replaceAll(list)
            Eng.schedule(this)
            toast("Backup restored.")
            recreate()
        } catch (e: Exception) {
            toast("This backup file is not valid. Nothing was changed.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == 11 || requestCode == 12) {
                val text = pendingText ?: return
                contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                pendingText = null
                toast("Saved.")
            } else if (requestCode == 13) {
                val ins = contentResolver.openInputStream(uri) ?: return
                val bos = ByteArrayOutputStream()
                ins.use {
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= 2_000_000)
                        bos.write(buf, 0, n)
                    }
                }
                restore(bos.toString("UTF-8"))
            }
        } catch (e: Exception) {
            toast("Could not read or write that file.")
        }
    }

    // ---------------------------------------------------------------- ONBOARDING

    private fun showOnboarding() {
        onboarding = true
        nav.visibility = View.GONE
        body.removeAllViews()
        body.addView(onboardView(), FrameLayout.LayoutParams(MATCH, MATCH))
        syncBack()
    }

    private fun finishOnboarding() {
        P.sp(this).edit().putBoolean("onboarded", true).apply()
        onboarding = false
        nav.visibility = View.VISIBLE
        Eng.schedule(this)
        go(0)
    }

    private fun onboardView(): View = screen { col ->
        val titles = arrayOf("Welcome to DataFlow", "Your counting window", "Exact numbers", "Alerts and battery")
        val texts = arrayOf(
            "DataFlow shows how much mobile data you use, only within the hours you choose. It works with any SIM or operator and never needs the internet.",
            "By default DataFlow counts from 12:00 PM to 12:00 AM and ignores usage from 12:00 AM to 12:00 PM, which suits plans with free night data. You can change the hours, the limit, or switch to a monthly cycle in Settings.",
            "For exact figures and per-app usage, DataFlow reads Android's own data statistics. That needs Usage access, which you grant in system settings. It only reads counters, never what you do inside apps. You can skip this and use approximate counting instead.",
            "Alerts tell you when usage passes a threshold, such as 75%. DataFlow runs nothing in the background at night and keeps no permanent notification. On Xiaomi or HyperOS phones, set Battery saver to No restrictions and turn on Autostart for DataFlow so alarms are not delayed."
        )
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        logo.contentDescription = "DataFlow logo"
        col.addView(logo, LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(32) })
        col.add(ct(titles[obPage], 24f, pal.text, true), 24)
        col.add(ct(texts[obPage], 15f, pal.sub), 12)
        if (obPage == 2) col.add(btn("Open Usage access settings", true) { openUsage() }, 20)
        if (obPage == 3) col.add(btn("Turn on data alerts", true) { setNotif(true) }, 20)
        col.add(ct((0..3).joinToString("  ") { if (it == obPage) "●" else "○" }, 14f, pal.accent), 24)
        val r = LinearLayout(this)
        r.orientation = HORIZ
        r.addView(btn("Skip") { finishOnboarding() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        r.addView(btn(if (obPage == 3) "Get started" else "Next", true) {
            if (obPage == 3) finishOnboarding() else { obPage++; showOnboarding() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
        col.add(r, 16)
        col.add(ct("Always ON", 13f, pal.sub, true), 32)
    }

    private companion object {
        const val Context_CONNECTIVITY = android.content.Context.CONNECTIVITY_SERVICE
        const val TELEPHONY = android.content.Context.TELEPHONY_SERVICE
    }
}
