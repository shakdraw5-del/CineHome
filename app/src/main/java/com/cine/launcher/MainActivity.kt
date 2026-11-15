package com.cine.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Your name: shown on the welcome screen and as the avatar letter. */
private const val USER_NAME = "Guest"

/** Global UI size. 0.8 = the "125% -> 100%" shrink. Raise toward 1.0 for bigger, lower for smaller. */
private const val S = 0.8f

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private class AppItem(val label: String, val pkg: String, val info: ResolveInfo, val lean: Boolean, val system: Boolean)
private class VH(val card: FrameLayout, val bgd: GradientDrawable, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(card)

/** Adaptive wallpaper: soft tonal blobs + silk ribbons, tinted by the focused app. Drawn once, then only slid. */
private class WaveView(ctx: Context) : View(ctx) {
    var tone = 0xFFFFFFFF.toInt()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rgb = tone and 0xFFFFFF
        val blobs = arrayOf(floatArrayOf(.15f, .85f, .55f, 0x48.toFloat()), floatArrayOf(.7f, .2f, .6f, 0x34.toFloat()), floatArrayOf(1f, .9f, .5f, 0x2A.toFloat()))
        for (b in blobs) {
            val col = rgb or (b[3].toInt() shl 24)
            fillP.shader = RadialGradient(b[0] * w, b[1] * h, b[2] * h, col, rgb, Shader.TileMode.CLAMP)
            c.drawCircle(b[0] * w, b[1] * h, b[2] * h, fillP)
        }
        p.color = rgb or 0x2E000000
        for (i in 0 until 18) {
            val o = i * h * 0.012f
            path.reset()
            path.moveTo(0f, h * 0.30f + o)
            path.cubicTo(w * 0.28f, h * 0.02f + o * 1.8f, w * 0.55f, h * 0.62f - o, w, h * 0.22f + o * 0.6f)
            c.drawPath(path, p)
        }
    }
}

private class DustView(ctx: Context) : View(ctx) {
    private val xs = FloatArray(32) { Math.random().toFloat() }
    private val ys = FloatArray(32) { Math.random().toFloat() }
    private val sp = FloatArray(32) { 0.2f + Math.random().toFloat() * 0.5f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var t = 0f
        set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        for (i in xs.indices) {
            val y = (ys[i] - t * sp[i] + 1f) % 1f
            p.color = if (i % 3 == 0) 0xAAFFD27A.toInt() else 0x88FFFFFF.toInt()
            c.drawCircle(xs[i] * width, y * height, (1.5f + i % 3) * d, p)
        }
    }
}

/** The Cine Home logo mark: a thin ring and a play triangle that draw themselves on. */
private class LogoView(ctx: Context) : View(ctx) {
    var prog = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE6EAF2.toInt(); strokeCap = Paint.Cap.ROUND }
    private val tri = Path()
    private val seg = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.strokeWidth = 2.5f * resources.displayMetrics.density
        val pad = p.strokeWidth * 2
        c.drawArc(pad, pad, w - pad, h - pad, -90f, 360f * minOf(1f, prog * 1.5f), false, p)
        tri.reset()
        tri.moveTo(w * 0.40f, h * 0.30f); tri.lineTo(w * 0.72f, h * 0.50f); tri.lineTo(w * 0.40f, h * 0.70f); tri.close()
        val m = PathMeasure(tri, false)
        seg.reset()
        m.getSegment(0f, m.length * ((prog - 0.3f) / 0.7f).coerceIn(0f, 1f), seg, true)
        c.drawPath(seg, p)
    }
}

/**
 * Cine Home v6.
 * Intro (logo draws itself + dust) -> welcome avatar -> home.
 * Top bar: Home | Apps | Entertainment | System, time and profile on the right.
 * Tile row: the focused app becomes a wide rectangle showing its logo and name.
 * Remote: arrows = D-pad, OK = confirm, Back and Home = jump to first tile.
 */
class MainActivity : Activity() {
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bg: GradientDrawable
    private lateinit var wave: WaveView
    private lateinit var waveAnim: ObjectAnimator
    private lateinit var home: FrameLayout
    private lateinit var welcome: FrameLayout
    private lateinit var intro: FrameLayout
    private lateinit var dust: DustView
    private lateinit var logo: LogoView
    private lateinit var word: TextView
    private lateinit var avatar: FrameLayout
    private lateinit var greet: TextView
    private lateinit var pillBg: GradientDrawable
    private lateinit var topTime: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroPill: TextView
    private lateinit var heroIcon: ImageView
    private lateinit var heroLayer: FrameLayout
    private lateinit var rv: RecyclerView
    private val adapter = AppAdapter()
    private val tabViews = ArrayList<Pair<TextView, View>>()
    private val avatarBgs = ArrayList<GradientDrawable>()

