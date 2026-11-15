package com.cine.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Your name: shown on the welcome screen and top right. */
private const val USER_NAME = "Guest"

/** Global UI size. 0.8 = the "125% -> 100%" shrink. */
private const val S = 0.8f

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private class AppItem(val label: String, val pkg: String, val info: ResolveInfo, val lean: Boolean, val system: Boolean)
private class VH(val card: FrameLayout, val bgd: GradientDrawable, val img: ImageView) : RecyclerView.ViewHolder(card)

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
 * Cine Home v7: PS5-style home.
 * Full-screen wallpaper that follows the focused app's colour, chips on top, big raised tiles at the bottom.
 * Remote: arrows = D-pad, OK = open, Back/Home = first tile, Menu = system settings.
 * Optional extras: res/drawable-nodpi/profile.jpg (avatar), assets/cine_font.ttf (custom font).
 */
class MainActivity : Activity() {
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private lateinit var home: FrameLayout
    private lateinit var welcome: FrameLayout
    private lateinit var intro: FrameLayout
    private lateinit var dust: DustView
    private lateinit var logo: LogoView
    private lateinit var word: TextView
    private lateinit var avatar: FrameLayout
    private lateinit var greet: TextView
    private lateinit var topTime: TextView
    private lateinit var heroBox: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var rv: RecyclerView
    private lateinit var wpBase: ImageView
    private lateinit var wpTop: ImageView
    private val adapter = AppAdapter()
    private val tabViews = ArrayList<TextView>()
    private val avatarBgs = ArrayList<GradientDrawable>()

    private val wps = arrayOfNulls<Bitmap>(4)
    private val wpHues = floatArrayOf(320f, 5f, 350f, 215f)
    private var curWp = -1
    private var face: Typeface? = null
    private var all: List<AppItem> = emptyList()
    private var lastKey: List<String> = emptyList()
    private var tab = 0
    private var entered = false
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val bgWorker = Executors.newSingleThreadExecutor()
    private val hueCache = ConcurrentHashMap<String, Float>()
    private var focusedPkg = ""

