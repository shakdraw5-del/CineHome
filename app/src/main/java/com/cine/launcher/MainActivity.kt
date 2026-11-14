package com.cine.launcher

import android.animation.ArgbEvaluator
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/**
 * Cine Home v3
 * - Pixel-style stacked clock (top left)
 * - Material You tonal theme: hovered app's icon colour sets the hue of everything
 * - PS5-style row: dimmed tiles, bright focused tile with a white ring, ambient glow
 * - Google TV-style "recent apps" row above the main row
 * Plain Views only, no blur, tiny bitmaps: light enough for a Xiaomi TV 5A.
 */
class MainActivity : Activity() {
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bg: GradientDrawable
    private lateinit var glow: GradientDrawable
    private lateinit var pillBg: GradientDrawable
    private lateinit var title: TextView
    private lateinit var pill: TextView
    private lateinit var hourTv: TextView
    private lateinit var minTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var rvMain: RecyclerView
    private lateinit var rvRecent: RecyclerView
    private lateinit var mainAdapter: AppAdapter
    private lateinit var recentAdapter: AppAdapter

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val hueCache = HashMap<String, Float>()
    private val ev = ArgbEvaluator()
    private var focusedPkg = ""
    private var anim: ValueAnimator? = null

    // Current tonal palette (animated). Default is a cool PS5-like blue.
    private var curTop = tone(220f, .34f, .20f)
    private var curBot = tone(220f, .24f, .06f)
    private var curAcc = tone(220f, .30f, .96f)

