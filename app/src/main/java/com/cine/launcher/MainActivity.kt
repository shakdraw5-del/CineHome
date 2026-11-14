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
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private class AppItem(val label: String, val pkg: String, val info: ResolveInfo, val lean: Boolean)
private class VH(val card: FrameLayout, val img: ImageView, val label: TextView) : RecyclerView.ViewHolder(card)

/** PS5-style silk ribbons. Drawn once into a GPU layer, then only slid sideways (cheap). */
private class WaveView(ctx: Context) : View(ctx) {
    var tone = 0xFFFFFFFF.toInt()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = (tone and 0xFFFFFF) or 0x2E000000
        for (i in 0 until 18) {
            val o = i * h * 0.012f
            path.reset()
            path.moveTo(0f, h * 0.30f + o)
            path.cubicTo(w * 0.28f, h * 0.02f + o * 1.8f, w * 0.55f, h * 0.62f - o, w, h * 0.22f + o * 0.6f)
            c.drawPath(path, p)
        }
    }
}

private class Bar(ctx: Context) : View(ctx) {
    var frac = 0f
    private var track = 0
    private var fill = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun colors(t: Int, f: Int) { track = t; fill = f; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = track; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = fill; c.drawRoundRect(0f, 0f, width * frac, height.toFloat(), r, r, p)
    }
}

/**
 * Cine Home v4: PS5 home layout (square tiles, enlarged selected tile with its name underneath,
 * silk-wave backdrop, info cards below) + Pixel / Material You tonal widgets (pastel stacked clock).
 * The hovered app's icon colour sets one hue that tints everything.
 */
class MainActivity : Activity() {
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bg: GradientDrawable
    private lateinit var wave: WaveView
    private lateinit var waveAnim: ObjectAnimator
    private lateinit var clockBg: GradientDrawable
    private lateinit var darkBg: GradientDrawable
    private lateinit var darkBg2: GradientDrawable
    private lateinit var topTime: TextView
    private lateinit var hourTv: TextView
    private lateinit var minTv: TextView
    private lateinit var dowTv: TextView
    private lateinit var dayTv: TextView
    private lateinit var monTv: TextView
    private lateinit var freeTv: TextView
    private lateinit var subTv: TextView
    private lateinit var bar: Bar
    private lateinit var rv: RecyclerView
    private val adapter = AppAdapter()

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val hueCache = HashMap<String, Float>()
    private val ev = ArgbEvaluator()
    private var focusedPkg = ""
    private var anim: ValueAnimator? = null