    private var all: List<AppItem> = emptyList()
    private var lastKey: List<String> = emptyList()
    private var tab = 0
    private var entered = false
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val hueCache = HashMap<String, Float>()
    private val ev = ArgbEvaluator()
    private var focusedPkg = ""
    private var anim: ValueAnimator? = null

    private fun tone(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))
    private fun dp(v: Int) = (v * resources.displayMetrics.density * S).toInt()

    /** Palette: 0 top, 1 bottom, 2 accent, 3 dark tonal card. */
    private fun pal(h: Float) = intArrayOf(tone(h, .34f, .20f), tone(h, .24f, .06f), tone(h, .30f, .96f), tone(h, .38f, .26f))
    private var cur = pal(220f)

    private val tick = object : Runnable {
        override fun run() {
            topTime.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 20_000)
        }
    }

    private fun tv(sz: Float, col: Int, face: String = "sans-serif-medium") = TextView(this).apply {
        textSize = sz * S; setTextColor(col); includeFontPadding = false
        typeface = Typeface.create(face, Typeface.NORMAL)
    }

    private fun oval() = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    /** Circular profile picture. Uses res/drawable-nodpi/profile.jpg if you add one, else your initial. */
    private fun makeAvatar(sp: Float): FrameLayout {
        val d = oval().apply { setColor(cur[3]) }; avatarBgs.add(d)
        return FrameLayout(this).apply {
            background = d; clipToOutline = true
            val id = resources.getIdentifier("profile", "drawable", packageName)
            if (id != 0) addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(id) }, MATCH, MATCH)
            else addView(tv(sp, Color.WHITE, "sans-serif-black").apply { text = USER_NAME.take(1).uppercase(); gravity = Gravity.CENTER }, MATCH, MATCH)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pm = packageManager
        prefs = getSharedPreferences("cine", Context.MODE_PRIVATE)
        bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cur[0], cur[1]))
        val root = FrameLayout(this).apply { background = bg }
        val sw = resources.displayMetrics.widthPixels

        wave = WaveView(this).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null); tone = cur[2] }
        root.addView(wave, FrameLayout.LayoutParams((sw * 1.25f).toInt(), MATCH))
        waveAnim = ObjectAnimator.ofFloat(wave, "translationX", 0f, -sw * 0.2f).apply {
            duration = 20000; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator(); start()
        }

        home = FrameLayout(this).apply {
            clipChildren = false; alpha = 0f; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(home, MATCH, MATCH)
        buildHome(); buildWelcome(root); buildIntro(root)
        setContentView(root)
        paintTheme()
        if (b == null) runIntro() else { welcome.visibility = View.GONE; intro.visibility = View.GONE; enterHome(false) }
    }

    private fun makeTab(label: String, idx: Int): LinearLayout {
        val t = tv(20f, Color.WHITE).apply { text = label; isFocusable = true; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val u = View(this).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(3).toFloat() } }
        t.setOnFocusChangeListener { _, _ -> refreshTabs() }
        t.setOnClickListener { tab = idx; rebuild(); refreshTabs() }
        tabViews.add(t to u)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(t)
            addView(u, LinearLayout.LayoutParams(dp(28), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(2) })
        }
    }

    private fun refreshTabs() {
        tabViews.forEachIndexed { i, (t, u) ->
            t.alpha = if (tab == i || t.isFocused) 1f else 0.55f
            u.visibility = if (tab == i) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun buildHome() {
        val names = listOf("Home", "Apps", "Entertainment", "System")
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            names.forEachIndexed { i, n -> addView(makeTab(n, i), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) }) }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(54); topMargin = dp(28) })

        topTime = tv(16f, Color.WHITE).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(50).toFloat() }
        }
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(topTime)
            addView(makeAvatar(16f), LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(14) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = dp(64); topMargin = dp(24) })

        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
            clipToPadding = false; clipChildren = false
            setPadding(dp(56), dp(24), dp(56), dp(24))
            setHasFixedSize(false); itemAnimator = null; setItemViewCacheSize(8)
        }
        home.addView(rv, FrameLayout.LayoutParams(MATCH, dp(150), Gravity.TOP).apply { topMargin = dp(190) })

        // Hero stage: selected app's name, category, Open pill, faint logo watermark.
        heroTitle = tv(44f, Color.WHITE); heroSub = tv(15f, 0xB3FFFFFF.toInt())
        pillBg = GradientDrawable().apply { cornerRadius = dp(50).toFloat() }
        heroPill = tv(17f, Color.BLACK, "sans-serif-black").apply {
            text = "Open"; setPadding(dp(34), dp(12), dp(34), dp(12)); background = pillBg
        }
        heroIcon = ImageView(this).apply { alpha = 0.14f }
        heroLayer = FrameLayout(this).apply { alpha = 0f }
        heroLayer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heroTitle); addView(heroSub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(heroPill, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(22) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(64); bottomMargin = dp(90) })
        heroLayer.addView(heroIcon, FrameLayout.LayoutParams(dp(260), dp(260), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(90); bottomMargin = dp(60) })
        home.addView(heroLayer, MATCH, MATCH)
        refreshTabs()
    }

    private fun buildWelcome(root: FrameLayout) {
        avatar = makeAvatar(52f).apply {
            foreground = oval().apply { setStroke(dp(4), 0xF2FFFFFF.toInt()) }
            isFocusable = true; setOnClickListener { enterHome(true) }
        }
        greet = tv(18f, 0xB3FFFFFF.toInt())
        welcome = FrameLayout(this).apply { visibility = View.GONE }
        welcome.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(avatar, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(tv(30f, Color.WHITE).apply { text = USER_NAME }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
            addView(greet, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(tv(13f, 0x88FFFFFF.toInt()).apply { text = "PRESS OK"; letterSpacing = 0.3f }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(26) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(welcome, MATCH, MATCH)
    }

    private fun buildIntro(root: FrameLayout) {
        intro = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        dust = DustView(this); logo = LogoView(this)
        word = tv(40f, Color.WHITE, "sans-serif-light").apply {
            text = "CINE HOME"; letterSpacing = 0.45f; alpha = 0f
            paint.shader = LinearGradient(0f, 0f, dp(380).toFloat(), 0f,
                intArrayOf(0xFF8E98AA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8E98AA.toInt()), null, Shader.TileMode.CLAMP)
        }
        intro.addView(dust, MATCH, MATCH)
        intro.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(dp(110), dp(110)))
            addView(word, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(26) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(intro, MATCH, MATCH)
    }

    private fun runIntro() {
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 3600; addUpdateListener { dust.t = it.animatedValue as Float }; start() }
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 300; duration = 1600; interpolator = LinearInterpolator()
            addUpdateListener { logo.prog = it.animatedValue as Float }; start()
        }
        word.animate().alpha(1f).setStartDelay(1500).setDuration(1100).start()
        ValueAnimator.ofFloat(0.45f, 0.12f).apply {
            startDelay = 1500; duration = 1700
            addUpdateListener { word.letterSpacing = it.animatedValue as Float }; start()
        }
        ui.postDelayed({
            intro.animate().alpha(0f).setDuration(600).withEndAction { intro.visibility = View.GONE; showWelcome() }.start()
        }, 3800)
    }

    private fun showWelcome() {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greet.text = if (hr < 12) "Good morning" else if (hr < 17) "Good afternoon" else "Good evening"
        welcome.alpha = 0f; welcome.visibility = View.VISIBLE
        welcome.animate().alpha(1f).setDuration(500).start()
        avatar.requestFocus()
    }

    private fun enterHome(animated: Boolean) {
        entered = true
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (animated) {
            welcome.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(400)
                .withEndAction { welcome.visibility = View.GONE; welcome.scaleX = 1f; welcome.scaleY = 1f }.start()
            home.animate().alpha(1f).setDuration(500).start()
        } else home.alpha = 1f
        focusFirst()
    }

    private fun focusFirst() {
        rv.scrollToPosition(0)
        rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun paintTheme() {
        bg.colors = intArrayOf(cur[0], cur[1])
        for (d in avatarBgs) d.setColor(cur[3])
        pillBg.setColor(cur[2]); heroPill.setTextColor(cur[1])
    }

    override fun onResume() {
        super.onResume()
        if (waveAnim.isPaused) waveAnim.resume()
        ui.post(tick); load()
    }

    override fun onPause() { ui.removeCallbacks(tick); waveAnim.pause(); super.onPause() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (entered) focusFirst() }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (entered) focusFirst() }

    private fun recents(): List<String> =
        (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }

    private fun listFor(t: Int): List<AppItem> = when (t) {
        0 -> {
            val rec = recents().mapNotNull { r -> all.firstOrNull { it.pkg == r } }
            rec + all.filter { a -> rec.none { it.pkg == a.pkg } }.sortedWith(compareBy<AppItem>({ !it.lean }, { it.label.lowercase() }))
        }
        1 -> all
        2 -> all.filter { it.lean }
        else -> all.filter { it.system }
    }

    private fun rebuild() { adapter.items = listFor(tab); adapter.notifyDataSetChanged() }

    private fun load() {
        Thread {
            val seen = HashSet<String>()
            val out = ArrayList<AppItem>()
            for ((cat, lean) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
                val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val p = ri.activityInfo.packageName
                    if (p == packageName || !seen.add(p)) continue
                    val sys = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    out.add(AppItem(ri.loadLabel(pm).toString(), p, ri, lean, sys))
                }
            }
            out.sortBy { it.label.lowercase() }
            ui.post {
                val key = out.map { it.pkg } + "|" + recents()
                if (key != lastKey) { lastKey = key; all = out; rebuild(); if (entered) focusFirst() }
            }
        }.start()
    }

    private fun launch(a: AppItem) {
        try {
            val r = (listOf(a.pkg) + recents().filter { it != a.pkg }).take(8)
            prefs.edit().putString("recent", r.joinToString(",")).apply()
            startActivity(
                Intent(Intent.ACTION_MAIN).setClassName(a.pkg, a.info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (_: Exception) { }
    }

    private fun hueFor(a: AppItem, cb: (Float) -> Unit) {
        hueCache[a.pkg]?.let { cb(it); return }
        worker.execute {
            var h = 220f
            try {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val d = a.info.loadIcon(pm)
                d.setBounds(0, 0, 64, 64); d.draw(Canvas(bmp))
                val p = Palette.from(bmp).maximumColorCount(10).generate()
                val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
                if (sw != null) {
                    val hsv = FloatArray(3); Color.colorToHSV(sw.rgb, hsv)
                    if (hsv[1] > 0.2f) h = hsv[0]
                }
                bmp.recycle()
            } catch (_: Exception) { }
            ui.post { hueCache[a.pkg] = h; cb(h) }
        }
    }

    private fun applyHue(h: Float) {
        val from = cur.copyOf(); val to = pal(h)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { f ->
                for (i in cur.indices) cur[i] = ev.evaluate(f.animatedFraction, from[i], to[i]) as Int
                paintTheme()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { wave.tone = cur[2] }
            })
            start()
        }
    }

    private fun onFocused(a: AppItem) {
        focusedPkg = a.pkg
        heroTitle.text = a.label
        heroSub.text = if (recents().contains(a.pkg)) "Recently opened" else if (a.lean) "Entertainment" else if (a.system) "System" else "App"
        heroIcon.setImageDrawable(a.info.loadIcon(pm))
        heroLayer.alpha = 0f; heroLayer.animate().alpha(1f).setDuration(220).start()
        hueFor(a) { h -> if (focusedPkg == a.pkg) applyHue(h) }
    }

    private inner class AppAdapter : RecyclerView.Adapter<VH>() {
        var items: List<AppItem> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val ctx = p.context
            val radius = dp(22).toFloat()
            val bgd = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = radius }
            val card = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(dp(88), dp(88)).apply { setMargins(dp(14), 0, dp(14), 0) }
                background = bgd; isFocusable = true
            }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 16f * S; maxLines = 1; alpha = 0f
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            card.addView(img, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(17) })
            card.addView(label, FrameLayout.LayoutParams(dp(130), WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(80) })

            val ring = GradientDrawable().apply { setStroke(dp(3), 0xF2FFFFFF.toInt()); cornerRadius = radius }
            card.setOnFocusChangeListener { v, has ->
                // Grow into a rectangle showing logo + name, or shrink back to a square.
                val lp = v.layoutParams as RecyclerView.LayoutParams
                (v.tag as? ValueAnimator)?.cancel()
                v.tag = ValueAnimator.ofInt(lp.width, if (has) dp(240) else dp(88)).apply {
                    duration = 160
                    addUpdateListener { lp.width = it.animatedValue as Int; v.layoutParams = lp }
                    start()
                }
                bgd.setColor(if (has) cur[3] else 0x26FFFFFF)
                label.animate().alpha(if (has) 1f else 0f).setDuration(160).start()
                v.animate().alpha(if (has) 1f else 0.8f).translationZ(if (has) dp(18).toFloat() else 0f).setDuration(160).start()
                v.foreground = if (has) ring else null
                if (has) {
                    val r = v.parent as? RecyclerView ?: return@setOnFocusChangeListener
                    val i = r.getChildAdapterPosition(v)
                    if (i >= 0) onFocused(items[i])
                }
            }
            card.setOnClickListener { v ->
                val r = v.parent as? RecyclerView ?: return@setOnClickListener
                val i = r.getChildAdapterPosition(v)
                if (i < 0) return@setOnClickListener
                val a = items[i]
                heroPill.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                v.animate().scaleX(2.4f).scaleY(2.4f).alpha(0f).setDuration(220).withEndAction {
                    launch(a)
                    v.postDelayed({ v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f; heroPill.scaleX = 1f; heroPill.scaleY = 1f }, 500)
                }.start()
            }
            return VH(card, bgd, img, label)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val a = items[i]
            (h.card.layoutParams as RecyclerView.LayoutParams).width = dp(88)
            h.card.scaleX = 1f; h.card.scaleY = 1f; h.card.translationZ = 0f
            h.card.alpha = 0.8f; h.card.foreground = null
            h.bgd.setColor(0x26FFFFFF)
            h.label.alpha = 0f; h.label.text = a.label
            h.img.setImageDrawable(a.info.loadIcon(pm))
        }
    }
}