    private fun tone(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pillColor() = (curAcc and 0xFFFFFF) or 0x30000000
    private fun glowColor() = (curAcc and 0xFFFFFF) or 0x3A000000

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            hourTv.text = SimpleDateFormat("hh", Locale.getDefault()).format(now)
            minTv.text = SimpleDateFormat("mm", Locale.getDefault()).format(now)
            dateTv.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now)
            ui.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pm = packageManager
        prefs = getSharedPreferences("cine", Context.MODE_PRIVATE)
        bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(curTop, curBot))
        val root = FrameLayout(this).apply { background = bg; clipChildren = false }

        // Ambient glow (PS5-style light bloom), tinted with the accent.
        glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(520).toFloat()
            setGradientCenter(0.82f, 0.12f)
            colors = intArrayOf(glowColor(), 0x00000000)
        }
        root.addView(View(this).apply { background = glow }, MATCH, MATCH)

        // Pixel-style stacked clock.
        val big = Typeface.create("sans-serif-black", Typeface.NORMAL)
        dateTv = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        hourTv = TextView(this).apply {
            setTextColor(curAcc); textSize = 72f; typeface = big
            includeFontPadding = false; setLineSpacing(0f, 0.8f)
        }
        minTv = TextView(this).apply {
            setTextColor(0xEBFFFFFF.toInt()); textSize = 72f; typeface = big
            includeFontPadding = false; setLineSpacing(0f, 0.8f)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dateTv)
            addView(hourTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(minTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -dp(12) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(64); topMargin = dp(36)
        })

        // Focused app title with tonal "Open" pill beside it.
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 34f; maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        pillBg = GradientDrawable().apply { cornerRadius = dp(50).toFloat(); setColor(pillColor()) }
        pill = TextView(this).apply {
            text = "Open"; textSize = 14f; setTextColor(curAcc)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(20), dp(6), dp(20), dp(6)); background = pillBg
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(18) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(64); bottomMargin = dp(318)
        })

        // Rows: recent (small) on top, all apps (large) below.
        recentAdapter = AppAdapter(dp(150), dp(84), dp(10), dp(6))
        mainAdapter = AppAdapter(dp(220), dp(124), dp(14), dp(10))
        rvRecent = makeRv(recentAdapter, 12).apply { visibility = View.GONE }
        rvMain = makeRv(mainAdapter, 12)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(rvRecent, LinearLayout.LayoutParams(MATCH, dp(120)))
            addView(rvMain, LinearLayout.LayoutParams(MATCH, dp(170)))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(20) })
        setContentView(root)
    }

    private fun makeRv(a: AppAdapter, padV: Int) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
        adapter = a
        clipToPadding = false; clipChildren = false
        setPadding(dp(50), dp(padV), dp(50), dp(padV))
        setHasFixedSize(true); itemAnimator = null; setItemViewCacheSize(8)
    }

    override fun onResume() { super.onResume(); ui.post(tick); load() }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        rvMain.scrollToPosition(0)
        rvMain.post { rvMain.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
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
            ui.post {
                val byPkg = out.associateBy { it.pkg }
                val rec = recents().mapNotNull { byPkg[it] }
                recentAdapter.items = rec
                recentAdapter.notifyDataSetChanged()
                rvRecent.visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
                if (out.map { it.pkg } != mainAdapter.items.map { it.pkg } || rec.isNotEmpty()) {
                    mainAdapter.items = out
                    mainAdapter.notifyDataSetChanged()
                    rvMain.post { rvMain.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
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

    /** One hue from the app's icon (96x54 bitmap, cached). Grey/white icons fall back to blue. */
    private fun hueFor(a: AppItem, cb: (Float) -> Unit) {
        hueCache[a.pkg]?.let { cb(it); return }
        worker.execute {
            var h = 220f
            try {
                val d = a.info.activityInfo.loadBanner(pm) ?: a.info.loadIcon(pm)
                val bmp = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 54); d.draw(Canvas(bmp))
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
        val top = tone(h, .34f, .20f); val bot = tone(h, .24f, .06f); val acc = tone(h, .30f, .96f)
        val t0 = curTop; val b0 = curBot; val a0 = curAcc
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            addUpdateListener {
                val f = it.animatedFraction
                curTop = ev.evaluate(f, t0, top) as Int
                curBot = ev.evaluate(f, b0, bot) as Int
                curAcc = ev.evaluate(f, a0, acc) as Int
                bg.colors = intArrayOf(curTop, curBot)
                glow.colors = intArrayOf(glowColor(), 0x00000000)
                hourTv.setTextColor(curAcc)
                pill.setTextColor(curAcc)
                pillBg.setColor(pillColor())
            }
            start()
        }
    }

    private fun onFocused(a: AppItem) {
        focusedPkg = a.pkg
        title.text = a.label
        title.animate().cancel()
        title.alpha = 0f; title.translationX = -dp(14).toFloat()
        title.animate().alpha(1f).translationX(0f).setDuration(220).start()
        hueFor(a) { h -> if (focusedPkg == a.pkg) applyHue(h) }
    }

    private inner class AppAdapter(val w: Int, val h: Int, val mh: Int, val mv: Int) : RecyclerView.Adapter<VH>() {
        var items: List<AppItem> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val ctx = p.context
            val radius = dp(22).toFloat()
            val card = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(w, h).apply { setMargins(mh, mv, mh, mv) }
                background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = radius }
                clipToOutline = true
                isFocusable = true
            }
            val img = ImageView(ctx)
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 12f; maxLines = 1; gravity = Gravity.CENTER
            }
            card.addView(img, MATCH, MATCH)
            card.addView(label, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(8) })

            val ring = GradientDrawable().apply { setStroke(dp(3), 0xF2FFFFFF.toInt()); cornerRadius = radius }
            card.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.12f else 1f
                v.animate().scaleX(s).scaleY(s).alpha(if (has) 1f else 0.72f)
                    .translationZ(if (has) dp(20).toFloat() else 0f).setDuration(150).start()
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
            h.card.alpha = 0.72f; h.card.foreground = null
            val banner = a.info.activityInfo.loadBanner(pm)
            if (banner != null) {
                h.img.setPadding(0, 0, 0, 0)
                h.img.scaleType = ImageView.ScaleType.CENTER_CROP
                h.img.setImageDrawable(banner)
                h.label.visibility = View.GONE
            } else {
                h.img.setPadding(dp(30), dp(14), dp(30), dp(30))
                h.img.scaleType = ImageView.ScaleType.FIT_CENTER
                h.img.setImageDrawable(a.info.loadIcon(pm))
                h.label.text = a.label
                h.label.visibility = View.VISIBLE
            }
        }
    }
}