    private fun tone(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Palette: 0 top, 1 bottom, 2 accent, 3 dark card, 4 pastel card, 5 text on pastel. */
    private fun pal(h: Float) = intArrayOf(
        tone(h, .34f, .20f), tone(h, .24f, .06f), tone(h, .30f, .96f),
        tone(h, .38f, .26f), tone(h, .26f, .88f), tone(h, .70f, .22f)
    )
    private var cur = pal(220f)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            val loc = Locale.getDefault()
            val h = SimpleDateFormat("hh", loc).format(now)
            val m = SimpleDateFormat("mm", loc).format(now)
            topTime.text = SimpleDateFormat("h:mm a", loc).format(now)
            hourTv.text = h; minTv.text = m
            dowTv.text = SimpleDateFormat("EEEE", loc).format(now).uppercase()
            dayTv.text = SimpleDateFormat("d", loc).format(now)
            monTv.text = SimpleDateFormat("MMMM yyyy", loc).format(now)
            ui.postDelayed(this, 20_000)
        }
    }

    private fun tv(sz: Float, col: Int, face: String = "sans-serif-medium") = TextView(this).apply {
        textSize = sz; setTextColor(col); includeFontPadding = false
        typeface = Typeface.create(face, Typeface.NORMAL)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pm = packageManager
        prefs = getSharedPreferences("cine", Context.MODE_PRIVATE)
        bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cur[0], cur[1]))
        val root = FrameLayout(this).apply { background = bg; clipChildren = false }
        val sw = resources.displayMetrics.widthPixels

        // Silk-wave backdrop that drifts slowly.
        wave = WaveView(this).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null); tone = cur[2] }
        root.addView(wave, FrameLayout.LayoutParams((sw * 1.25f).toInt(), MATCH))
        waveAnim = ObjectAnimator.ofFloat(wave, "translationX", 0f, -sw * 0.2f).apply {
            duration = 18000; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator(); start()
        }

        // Top bar (PS5): name on the left, time pill on the right.
        root.addView(tv(20f, Color.WHITE).apply { text = "Cine Home"; letterSpacing = 0.05f },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = dp(64); topMargin = dp(36) })
        topTime = tv(17f, Color.WHITE).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(50).toFloat() }
        }
        root.addView(topTime, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = dp(64); topMargin = dp(28) })

        // App row.
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
            clipToPadding = false; clipChildren = false
            setPadding(dp(56), dp(34), dp(56), dp(64))
            setHasFixedSize(true); itemAnimator = null; setItemViewCacheSize(8)
        }
        root.addView(rv, FrameLayout.LayoutParams(MATCH, dp(220), Gravity.TOP).apply { topMargin = dp(84) })

        // Widget cards (Pixel / Material You style).
        fun round() = GradientDrawable().apply { cornerRadius = dp(30).toFloat() }
        clockBg = round(); darkBg = round(); darkBg2 = round()
        fun card(d: GradientDrawable, w: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = d
            setPadding(dp(22), dp(18), dp(22), dp(18)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(w, MATCH).apply { rightMargin = dp(16) }
        }
        val white70 = 0xB3FFFFFF.toInt()
        val heavy = "sans-serif-black"

        hourTv = tv(60f, cur[5], heavy).apply { setLineSpacing(0f, .85f) }
        minTv = tv(60f, cur[5], heavy).apply { setLineSpacing(0f, .85f) }
        val clockCard = card(clockBg, dp(200)).apply {
            addView(hourTv); addView(minTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -dp(6) })
        }

        dowTv = tv(13f, white70).apply { letterSpacing = 0.15f }
        dayTv = tv(60f, cur[2], heavy)
        monTv = tv(14f, white70)
        val dateCard = card(darkBg, dp(190)).apply { addView(dowTv); addView(dayTv); addView(monTv) }

        freeTv = tv(30f, Color.WHITE)
        subTv = tv(13f, white70)
        bar = Bar(this)
        val storageCard = card(darkBg2, dp(300)).apply {
            addView(tv(15f, white70).apply { text = "Storage" })
            addView(freeTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            addView(subTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            addView(bar, LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(16) })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clockCard); addView(dateCard); addView(storageCard)
        }, FrameLayout.LayoutParams(WRAP, dp(170), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(64); bottomMargin = dp(30) })

        setContentView(root)
        paintTheme()
    }

    private fun paintTheme() {
        bg.colors = intArrayOf(cur[0], cur[1])
        clockBg.setColor(cur[4]); hourTv.setTextColor(cur[5]); minTv.setTextColor(cur[5])
        darkBg.setColor(cur[3]); darkBg2.setColor(cur[3]); dayTv.setTextColor(cur[2])
        bar.colors((cur[2] and 0xFFFFFF) or 0x33000000, cur[2])
    }

    private fun updateStorage() {
        val s = StatFs(Environment.getDataDirectory().path)
        val total = s.blockCountLong * s.blockSizeLong
        val free = s.availableBlocksLong * s.blockSizeLong
        fun gb(x: Long) = String.format(Locale.US, "%.1f GB", x / 1073741824.0)
        freeTv.text = gb(free)
        subTv.text = "free of " + gb(total)
        bar.frac = if (total > 0) 1f - free.toFloat() / total else 0f
        bar.invalidate()
    }

    override fun onResume() {
        super.onResume()
        if (waveAnim.isPaused) waveAnim.resume()
        ui.post(tick); updateStorage(); load()
    }

    override fun onPause() {
        ui.removeCallbacks(tick); waveAnim.pause(); super.onPause()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        rv.scrollToPosition(0)
        rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* a home screen shouldn't close */ }

    private fun recents(): List<String> =
        (prefs.getString("recent", "") ?: "").split(",").filter { it.isNotEmpty() }

    private fun load() {
        Thread {
            val seen = HashSet<String>()
            val out = ArrayList<AppItem>()
            for ((cat, lean) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
                val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val p = ri.activityInfo.packageName
                    if (p == packageName || !seen.add(p)) continue
                    out.add(AppItem(ri.loadLabel(pm).toString(), p, ri, lean))
                }
            }
            out.sortWith(compareBy<AppItem>({ !it.lean }, { it.label.lowercase() }))
            val byPkg = out.associateBy { it.pkg }
            val rec = recents().mapNotNull { byPkg[it] }
            val ordered = rec + out.filter { a -> rec.none { it.pkg == a.pkg } }
            ui.post {
                if (ordered.map { it.pkg } != adapter.items.map { it.pkg }) {
                    adapter.items = ordered
                    adapter.notifyDataSetChanged()
                    rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
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

    /** One hue from the app's icon (small bitmap, cached). Grey/white icons fall back to blue. */
    private fun hueFor(a: AppItem, cb: (Float) -> Unit) {
        hueCache[a.pkg]?.let { cb(it); return }
        worker.execute {
            var h = 220f
            try {
                val d = a.info.loadIcon(pm)
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
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
        hueFor(a) { h -> if (focusedPkg == a.pkg) applyHue(h) }
    }

    private inner class AppAdapter : RecyclerView.Adapter<VH>() {
        var items: List<AppItem> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val ctx = p.context
            val radius = dp(22).toFloat()
            val card = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(dp(88), dp(88)).apply { setMargins(dp(16), 0, dp(16), 0) }
                background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = radius }
                isFocusable = true
            }
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 13f; maxLines = 1; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                alpha = 0f; translationY = dp(30).toFloat()
            }
            card.addView(img, MATCH, MATCH)
            card.addView(label, FrameLayout.LayoutParams(dp(220), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            val ring = GradientDrawable().apply { setStroke(dp(3), 0xF2FFFFFF.toInt()); cornerRadius = radius }
            card.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.35f else 1f
                v.animate().scaleX(s).scaleY(s).alpha(if (has) 1f else 0.8f)
                    .translationZ(if (has) dp(20).toFloat() else 0f).setDuration(160).start()
                label.animate().alpha(if (has) 1f else 0f).setDuration(160).start()
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
                if (i >= 0) launch(items[i])
            }
            return VH(card, img, label)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val a = items[i]
            h.card.scaleX = 1f; h.card.scaleY = 1f; h.card.translationZ = 0f
            h.card.alpha = 0.8f; h.card.foreground = null
            h.label.alpha = 0f; h.label.text = a.label
            h.img.setImageDrawable(a.info.loadIcon(pm))
        }
    }
}