    private fun tone(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))
    private fun dp(v: Int) = (v * resources.displayMetrics.density * S).toInt()

    /** Palette: 0 accent, 1 dark tonal. */
    private fun pal(h: Float) = intArrayOf(tone(h, .35f, .97f), tone(h, .38f, .26f))
    private var cur = pal(215f)

    private val tick = object : Runnable {
        override fun run() {
            topTime.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 20_000)
        }
    }

    private fun tv(sz: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = sz * S; setTextColor(col); includeFontPadding = false
        typeface = face?.let { Typeface.create(it, if (bold) Typeface.BOLD else Typeface.NORMAL) }
            ?: Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun oval() = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private fun gradient(o: GradientDrawable.Orientation, vararg c: Int) = GradientDrawable(o, c)

    private fun makeAvatar(sp: Float): FrameLayout {
        val d = oval().apply { setColor(cur[1]) }; avatarBgs.add(d)
        return FrameLayout(this).apply {
            background = d; clipToOutline = true
            val id = resources.getIdentifier("profile", "drawable", packageName)
            if (id != 0) addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(id) }, MATCH, MATCH)
            else addView(tv(sp, Color.WHITE, true).apply { text = USER_NAME.take(1).uppercase(); gravity = Gravity.CENTER }, MATCH, MATCH)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        face = try { Typeface.createFromAsset(assets, "cine_font.ttf") } catch (_: Exception) { null }
        pm = packageManager
        prefs = getSharedPreferences("cine", Context.MODE_PRIVATE)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF07090E.toInt()) }

        // Wallpaper layers + PS5-style darkening so text and tiles stay readable.
        wpBase = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        wpTop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(wpBase, MATCH, MATCH); root.addView(wpTop, MATCH, MATCH)
        val navy = 0xFF07090E.toInt()
        root.addView(View(this).apply {
            background = gradient(GradientDrawable.Orientation.LEFT_RIGHT, (navy and 0xFFFFFF) or 0xF2000000.toInt(), (navy and 0xFFFFFF) or 0xB0000000.toInt(), (navy and 0xFFFFFF) or 0x1E000000)
        }, MATCH, MATCH)
        root.addView(View(this).apply {
            background = gradient(GradientDrawable.Orientation.TOP_BOTTOM, (navy and 0xFFFFFF), (navy and 0xFFFFFF) or 0xEE000000.toInt())
        }, FrameLayout.LayoutParams(MATCH, dp(400), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = gradient(GradientDrawable.Orientation.TOP_BOTTOM, (navy and 0xFFFFFF) or 0xAA000000.toInt(), (navy and 0xFFFFFF))
        }, FrameLayout.LayoutParams(MATCH, dp(200), Gravity.TOP))

        home = FrameLayout(this).apply {
            clipChildren = false; alpha = 0f; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(home, MATCH, MATCH)
        buildHome(); buildWelcome(root); buildIntro(root)
        setContentView(root)
        loadWallpapers()
        if (b == null) runIntro() else { welcome.visibility = View.GONE; intro.visibility = View.GONE; enterHome(false) }
    }

    private fun hint(key: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tv(11f, Color.WHITE, true).apply {
            text = key; setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { setStroke(dp(2), 0xCCFFFFFF.toInt()); cornerRadius = dp(50).toFloat() }
        })
        addView(tv(12f, Color.WHITE, true).apply { text = label.uppercase(); letterSpacing = 0.1f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
    }

    private fun buildHome() {
        // Top left: clock.
        topTime = tv(22f, Color.WHITE, true)
        home.addView(topTime, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(64); topMargin = dp(36) })

        // Top right: name + avatar with status dot.
        val av = FrameLayout(this)
        av.addView(makeAvatar(16f), FrameLayout.LayoutParams(dp(44), dp(44)))
        av.addView(View(this).apply { background = oval().apply { setColor(0xFF3DDC97.toInt()) } },
            FrameLayout.LayoutParams(dp(11), dp(11), Gravity.BOTTOM or Gravity.END))
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv(17f, Color.WHITE, true).apply { text = USER_NAME })
            addView(av, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(14) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = dp(64); topMargin = dp(28) })

        // Category chips (active one outlined, like "ALL" on the PS5).
        val names = listOf("Home", "Apps", "Entertainment", "System")
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            names.forEachIndexed { i, n ->
                val t = tv(14f, Color.WHITE, true).apply {
                    text = n.uppercase(); letterSpacing = 0.1f; isFocusable = true
                    setPadding(dp(20), dp(9), dp(20), dp(9))
                    setOnFocusChangeListener { _, _ -> refreshTabs() }
                    setOnClickListener { tab = i; rebuild(); refreshTabs() }
                }
                tabViews.add(t); addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(54); topMargin = dp(100) })

        // App name + category above the tile row.
        heroTitle = tv(34f, Color.WHITE, true); heroSub = tv(14f, 0xB3FFFFFF.toInt(), true).apply { letterSpacing = 0.1f }
        heroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            addView(heroTitle); addView(heroSub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        }
        home.addView(heroBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(64); bottomMargin = dp(322) })

        // Big tile row along the lower third.
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
            clipToPadding = false; clipChildren = false
            setPadding(dp(46), dp(46), dp(46), dp(30))
            setHasFixedSize(true); itemAnimator = null; setItemViewCacheSize(8)
        }
        home.addView(rv, FrameLayout.LayoutParams(MATCH, dp(240), Gravity.BOTTOM).apply { bottomMargin = dp(70) })

        // Button hints, mapped to the Xiaomi remote.
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = { LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(28) } }
            addView(hint("MENU", "Settings"), lp()); addView(hint("BACK", "First app"), lp()); addView(hint("OK", "Open"), lp())
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(64); bottomMargin = dp(26) })
        refreshTabs()
    }

    private fun refreshTabs() {
        tabViews.forEachIndexed { i, t ->
            val on = tab == i; val f = t.isFocused
            t.background = if (on || f) GradientDrawable().apply {
                setStroke(dp(2), if (on) cur[0] else 0x88FFFFFF.toInt()); cornerRadius = dp(50).toFloat()
            } else null
            t.alpha = if (on || f) 1f else 0.7f
        }
    }

    private fun paintTheme() { for (d in avatarBgs) d.setColor(cur[1]); refreshTabs() }

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
            addView(tv(30f, Color.WHITE, true).apply { text = USER_NAME }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
            addView(greet, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(tv(13f, 0x88FFFFFF.toInt()).apply { text = "PRESS OK"; letterSpacing = 0.3f }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(26) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(welcome, MATCH, MATCH)
    }

    private fun buildIntro(root: FrameLayout) {
        intro = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        dust = DustView(this); logo = LogoView(this)
        word = tv(40f, Color.WHITE).apply {
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

    private fun loadWallpapers() {
        bgWorker.execute {
            val names = arrayOf("wp_sunset", "wp_samurai", "wp_spidey", "wp_planet")
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            for (i in names.indices) {
                val id = resources.getIdentifier(names[i], "drawable", packageName)
                if (id != 0) try { wps[i] = BitmapFactory.decodeResource(resources, id, o) } catch (_: Throwable) { }
            }
            ui.post { showWallpaper(wpFor(215f)) }
        }
    }

    private fun wpFor(h: Float): Int {
        var best = 0; var bd = 999f
        for (i in wpHues.indices) {
            var d = Math.abs(h - wpHues[i]); if (d > 180f) d = 360f - d
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun showWallpaper(i: Int) {
        val bmp = wps[i] ?: return
        if (i == curWp) return
        wpTop.animate().cancel()
        if (curWp >= 0) wpBase.setImageBitmap(wps[curWp])
        curWp = i
        wpTop.setImageBitmap(bmp); wpTop.alpha = 0f
        wpTop.animate().alpha(1f).setDuration(500).withEndAction { wpBase.setImageBitmap(bmp); wpTop.alpha = 0f }.start()
    }

    override fun onResume() { super.onResume(); ui.post(tick); load() }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (entered) focusFirst() }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (entered) focusFirst() }

    override fun onKeyDown(k: Int, e: KeyEvent?): Boolean {
        if (k == KeyEvent.KEYCODE_MENU && entered) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            return true
        }
        return super.onKeyDown(k, e)
    }

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
                if (key != lastKey) { lastKey = key; all = out; rebuild(); prefetch(); if (entered) focusFirst() }
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

    /** One hue from the app's icon. Grey/white icons fall back to blue. Runs off the main thread. */
    private fun computeHue(a: AppItem): Float {
        var h = 215f
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
        return h
    }

    private fun hueFor(a: AppItem, cb: (Float) -> Unit) {
        hueCache[a.pkg]?.let { cb(it); return }
        worker.execute { val h = computeHue(a); ui.post { hueCache[a.pkg] = h; cb(h) } }
    }

    /** Colours every tile from its own icon in the background, then repaints just the tile colours. */
    private fun prefetch() {
        val snap = adapter.items
        bgWorker.execute {
            for (a in snap) {
                if (hueCache.containsKey(a.pkg)) continue
                val h = computeHue(a)
                ui.post {
                    hueCache[a.pkg] = h
                    val i = adapter.items.indexOfFirst { it.pkg == a.pkg }
                    if (i >= 0) adapter.notifyItemChanged(i, "c")
                }
            }
        }
    }

    private fun onFocused(a: AppItem) {
        focusedPkg = a.pkg
        heroTitle.text = a.label
        heroSub.text = (if (recents().contains(a.pkg)) "RECENTLY OPENED" else if (a.lean) "ENTERTAINMENT" else if (a.system) "SYSTEM" else "APP")
        heroBox.alpha = 0f; heroBox.animate().alpha(1f).setDuration(220).start()
        hueFor(a) { h -> if (focusedPkg == a.pkg) { cur = pal(h); paintTheme(); showWallpaper(wpFor(h)) } }
    }

    private inner class AppAdapter : RecyclerView.Adapter<VH>() {
        var items: List<AppItem> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val ctx = p.context
            val radius = dp(18).toFloat()
            val bgd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A3350.toInt(), 0xFF10141F.toInt()))
                .apply { cornerRadius = radius }
            val card = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(dp(150), dp(150)).apply { setMargins(dp(18), 0, dp(18), 0) }
                background = bgd; isFocusable = true
            }
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            card.addView(img, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))

            val ring = GradientDrawable().apply { setStroke(dp(3), 0xF2FFFFFF.toInt()); cornerRadius = radius }
            card.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.18f else 1f
                v.animate().scaleX(s).scaleY(s).translationY(if (has) -dp(16).toFloat() else 0f)
                    .alpha(if (has) 1f else 0.85f).translationZ(if (has) dp(20).toFloat() else 0f).setDuration(160).start()
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
                v.animate().scaleX(2.4f).scaleY(2.4f).alpha(0f).setDuration(220).withEndAction {
                    launch(a)
                    v.postDelayed({ v.scaleX = 1.18f; v.scaleY = 1.18f; v.alpha = 1f }, 500)
                }.start()
            }
            return VH(card, bgd, img)
        }

        private fun paint(h: VH, a: AppItem) {
            val hh = hueCache[a.pkg] ?: 215f
            h.bgd.colors = intArrayOf(tone(hh, .40f, .42f), tone(hh, .55f, .15f))
        }

        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) super.onBindViewHolder(h, i, payloads) else paint(h, items[i])
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val a = items[i]
            h.card.scaleX = 1f; h.card.scaleY = 1f; h.card.translationY = 0f; h.card.translationZ = 0f
            h.card.alpha = 0.85f; h.card.foreground = null
            paint(h, a)
            h.img.setImageDrawable(a.info.loadIcon(pm))
        }
    }
}
