package dev.zest.island

import android.content.Context
import android.media.MediaMetadata
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pegas bentuk tertutup (analitik, stabil di frame berapa pun).
 * response = periode osilasi dalam detik, damping = rasio redaman.
 * Gaya yang sama dengan island referensi (response ~0.3-0.45, damping ~0.6-0.9).
 */
private class Spr(v: Float, var response: Float, var damping: Float, private val eps: Float = 0.4f) {
    var value = v
    var target = v
    var vel = 0f
    var rest = true

    fun to(t: Float, r: Float = response, d: Float = damping) {
        response = r
        damping = d
        if (t != target || !rest) {
            target = t
            rest = false
        }
    }

    fun snap(v: Float) {
        value = v; target = v; vel = 0f; rest = true
    }

    /** true bila masih bergerak. */
    fun step(dtIn: Float): Boolean {
        if (rest) return false
        val dt = dtIn.coerceIn(0f, 0.064f).toDouble()
        val w = 2.0 * PI / max(0.016f, response).toDouble()
        val z = max(0.05f, damping).toDouble()
        val x0 = (value - target).toDouble()
        val v0 = vel.toDouble()
        val x: Double
        val v: Double
        if (z < 1.0) {
            val wd = w * sqrt(1.0 - z * z)
            val e = exp(-z * w * dt)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            val b = (z * w * x0 + v0) / wd
            x = e * (x0 * c + b * s)
            v = -z * w * x + e * (-wd * x0 * s + wd * b * c)
        } else {
            val b = w * x0 + v0
            val e = exp(-w * dt)
            x = (x0 + b * dt) * e
            v = (b - w * (x0 + b * dt)) * e
        }
        value = (target + x).toFloat()
        vel = v.toFloat()
        if (abs(x) < eps && abs(v) < eps * 60f) {
            value = target; vel = 0f; rest = true
            return false
        }
        return true
    }
}

private class Block(val key: String, var item: IslandItem) {
    val a = Spr(0f, 0.32f, 0.9f, 0.002f)
    val y = Spr(0f, 0.45f, 0.75f, 0.4f)
    var live = true
    var fresh = true
    var h = 0f
    var title = ""
    var sub = ""
    var artBmp: Bitmap? = null
    var shader: BitmapShader? = null
}

/**
 * Seluruh island digambar di satu View (Canvas) pada jendela berukuran tetap.
 * Tidak ada child view / layout per frame -> animasi pegas mulus.
 */
class IslandView(context: Context) : View(context) {
    var onToggle: (() -> Unit)? = null
    var onOutside: (() -> Unit)? = null
    /** Dipanggil saat semua pegas diam (dipakai service untuk merapikan area sentuh). */
    var onSettled: (() -> Unit)? = null
    /** Pill digeser ke kiri/kanan untuk dibuang: kunci item yang dibuang. */
    var onDismiss: ((List<String>) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Float = v * density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // Cutout A23: sekitar 61x23dp di tengah atas.
    private var collapsedW = dp(112)
    private var collapsedH = dp(28)
    private var satD = collapsedH
    private val satGap = dp(4)
    private val margin = dp(16)
    private val expandedW = min(resources.displayMetrics.widthPixels - dp(24), dp(380))
    private val pad = dp(12)
    private val blockGap = dp(8)
    private val blockW get() = expandedW - 2 * pad
    private val cornerExpanded = dp(40)

    // Pegas (gaya referensi)
    private val sw = Spr(collapsedW * 0.6f, 0.36f, 0.74f, 0.4f)
    private val sh = Spr(collapsedH * 0.6f, 0.38f, 0.70f, 0.4f)
    private val sr = Spr(collapsedH / 2f, 0.35f, 0.9f, 0.4f)
    private val sa = Spr(0f, 0.2f, 1f, 0.002f)        // isi kartu
    private val vis = Spr(0f, 0.22f, 1f, 0.002f)      // muncul
    private val split = Spr(0f, 0.46f, 0.62f, 0.002f) // kapsul kedua
    private val press = Spr(1f, 0.2f, 0.9f, 0.0005f)
    private val dragX = Spr(0f, 0.3f, 0.85f, 0.3f)    // geser untuk membuang pill
    private val springs = listOf(sw, sh, sr, sa, vis, split, press, dragX)
    private var downX = 0f
    private var swiping = false

    private var items: List<IslandItem> = emptyList()
    private var expanded = false
    private var primary: IslandItem? = null
    private var satItem: IslandItem? = null
    private val blocks = ArrayList<Block>()
    private var cardH = collapsedH

    // art kecil di pil
    private var pArtBmp: Bitmap? = null
    private var pArtShader: BitmapShader? = null

    // cat
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    // Liquid glass: kaca gelap tembus pandang + kilap atas + tepi bercahaya
    private val blockBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glossShader = LinearGradient(
        0f, 0f, 0f, 1f, intArrayOf(0x55FFFFFF, 0x18FFFFFF, 0x06FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
    )
    private val rimShader = LinearGradient(
        0f, 0f, 1f, 1f, intArrayOf(0xB0FFFFFF.toInt(), 0x18FFFFFF, 0x60FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glossShader }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; shader = rimShader }
    private val blockRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val glMat = Matrix()
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B8.toInt(); textSize = sp(13f)
    }
    private val tBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(19f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val tPill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum"
    }
    private val tNotice = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tBtn = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = sp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val tSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0A8.toInt(); textSize = sp(11f); fontFeatureSettings = "tnum"
    }

    private val path = Path()
    private val rect = RectF()
    private val clipR = RectF()
    private val tmp = Matrix()

    // loop animasi
    private var running = false
    private var lastNs = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nowNs: Long) {
            val dt = if (lastNs == 0L) 1f / 60f else (nowNs - lastNs) / 1e9f
            lastNs = nowNs
            var moving = false
            for (s in springs) if (s.step(dt)) moving = true
            for (b in blocks) {
                if (b.a.step(dt)) moving = true
                if (b.y.step(dt)) moving = true
            }
            blocks.removeAll { !it.live && it.a.rest && it.a.value < 0.01f }
            invalidateIsland()
            if (moving || mediaPlaying()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastNs = 0L
                dirtyW = 0f; dirtyH = 0f
                invalidate()   // bersihkan sisa area gambar
                onSettled?.invoke()
            }
        }
    }

    private val dirty = Rect()

    private var dirtyW = 0f
    private var dirtyH = 0f

    /** Hanya gambar ulang area island (bukan seluruh jendela besar) -> jauh lebih ringan di GPU lemah. */
    private fun invalidateIsland() {
        dirtyW = max(dirtyW * 0.97f, max(sw.value, collapsedW) + 2 * (satD + satGap))
        dirtyH = max(dirtyH * 0.97f, max(sh.value, collapsedH))
        val cx = width / 2f
        val m = dp(24)
        if (offX != 0f || dragX.value != 0f) {
            dirty.set(0, max(0f, offY - m).toInt(), width, (offY + dirtyH * 1.12f + m).toInt().coerceAtMost(height))
        } else dirty.set(
            (cx - dirtyW / 2f - m).toInt().coerceAtLeast(0),
            max(0f, offY - m).toInt(),
            (cx + dirtyW / 2f + m).toInt().coerceAtMost(width),
            (offY + dirtyH * 1.12f + m).toInt().coerceAtMost(height)
        )
        invalidate(dirty)
    }

    private fun kick() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun mediaPlaying() = items.any { (it.kind == Kind.MEDIA && it.playing) || it.kind == Kind.NOTICE }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Muncul dengan pegas setiap kali jendela dipasang.
        sw.snap(collapsedW * 0.6f)
        sh.snap(collapsedH * 0.6f)
        sr.snap(collapsedH / 2f)
        sa.snap(0f)
        vis.snap(0f)
        split.snap(0f)
        press.snap(1f)
        retarget()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        lastNs = 0L
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ API

    private var offX = 0f
    private var offY = 0f

    /** Geser X efektif: pill menutup mengikuti pengaturan, kartu terbuka selalu di tengah layar. */
    private fun curOffX(): Float = offX * (1f - sa.value.coerceIn(0f, 1f))

    /** Geser posisi pill (px) dari UN1CA Settings. */
    fun setOffset(x: Float, y: Float) {
        if (x == offX && y == offY) return
        offX = x
        offY = y
        invalidate()
    }

    /** Tinggi jendela tetap yang cukup untuk kartu terbesar (3 notifikasi + musik). */
    fun fixedWindowHeight(): Int = (dp(500) + max(0f, offY)).toInt()

    /** Ukuran jendela gambar yang TIDAK pernah berubah (mode Aksesibilitas). */
    fun fixedWindowSize(): Pair<Int, Int> = Pair((expandedW + 2 * margin).toInt(), dp(500).toInt())

    /** Area sentuh saat ini (koordinat jendela) untuk touchable region. */
    fun touchBounds(out: Rect) {
        if (hidden || items.isEmpty()) { out.setEmpty(); return }
        val cx = cx() + (if (expanded) 0f else offX)
        val slop = dp(8)
        val hw: Float
        val bottom: Float
        var extraRight = 0f
        val tw = max(sw.value, sw.target)
        val th = max(sh.value, sh.target)
        if (expanded || sa.value > 0.02f) {
            hw = max(tw, expandedW) / 2f
            bottom = max(th, cardH)
        } else {
            hw = max(tw, collapsedW) / 2f
            bottom = max(th, collapsedH)
            if (satItem != null || split.value > 0.02f) extraRight = satGap + satD
        }
        out.set(
            (cx - hw - slop).toInt(),
            (offY).toInt().coerceAtLeast(0),
            (cx + hw + extraRight + slop).toInt(),
            (offY + bottom * 1.06f + slop).toInt()
        )
    }

    /** Ukuran pill (dp) dari UN1CA Settings; 0 = bawaan. */
    fun setPillSize(wDp: Int, hDp: Int) {
        val w = if (wDp <= 0) 112 else wDp.coerceIn(40, 300)
        val h = if (hDp <= 0) 28 else hDp.coerceIn(16, 80)
        val nw = dp(w)
        val nh = dp(h)
        if (nw == collapsedW && nh == collapsedH) return
        collapsedW = nw
        collapsedH = nh
        satD = nh
        pArtBmp = null
        pickPrimary()
        syncBlocks()
        retarget()
    }

    private var hidden = false

    /** Menyusut halus (QS panel / layar kunci) atau kembali muncul dengan pegas. */
    fun setHidden(h: Boolean) {
        if (hidden == h) return
        hidden = h
        retarget()
    }

    fun isHidden() = hidden

    /** Dipakai sebelum jendela dipasang ulang: mulai dari keadaan tampil. */
    fun resetHidden() { hidden = false }

    fun bind(newItems: List<IslandItem>, isExpanded: Boolean) {
        val entering = items.isEmpty() && newItems.isNotEmpty() && !isExpanded && !hidden
        items = newItems
        expanded = isExpanded
        pickPrimary()
        syncBlocks()
        if (entering) {
            // Seperti "masuk ke island": titik kecil di kamera, lalu mengembang memantul jadi pil.
            sw.snap(idleD())
            sh.snap(idleD())
            sr.snap(idleD() / 2f)
            sa.snap(0f)
            vis.snap(1f)
            retarget()
            sw.to(collapsedTargetW(), 0.46f, 0.56f)
            sh.to(collapsedH, 0.40f, 0.58f)
            return
        }
        retarget()
    }

    /**
     * Ukuran jendela saat kartu terbuka: selalu cukup untuk media + 3 notifikasi, jadi jendela
     * tidak perlu diubah ukurannya (dan tidak berkedip) ketika blok bertambah.
     */
    fun expandedSize(): Pair<Int, Int> {
        // Kartu sekarang + ruang untuk satu notifikasi lagi (tanpa ubah ukuran jendela).
        val need = max(cardH, collapsedH) + dp(64) + blockGap
        return Pair((expandedW + 2 * margin).toInt(), (need * 1.06f + margin).toInt())
    }

    /** True bila semua pegas diam (dipakai service untuk menyusutkan jendela). */
    fun isSettled(): Boolean =
        springs.all { it.rest } && blocks.all { it.a.rest && it.y.rest }

    /** Ukuran jendela saat pill menyusut (cukup untuk pill + kapsul kedua + pegas). */
    fun collapsedWindowSize(): Pair<Int, Int> =
        Pair((max(collapsedW, dp(210)) + 2 * (satD + satGap) + 2 * margin).toInt(), (collapsedH * 1.1f + margin).toInt())

    /** Dipanggil ~20x/detik dari service: timer & progress. */
    fun tick() {
        invalidateIsland()
    }

    // ------------------------------------------------------------ pemilihan

    private fun pickPrimary() {
        val notice = items.firstOrNull { it.kind == Kind.NOTICE }
        val list = items.filter { it.kind != Kind.NOTICE }
        val first = notice ?: list.firstOrNull()
        if (first == null) {
            primary = null; satItem = null
            return
        }
        val media = list.firstOrNull { it.kind == Kind.MEDIA }
        val timerLike = list.firstOrNull { it.kind == Kind.TIMER || it.kind == Kind.CALL }
        if (false && notice == null && first.kind != Kind.ALERT && media != null && timerLike != null) {
            primary = media
            satItem = null
        } else {
            primary = first
            satItem = null
        }
        val p = primary
        val art = p?.art
        if (art != null) {
            if (art !== pArtBmp) {
                pArtBmp = art
                pArtShader = makeShader(art, artSize())
            }
        } else {
            pArtBmp = null; pArtShader = null
        }
    }

    private fun artSize() = min(collapsedH * 0.8f, dp(24))

    private fun makeShader(bmp: Bitmap, size: Float): BitmapShader {
        val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = size / min(bmp.width, bmp.height).toFloat()
        val m = Matrix()
        m.setScale(sc, sc)
        m.postTranslate((size - bmp.width * sc) / 2f, (size - bmp.height * sc) / 2f)
        sh.setLocalMatrix(m)
        return sh
    }

    private fun hasActions(i: IslandItem) =
        (i.kind == Kind.TIMER || i.kind == Kind.CALL) && i.actions.isNotEmpty()

    private fun blockHeight(i: IslandItem): Float = when {
        i.kind == Kind.MEDIA -> dp(164)
        i.kind == Kind.CALL && hasActions(i) -> dp(116)
        hasActions(i) -> dp(104)
        else -> dp(64)
    }

    private fun syncBlocks() {
        val list = items.filter { it.kind != Kind.NOTICE }
        val keys = list.map { it.key }.toHashSet()
        val byKey = HashMap<String, Block>()
        for (b in blocks) byKey[b.key] = b
        val live = ArrayList<Block>()
        for (it in list) {
            var b = byKey[it.key]
            if (b == null) {
                b = Block(it.key, it)
                blocks.add(b)
            }
            b.item = it
            b.live = true
            prepare(b)
            live.add(b)
        }
        for (b in blocks) if (b.key !in keys) {
            b.live = false
            b.a.to(0f, 0.2f, 1f)
        }
        var cursor = pad
        for (b in live) {
            b.h = blockHeight(b.item)
            if (b.fresh) {
                b.fresh = false
                b.y.snap(cursor - dp(14))
                b.a.snap(0f)
            }
            b.y.to(cursor, 0.45f, 0.75f)
            b.a.to(1f, 0.34f, 1f)
            cursor += b.h + blockGap
        }
        cardH = if (live.isEmpty()) collapsedH else cursor - blockGap + pad
    }

    private val timeLike = Regex("[0-9:.\\s]*")

    private fun prepare(b: Block) {
        val i = b.item
        val media = i.kind == Kind.MEDIA
        val left = if (media) dp(74) else dp(60)
        val right = when {
            media -> dp(44)
            i.hasTimer -> dp(96)
            else -> dp(14)
        }
        val maxW = max(dp(40), blockW - left - right)
        b.title = TextUtils.ellipsize(i.title, tTitle, maxW, TextUtils.TruncateAt.END).toString()
        val subSrc = if (i.hasTimer && timeLike.matches(i.subtitle)) "" else i.subtitle
        b.sub = TextUtils.ellipsize(subSrc, tSub, maxW, TextUtils.TruncateAt.END).toString()
        val art = i.art
        if (art != null) {
            if (art !== b.artBmp) {
                b.artBmp = art
                b.shader = makeShader(art, dp(58))
            }
        } else {
            b.artBmp = null; b.shader = null
        }
        i.icon?.let { d ->
            try { d.mutate().setTint(0xFFFFFFFF.toInt()) } catch (_: Throwable) {}
        }
    }

    private fun idleD() = collapsedH * 0.7f

    private fun collapsedTargetW(): Float {
        val p = primary
        if (p == null) return idleD()   // bulat kecil hanya saat idle
        if (p.kind == Kind.TIMER && !p.hasTimer && p.icon2 != null) return collapsedW + dp(40)   // [logo] 1-0 [logo]
        if (p == null || p.kind != Kind.NOTICE || p.title.isEmpty()) return collapsedW
        val gs = collapsedH * 0.56f
        val half = max(dp(11) + gs + dp(6), dp(12) + tNotice.measureText(p.title) + dp(6))
        return min(max(collapsedW, 2 * half + dp(30)), dp(230))
    }

    private fun retarget() {
        if (hidden) {
            sw.to(collapsedW * 0.42f, 0.34f, 0.9f)
            sh.to(collapsedH * 0.42f, 0.34f, 0.9f)
            sr.to(collapsedH * 0.21f, 0.34f, 0.9f)
            sa.to(0f, 0.14f, 1f)
            split.to(0f, 0.3f, 0.9f)
            vis.to(0f, 0.26f, 1f)
            kick()
            return
        }
        if (expanded) {
            // damping rendah = membal/kenyal seperti jeli (melebar ke bawah lalu mengendap)
            sw.to(expandedW, 0.40f, 0.60f)
            sh.to(max(cardH, collapsedH), 0.46f, 0.52f)
            sr.to(cornerExpanded, 0.35f, 0.9f)
            sa.to(1f, 0.30f, 1f)
        } else {
            sw.to(collapsedTargetW(), 0.42f, 0.86f)
            val hh = if (primary == null) idleD() else collapsedH
            sh.to(hh, 0.38f, 0.88f)
            sr.to(hh / 2f, 0.36f, 1f)
            sa.to(0f, 0.12f, 1f)
        }
        split.to(if (satItem != null && !expanded) 1f else 0f, 0.46f, 0.62f)
        vis.to(1f, 0.22f, 1f)
        kick()
    }

    // --------------------------------------------------------------- sentuh

    private enum class Hit { NONE, PILL, PREV, PLAY, NEXT, STAR, OUTPUT, MUTE, OPEN, ACTION, SEEK }

    private var seekBlock: Block? = null
    private var seekFrac = 0f

    /** Area bilah progress musik (diperlebar vertikal supaya mudah digeser). */
    private fun seekRect(b: Block, out: RectF): RectF {
        val r = blockRect(b, ctlTmp)
        out.set(r.left, r.top + dp(76), r.right, r.top + dp(108))
        return out
    }

    private fun fracAt(b: Block, x: Float): Float {
        val r = blockRect(b, ctlTmp)
        val p0 = r.left + dp(42)
        val p1 = r.right - dp(46)
        return ((x - curOffX() - p0) / (p1 - p0)).coerceIn(0f, 1f)
    }

    // Tahan lama = buka lebar; ketuk sekali = buka aplikasinya (seperti iPhone).
    private var longFired = false
    private val longRun = Runnable {
        if (downHit == Hit.PILL && !swiping && !expanded) {
            longFired = true
            press.to(1f, 0.35f, 0.6f)
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            onToggle?.invoke()
        }
    }

    private var downHit = Hit.NONE
    private var downBlock: Block? = null
    private var downIdx = 0
    private var hitIdx = 0

    private fun cx() = (if (width > 0) width else (expandedW + 2 * margin).toInt()) / 2f

    private fun blockRect(b: Block, out: RectF): RectF {
        val l = cx() - blockW / 2f
        out.set(l, b.y.value, l + blockW, b.y.value + b.h)
        return out
    }

    private val ctlRects = arrayOf(RectF(), RectF(), RectF(), RectF(), RectF())
    private val ctlTmp = RectF()

    /** Mengisi (dan mengembalikan) array tetap -> tanpa alokasi per frame. */
    private fun controlRects(b: Block): Array<RectF> {
        val r = blockRect(b, ctlTmp)
        val cy = r.top + dp(134)
        val c = r.centerX()
        val half = dp(26)
        ctlRects[0].set(c - dp(64) - half, cy - half, c - dp(64) + half, cy + half)
        ctlRects[1].set(c - half, cy - half, c + half, cy + half)
        ctlRects[2].set(c + dp(64) - half, cy - half, c + dp(64) + half, cy + half)
        val hs = dp(22)
        ctlRects[3].set(r.left + dp(4), cy - hs, r.left + dp(4) + 2 * hs, cy + hs)
        ctlRects[4].set(r.right - dp(4) - 2 * hs, cy - hs, r.right - dp(4), cy + hs)
        return ctlRects
    }

    /** Persegi tombol aksi ke-[idx] dari [n] pada kartu timer/telepon. */
    private fun actionRect(b: Block, idx: Int, n: Int, out: RectF): RectF {
        val r = blockRect(b, ctlTmp)
        val gap = dp(8)
        val left = r.left + dp(10)
        val total = r.width() - dp(20)
        val w = (total - gap * (n - 1)) / n
        val x = left + idx * (w + gap)
        if (b.item.kind == Kind.CALL) out.set(x, r.top + dp(60), x + w, r.top + dp(102))
        else out.set(x, r.top + dp(64), x + w, r.top + dp(96))
        return out
    }

    private val hitTmp = RectF()

    private val muteWords = arrayOf("mute", "bisu", "senyap", "unmute")
    /** Panggilan tersambung tanpa tombol bisu dari aplikasinya -> tambahkan tombol bisu sendiri. */
    private fun needsMute(i: IslandItem) =
        i.kind == Kind.CALL && i.hasTimer && i.actions.none { a -> muteWords.any { a.title.lowercase().contains(it) } }

    private fun micMuted(): Boolean = try {
        (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMicrophoneMute
    } catch (_: Throwable) { false }

    private fun toggleMute() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isMicrophoneMute = !am.isMicrophoneMute
        } catch (_: Throwable) {}
        kick()
    }

    private fun hit(rx: Float, ry: Float): Pair<Hit, Block?> {
        val x = rx - curOffX()
        val y = ry - offY
        val cx = cx()
        hitIdx = 0
        if (!expanded) {
            val slop = dp(6)
            val hw = max(collapsedW, sw.value) / 2f
            if (x >= cx - hw - slop && x <= cx + hw + slop && y <= collapsedH + slop) {
                return Hit.PILL to null
            }
            if (satItem != null && x >= cx + collapsedW / 2f && x <= cx + collapsedW / 2f + satGap + satD + slop &&
                y <= collapsedH + slop
            ) return Hit.PILL to null
            return Hit.NONE to null
        }
        val tmpR = hitTmp
        for (b in blocks) {
            if (!b.live) continue
            val it = b.item
            if (it.kind == Kind.MEDIA) {
                if (it.durationMs > 0 && seekRect(b, tmpR).contains(x, y)) return Hit.SEEK to b
                val cr = controlRects(b)
                if (cr[0].contains(x, y)) return Hit.PREV to b
                if (cr[1].contains(x, y)) return Hit.PLAY to b
                if (cr[2].contains(x, y)) return Hit.NEXT to b
                if (cr[3].contains(x, y)) return Hit.STAR to b
                if (cr[4].contains(x, y)) return Hit.OUTPUT to b
            } else if (hasActions(it)) {
                val off = if (needsMute(it)) 1 else 0
                val n = it.actions.size + off
                if (off == 1 && actionRect(b, 0, n, tmpR).contains(x, y)) return Hit.MUTE to b
                for (k in it.actions.indices) {
                    if (actionRect(b, k + off, n, tmpR).contains(x, y)) {
                        hitIdx = k
                        return Hit.ACTION to b
                    }
                }
            }
            if (blockRect(b, tmpR).contains(x, y)) return Hit.OPEN to b
        }
        return Hit.NONE to null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (expanded) onOutside?.invoke()
            return false
        }
        return touchAt(e.actionMasked, e.x, e.y)
    }

    /** Logika sentuh bersama (dipanggil juga dari jendela sentuh terpisah di mode Aksesibilitas). */
    fun touchAt(action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                swiping = false
                longFired = false
                removeCallbacks(longRun)
                val (h, b) = hit(x, y)
                downHit = h
                downBlock = b
                downIdx = hitIdx
                if (h == Hit.NONE) return false
                if (h == Hit.PILL) {
                    press.to(0.94f, 0.2f, 0.9f)
                    kick()
                    if (!expanded) postDelayed(longRun, 380)
                }
                if (h == Hit.SEEK && b != null) {
                    seekBlock = b
                    seekFrac = fracAt(b, x)
                    kick()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downHit == Hit.PILL && !expanded) {
                    val dx = x - downX
                    if (!swiping && abs(dx) > dp(10)) { swiping = true; removeCallbacks(longRun) }
                    if (swiping) {
                        dragX.snap(dx * 0.8f)
                        invalidateIsland()
                        return true
                    }
                }
                val sb = seekBlock
                if (sb != null) {
                    seekFrac = fracAt(sb, x)
                    kick()
                    return true
                }
                return downHit != Hit.NONE
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                val was = downHit
                val wasBlock = downBlock
                val wasIdx = downIdx
                downHit = Hit.NONE
                downBlock = null
                if (was == Hit.PILL) {
                    press.to(1f, 0.35f, 0.6f)
                    kick()
                }
                val sb = seekBlock
                if (sb != null) {
                    seekBlock = null
                    val it = sb.item
                    if (action == MotionEvent.ACTION_UP && it.durationMs > 0) {
                        val seekTo = (fracAt(sb, x) * it.durationMs).toLong()
                        try { it.controller?.transportControls?.seekTo(seekTo) } catch (_: Throwable) {}
                        it.positionMs = seekTo
                        it.posTime = SystemClock.elapsedRealtime()
                    }
                    kick()
                    return true
                }
                if (swiping && was == Hit.PILL) {
                    swiping = false
                    val dx = x - downX
                    if (abs(dx) > dp(36)) {
                        val keys = ArrayList<String>(2)
                        primary?.let { keys.add(it.key) }
                        satItem?.let { keys.add(it.key) }
                        dragX.to(if (dx > 0) dp(170) else -dp(170), 0.22f, 1f)
                        kick()
                        postDelayed({
                            onDismiss?.invoke(keys)
                            dragX.snap(0f)
                            invalidateIsland()
                        }, 200)
                    } else {
                        dragX.to(0f, 0.3f, 0.8f)
                        kick()
                    }
                    return true
                }
                if (was == Hit.NONE) return false
                if (longFired) { longFired = false; return true }
                val (h, b) = hit(x, y)
                if (h == Hit.PILL && was == Hit.PILL && !expanded) {
                    primary?.let { openApp(it) }   // ketuk sekali -> aplikasinya
                    return true
                }
                if (h == was && b === wasBlock && hitIdx == wasIdx) perform(h, b, wasIdx)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                seekBlock = null
                if (swiping) { swiping = false; dragX.to(0f, 0.3f, 0.8f); kick() }
                if (downHit == Hit.PILL) {
                    press.to(1f, 0.35f, 0.6f)
                    kick()
                }
                downHit = Hit.NONE
                downBlock = null
                return true
            }
        }
        return downHit != Hit.NONE
    }

    /** Buka aplikasi sumber: pakai intent notifikasi/sesi; kalau tidak ada atau gagal, buka peluncur aplikasinya. */
    private fun openApp(item: IslandItem) {
        val pi = if (item.kind == Kind.MEDIA) item.controller?.sessionActivity else item.tap
        var ok = false
        if (pi != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val o = android.app.ActivityOptions.makeBasic()
                    o.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    pi.send(context, 0, null, null, null, null, o.toBundle())
                } else pi.send()
                ok = true
            } catch (_: Throwable) {}
        }
        if (ok) return
        val pkg = if (item.kind == Kind.MEDIA) item.controller?.packageName.orEmpty() else item.pkg
        if (pkg.isEmpty()) return
        try {
            val li = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(li)
        } catch (_: Throwable) {}
    }

    private val likedOverride = HashMap<String, Boolean>()
    // Nama aksi "hapus/batalkan suka" = lagu SUDAH disukai; "tambah/suka" = BELUM disukai.
    private val likedWords = arrayOf("remove", "unlike", "unfav", "unsave", "hapus", "batal", "dislike", "delete")

    private fun supportsLike(i: IslandItem): Boolean = i.controller?.let {
        likeAction(it) != null || it.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING) != null
    } == true

    private fun isLiked(i: IslandItem): Boolean {
        likedOverride[i.key + "|" + i.title]?.let { return it }
        val mc = i.controller ?: return false
        val r = mc.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        if (r != null && r.isRated && r.ratingStyle == android.media.Rating.RATING_HEART) return r.hasHeart()
        val a = likeAction(mc) ?: return false
        val s = (a.action + " " + a.name).lowercase()
        return likedWords.any { s.contains(it) }
    }

    private val likeWords = arrayOf("like", "love", "favorit", "favourite", "favorite", "heart", "thumb", "suka", "save", "add_to")

    /** Aksi kustom aplikasi musik untuk suka/favorit (mis. Spotify, YT Music). */
    private fun likeAction(mc: android.media.session.MediaController): android.media.session.PlaybackState.CustomAction? =
        mc.playbackState?.customActions?.firstOrNull { a ->
            val s = (a.action + " " + a.name).lowercase()
            likeWords.any { s.contains(it) }
        }

    private fun openOutputSwitcher(pkg: String) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg).addFlags(flags))
        } catch (_: Throwable) {
            try { context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(flags)) } catch (_: Throwable) {}
        }
    }

    private fun perform(h: Hit, b: Block?, idx: Int) {
        val item = b?.item
        try {
            when (h) {
                Hit.PILL -> onToggle?.invoke()
                Hit.PREV -> item?.controller?.transportControls?.skipToPrevious()
                Hit.NEXT -> item?.controller?.transportControls?.skipToNext()
                // Bintang: aksi kustom pertama aplikasi musik (suka/favorit) bila ada.
                Hit.MUTE -> toggleMute()
                Hit.STAR -> item?.let { item -> item.controller?.let { mc ->
                    val now = isLiked(item)
                    val ca = likeAction(mc)
                    if (ca != null) mc.transportControls.sendCustomAction(ca, null)
                    else try { mc.transportControls.setRating(android.media.Rating.newHeartRating(!now)) } catch (_: Throwable) {}
                    likedOverride[item.key + "|" + (item.title)] = !now
                    kick()
                } }
                // Keluaran audio (speaker/Bluetooth) -> panel pemilih keluaran sistem.
                Hit.OUTPUT -> openOutputSwitcher(item?.controller?.packageName.orEmpty())
                Hit.PLAY -> item?.let {
                    if (it.playing) it.controller?.transportControls?.pause()
                    else it.controller?.transportControls?.play()
                }
                // Ketuk isi kartu: buka aplikasinya, island tetap terbuka (tutup sendiri / ketuk di luar).
                // Aplikasi dibuka -> island menutup kartunya (kembali ke pill).
                Hit.OPEN -> if (item != null) {
                    openApp(item)
                    onOutside?.invoke()
                }
                Hit.ACTION -> item?.actions?.getOrNull(idx)?.intent?.send()
                Hit.SEEK, Hit.NONE -> Unit
            }
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- gambar

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(curOffX() + dragX.value, offY)
        try {
            drawIsland(c)
        } catch (t: Throwable) {
            android.util.Log.e("ZestIsland", "draw failed", t)
        }
        c.restore()
    }

    private fun drawIsland(c: Canvas) {
        val v = vis.value.coerceIn(0f, 1f) * (1f - (abs(dragX.value) / dp(150)).coerceIn(0f, 1f))
        if (v < 0.01f && items.isEmpty()) return
        val cx = cx()
        val w = max(dp(8), sw.value)
        val h = max(dp(8), sh.value)
        val appear = 0.55f + 0.45f * v
        val pulse = (sw.vel / 9000f).coerceIn(-0.05f, 0.05f)   // regang saat melebar
        val sx = (1f + pulse) * appear * press.value
        val sy = (1f - pulse * 0.6f) * appear * press.value
        val left = cx - w / 2f
        val top = if (h < collapsedH && !expanded) (collapsedH - h) / 2f else 0f
        val pivotY = min(h / 2f, collapsedH / 2f)

        c.save()
        c.scale(sx, sy, cx, pivotY)
        val alpha = (255 * v).toInt()
        val glassK = sa.value.coerceIn(0f, 1f)
        bg.alpha = (alpha * (0.99f - 0.13f * glassK)).toInt()   // pil hampir hitam, kartu terbuka tembus pandang

        // pil utama
        rect.set(left, top, left + w, top + h)
        clipR.set(rect)
        val r = min(sr.value, min(w, h) / 2f)
        c.drawRoundRect(rect, r, r, bg)
        if (glassK > 0.01f) {   // lapisan kaca terang saat kartu terbuka (gaya iOS 26)
            tint.alpha = (alpha * 0.05f * glassK).toInt()
            c.drawRoundRect(rect, r, r, tint)
        }
        drawGlass(c, left, top, w, h, r, alpha, glassK)

        // kapsul kedua (stopwatch/telepon)
        val spl = split.value.coerceIn(0f, 1f)
        if (spl > 0.01f) {
            val d = satD * spl
            val scx = cx + collapsedW / 2f + satGap + satD / 2f
            c.drawCircle(scx, h / 2f, d / 2f, bg)
            val si = satItem
            if (si != null && spl > 0.5f) drawSatellite(c, si, scx, h / 2f, (spl - 0.5f) * 2f * v)
        }

        // isi
        c.save()
        c.clipRect(clipR)
        val expA = sa.value.coerceIn(0f, 1f)
        val ca = (1f - expA * 3f).coerceIn(0f, 1f) * v
        if (ca > 0.02f) drawCollapsed(c, left, w, ca)
        val ea = ((expA - 0.2f) / 0.8f).coerceIn(0f, 1f) * v
        if (ea > 0.02f) {
            for (b in blocks) {
                val a = ea * b.a.value.coerceIn(0f, 1f)
                if (a > 0.02f) drawBlock(c, b, cx, a)
            }
        }
        c.restore()
        c.restore()
    }

    /** Kilap kaca di separuh atas + tepi bercahaya; tanpa alokasi per frame. */
    private fun drawGlass(c: Canvas, left: Float, top: Float, w: Float, h: Float, r: Float, alpha: Int, k: Float) {
        glMat.setScale(1f, h * 0.62f)
        glMat.postTranslate(0f, top)
        glossShader.setLocalMatrix(glMat)
        glossPaint.alpha = (alpha * (0.10f + 0.28f * k)).toInt()
        rect.set(left, top, left + w, top + h)
        c.drawRoundRect(rect, r, r, glossPaint)
        val sw = max(dp(0.9f), 1f)
        glMat.setScale(w, h)
        glMat.postTranslate(left, top)
        rimShader.setLocalMatrix(glMat)
        rimPaint.strokeWidth = sw
        rimPaint.alpha = (alpha * (0.40f + 0.25f * k)).toInt()
        rect.set(left + sw / 2f, top + sw / 2f, left + w - sw / 2f, top + h - sw / 2f)
        c.drawRoundRect(rect, max(0f, r - sw / 2f), max(0f, r - sw / 2f), rimPaint)
        rect.set(left, top, left + w, top + h)
    }

    private fun drawCollapsed(c: Canvas, left: Float, w: Float, a: Float) {
        val p = primary ?: return
        val h = collapsedH
        val midY = h / 2f
        val ia = (255 * a).toInt()
        if (p.kind == Kind.NOTICE) {
            drawNotice(c, p, left, w, midY, ia)
            return
        }
        val circle = false
        val s = if (circle) h * 0.78f else artSize()
        val ax = if (circle) left + (w - s) / 2f else left + dp(4)
        val ay = midY - s / 2f
        val sh = pArtShader
        if (p.art != null && sh != null) {
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(ax, ay)
            c.drawCircle(s / 2f, s / 2f, s / 2f, shaderPaint)
            c.restore()
            shaderPaint.shader = null
        } else {
            drawIcon(c, p.icon, ax, ay, s, ia)
        }
        if (circle) return
        val rx = left + w - dp(9)
        when {
            p.hasTimer -> {
                tPill.alpha = ia
                val fm = tPill.fontMetrics
                c.drawText(formatTimer(p, true), rx, midY - (fm.ascent + fm.descent) / 2f, tPill)
            }
            p.kind == Kind.MEDIA -> drawEq(c, rx - dp(20.5f), midY, min(h * 0.5f, dp(13)), ia, p.playing)
            p.chipText.isNotEmpty() || p.icon2 != null -> {
                // Gaya chip Samsung: logo 1 di kiri, skor di kanan kamera, logo 2 paling kanan.
                var tx = rx
                val s2 = h * 0.62f
                if (p.icon2 != null) {
                    drawIcon(c, p.icon2, rx - s2, midY - s2 / 2f, s2, ia)
                    tx = rx - s2 - dp(4)
                }
                if (p.chipText.isNotEmpty()) {
                    tPill.alpha = ia
                    val fm = tPill.fontMetrics
                    c.drawText(p.chipText, tx, midY - (fm.ascent + fm.descent) / 2f, tPill)
                }
            }
        }
    }


    // ------------------------------------------------------- pemberitahuan kecil

    private fun drawNotice(c: Canvas, p: IslandItem, left: Float, w: Float, midY: Float, ia: Int) {
        val t = (SystemClock.uptimeMillis() - p.shownAt) / 1000f
        val gs = collapsedH * 0.56f
        val hasText = p.title.isNotEmpty()
        val textW = if (hasText) tNotice.measureText(p.title) else 0f
        // Tengah pill = lubang/notch kamera -> ikon di kiri, teks di kanan (seperti item lain).
        drawGlyph(c, p, left + dp(11) + gs / 2f, midY, gs, ia, t)
        if (hasText) {
            tNotice.alpha = ia
            val fm = tNotice.fontMetrics
            c.drawText(p.title, left + w - dp(12) - textW, midY - (fm.ascent + fm.descent) / 2f, tNotice)
        }
    }

    private fun drawGlyph(c: Canvas, p: IslandItem, cx: Float, cy: Float, s: Float, ia: Int, t: Float) {
        val col = if (p.accent == -1) 0xFFFFFFFF.toInt() else p.accent
        stroke.color = col
        stroke.alpha = ia
        stroke.strokeWidth = max(dp(1.3f), s * 0.1f)
        stroke.strokeJoin = Paint.Join.ROUND
        fill.color = col
        fill.alpha = ia
        when (p.glyph) {
            "ring", "silent" -> {
                c.save()
                if (p.glyph == "ring") c.rotate(sin(t * 22f) * 14f * exp(-t * 3.2f), cx, cy - s * 0.45f)
                bell(c, cx, cy, s)
                c.restore()
                if (p.glyph == "silent") {
                    c.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, stroke)
                }
            }
            "vibrate" -> {
                val dx = sin(t * 55f) * s * 0.07f * exp(-t * 2.5f)
                c.save()
                c.translate(dx, 0f)
                rect.set(cx - s * 0.2f, cy - s * 0.42f, cx + s * 0.2f, cy + s * 0.42f)
                c.drawRoundRect(rect, s * 0.08f, s * 0.08f, stroke)
                c.restore()
                for (k in 0 until 2) {
                    val o = s * (0.36f + k * 0.14f)
                    c.drawLine(cx - o, cy - s * 0.16f, cx - o, cy + s * 0.16f, stroke)
                    c.drawLine(cx + o, cy - s * 0.16f, cx + o, cy + s * 0.16f, stroke)
                }
            }
            "charge", "low" -> {
                val bw = s * 1.05f
                val bh = s * 0.55f
                rect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f - s * 0.07f, cy + bh / 2f)
                stroke.strokeWidth = max(dp(1.1f), s * 0.07f)
                c.drawRoundRect(rect, bh * 0.28f, bh * 0.28f, stroke)
                rect.set(cx + bw / 2f - s * 0.05f, cy - bh * 0.17f, cx + bw / 2f + s * 0.02f, cy + bh * 0.17f)
                c.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
                val frac = (p.level / 100f).coerceIn(0.08f, 1f)
                val inL = cx - bw / 2f + s * 0.09f
                val inR = cx + bw / 2f - s * 0.16f
                rect.set(inL, cy - bh / 2f + s * 0.09f, inL + (inR - inL) * frac, cy + bh / 2f - s * 0.09f)
                c.drawRoundRect(rect, bh * 0.16f, bh * 0.16f, fill)
                if (p.glyph == "charge") {
                    fill.color = 0xFFFFFFFF.toInt()
                    fill.alpha = ia
                    path.reset()
                    path.moveTo(cx + s * 0.06f, cy - bh * 0.42f)
                    path.lineTo(cx - s * 0.14f, cy + s * 0.02f)
                    path.lineTo(cx - s * 0.01f, cy + s * 0.02f)
                    path.lineTo(cx - s * 0.06f, cy + bh * 0.42f)
                    path.lineTo(cx + s * 0.16f, cy - s * 0.04f)
                    path.lineTo(cx + s * 0.03f, cy - s * 0.04f)
                    path.close()
                    c.drawPath(path, fill)
                }
            }
            "unlock" -> {
                val k = (t / 0.32f).coerceIn(0f, 1f)
                val e = 1f - (1f - k) * (1f - k)                 // easeOut
                val bounce = 1f + 0.12f * sin(k * PI.toFloat())
                c.save()
                c.scale(bounce, bounce, cx, cy)
                fill.color = 0xFFFFFFFF.toInt()
                fill.alpha = ia
                rect.set(cx - s * 0.4f, cy - s * 0.04f, cx + s * 0.4f, cy + s * 0.46f)
                c.drawRoundRect(rect, s * 0.1f, s * 0.1f, fill)
                stroke.color = 0xFFFFFFFF.toInt()
                stroke.alpha = ia
                stroke.strokeWidth = max(dp(1.4f), s * 0.12f)
                val lift = s * 0.16f * e
                val lx = cx - s * 0.24f
                val rx = cx + s * 0.24f
                path.reset()
                path.moveTo(lx, cy - s * 0.04f)
                path.lineTo(lx, cy - s * 0.3f)
                path.arcTo(lx, cy - s * 0.54f, rx, cy - s * 0.06f, 180f, 180f, false)
                path.lineTo(rx, cy - s * 0.04f - lift)
                c.drawPath(path, stroke)
                c.restore()
            }
        }
    }

    private fun bell(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.4f, cy + s * 0.3f)
        path.lineTo(cx - s * 0.32f, cy + s * 0.2f)
        path.lineTo(cx - s * 0.3f, cy - s * 0.1f)
        path.arcTo(cx - s * 0.3f, cy - s * 0.52f, cx + s * 0.3f, cy + s * 0.08f, 180f, 180f, false)
        path.lineTo(cx + s * 0.32f, cy + s * 0.2f)
        path.lineTo(cx + s * 0.4f, cy + s * 0.3f)
        path.close()
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy + s * 0.46f, s * 0.08f, fill)
    }

    private fun drawEq(c: Canvas, x: Float, midY: Float, maxH: Float, ia: Int, playing: Boolean) {
        val t = SystemClock.uptimeMillis() / 1000f
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = ia
        val bw = dp(2.5f)
        val gap = dp(2f)
        for (i in 0 until 5) {
            // naik-turun seperti equalizer: tiap batang punya kecepatan & fase sendiri
            val k = if (playing) 0.18f + 0.82f * (0.5f + 0.5f * sin(t * (7.5f + i * 1.9f) + i * 2.3f)) *
                (0.65f + 0.35f * sin(t * 2.6f + i * 0.9f)) else 0.2f
            val bh = max(dp(2f), maxH * k)
            val bx = x + i * (bw + gap)
            rect.set(bx, midY - bh / 2f, bx + bw, midY + bh / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, fill)
        }
    }

    private fun drawIcon(c: Canvas, d: Drawable?, x: Float, y: Float, size: Float, ia: Int) {
        if (d == null) return
        try {
            d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
            d.alpha = ia
            d.draw(c)
        } catch (_: Throwable) {}
    }

    private fun drawSatellite(c: Canvas, item: IslandItem, cx: Float, cy: Float, a: Float) {
        val ia = (255 * a.coerceIn(0f, 1f)).toInt()
        val art = item.art
        if (art != null) {
            val s = satD * 0.72f
            val sh = makeShader(art, s)
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(cx - s / 2f, cy - s / 2f)
            c.drawCircle(s / 2f, s / 2f, s / 2f, shaderPaint)
            c.restore()
            shaderPaint.shader = null
            return
        }
        if (item.kind == Kind.CALL && item.icon != null) {
            val s = satD * 0.6f
            drawIcon(c, item.icon, cx - s / 2f, cy - s / 2f, s, ia)
            return
        }
        // ikon stopwatch
        val r = satD * 0.27f
        val orange = 0xFFFF9F0A.toInt()
        stroke.color = orange
        stroke.alpha = ia
        stroke.strokeWidth = max(dp(1.4f), satD * 0.06f)
        val oy = cy + satD * 0.04f
        c.drawCircle(cx, oy, r, stroke)
        c.drawLine(cx, oy, cx, oy - r * 0.62f, stroke)
        val ang = (SystemClock.uptimeMillis() % 60_000L) / 60_000f * 2f * PI.toFloat()
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.alpha = ia
        c.drawLine(cx, oy, cx + sin(ang) * r * 0.62f, oy - cos(ang) * r * 0.62f, stroke)
        fill.color = orange
        fill.alpha = ia
        rect.set(cx - r * 0.22f, oy - r - satD * 0.1f, cx + r * 0.22f, oy - r - satD * 0.02f)
        c.drawRoundRect(rect, dp(1f), dp(1f), fill)
    }

    private val btnRect = RectF()

    private fun drawBlock(c: Canvas, b: Block, cx: Float, a: Float) {
        // Isi kartu membesar halus dari 94% saat muncul (seperti iOS), tanpa alokasi.
        val sc = 0.94f + 0.06f * a.coerceIn(0f, 1f)
        c.save()
        c.scale(sc, sc, cx, b.y.value + b.h / 2f)
        drawBlockInner(c, b, cx, a)
        c.restore()
    }

    private fun drawBlockInner(c: Canvas, b: Block, cx: Float, a: Float) {
        val ia = (255 * a).toInt()
        val x0 = cx - blockW / 2f
        val y0 = b.y.value
        val i = b.item
        if (i.kind == Kind.MEDIA) {
            drawMedia(c, b, x0, y0, ia)
            return
        }
        blockBg.alpha = (ia * 0.11f).toInt()
        rect.set(x0, y0, x0 + blockW, y0 + b.h)
        c.drawRoundRect(rect, dp(20), dp(20), blockBg)
        blockRim.strokeWidth = dp(0.8f)
        blockRim.alpha = (ia * 0.20f).toInt()
        c.drawRoundRect(rect, dp(20), dp(20), blockRim)
        // ikon
        val ix = x0 + dp(10)
        val iy = y0 + dp(12)
        iconBg.alpha = (ia * 0.16f).toInt()
        rect.set(ix, iy, ix + dp(40), iy + dp(40))
        c.drawRoundRect(rect, dp(12), dp(12), iconBg)
        drawIcon(c, i.icon, ix + dp(9), iy + dp(9), dp(22), ia)
        val tx = x0 + dp(60)
        tTitle.alpha = ia
        tSub.alpha = ia
        if (b.sub.isEmpty()) {
            c.drawText(b.title, tx, y0 + dp(37), tTitle)
        } else {
            c.drawText(b.title, tx, y0 + dp(29), tTitle)
            c.drawText(b.sub, tx, y0 + dp(47), tSub)
        }
        if (i.hasTimer) {
            tBig.alpha = ia
            val fm = tBig.fontMetrics
            c.drawText(formatTimer(i, false), x0 + blockW - dp(14), y0 + dp(32) - (fm.ascent + fm.descent) / 2f, tBig)
        } else if (i.chipText.isNotEmpty() || i.icon2 != null) {
            tBig.alpha = ia
            val fm = tBig.fontMetrics
            var rxx = x0 + blockW - dp(14)
            if (i.icon2 != null) {
                drawIcon(c, i.icon2, rxx - dp(26), y0 + dp(19), dp(26), ia)
                rxx -= dp(34)
            }
            if (i.chipText.isNotEmpty()) c.drawText(i.chipText, rxx, y0 + dp(32) - (fm.ascent + fm.descent) / 2f, tBig)
        }
        // tombol aksi (Berhenti / Jeda / Tolak / Akhiri ...)
        if (hasActions(i)) {
            val off = if (needsMute(i)) 1 else 0
            val n = i.actions.size + off
            if (off == 1) {
                actionRect(b, 0, n, btnRect)
                val muted = micMuted()
                fill.color = if (muted) 0xFFFFFFFF.toInt() else 0xFF3A3A40.toInt()
                fill.alpha = ia
                c.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, fill)
                drawMic(c, btnRect.centerX(), btnRect.centerY(), btnRect.height() * 0.46f, muted, ia)
            }
            for (k in 0 until i.actions.size) {
                actionRect(b, k + off, n, btnRect)
                val t = i.actions[k].title
                val danger = isDanger(t)
                if (i.kind == Kind.CALL) {
                    // Gaya iPhone: kapsul merah (tolak/akhiri) dan hijau (angkat).
                    val answer = !danger && !i.hasTimer
                    fill.color = when {
                        danger -> 0xFFFF453A.toInt()
                        answer -> 0xFF30D158.toInt()
                        else -> 0xFF3A3A40.toInt()
                    }
                    fill.alpha = ia
                    c.drawRoundRect(btnRect, btnRect.height() / 2f, btnRect.height() / 2f, fill)
                    if (danger || answer) drawHandset(c, btnRect.centerX(), btnRect.centerY(), btnRect.height() * 0.5f, danger, ia)
                    else {
                        tBtn.alpha = ia
                        val fm2 = tBtn.fontMetrics
                        val lb = TextUtils.ellipsize(t, tBtn, btnRect.width() - dp(12), TextUtils.TruncateAt.END).toString()
                        c.drawText(lb, btnRect.centerX(), btnRect.centerY() - (fm2.ascent + fm2.descent) / 2f, tBtn)
                    }
                    continue
                }
                fill.color = if (danger) 0xFFFF453A.toInt() else 0xFF3A3A40.toInt()
                fill.alpha = ia
                c.drawRoundRect(btnRect, dp(15), dp(15), fill)
                tBtn.alpha = ia
                val fm = tBtn.fontMetrics
                val label = TextUtils.ellipsize(t, tBtn, btnRect.width() - dp(12), TextUtils.TruncateAt.END).toString()
                c.drawText(label, btnRect.centerX(), btnRect.centerY() - (fm.ascent + fm.descent) / 2f, tBtn)
            }
        }
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float, s: Float, muted: Boolean, ia: Int) {
        val col = if (muted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        fill.color = col; fill.alpha = ia
        stroke.color = col; stroke.alpha = ia
        stroke.strokeWidth = max(dp(1.5f), s * 0.11f)
        stroke.strokeCap = Paint.Cap.ROUND
        rect.set(cx - s * 0.22f, cy - s * 0.55f, cx + s * 0.22f, cy + s * 0.12f)
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, fill)
        rect.set(cx - s * 0.42f, cy - s * 0.3f, cx + s * 0.42f, cy + s * 0.34f)
        c.drawArc(rect, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + s * 0.34f, cx, cy + s * 0.55f, stroke)
        if (muted) c.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.55f, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** Gagang telepon: busur tebal; ditelungkupkan (diputar) untuk tombol tutup. */
    private fun drawHandset(c: Canvas, cx: Float, cy: Float, s: Float, hangUp: Boolean, ia: Int) {
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.alpha = ia
        stroke.strokeWidth = s * 0.34f
        stroke.strokeCap = Paint.Cap.ROUND
        c.save()
        c.rotate(if (hangUp) 135f else -45f, cx, cy)
        rect.set(cx - s * 0.62f, cy - s * 0.34f, cx + s * 0.62f, cy + s * 0.9f)
        path.reset()
        path.arcTo(rect, 205f, 130f, true)
        c.drawPath(path, stroke)
        c.restore()
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun isDanger(t: String): Boolean {
        val l = t.lowercase()
        return l.contains("stop") || l.contains("berhenti") || l.contains("hentikan") || l.contains("akhiri") ||
            l.contains("tolak") || l.contains("decline") || l.contains("end") || l.contains("reset") ||
            l.contains("matikan") || l.contains("tutup") || l.contains("hang") || l.contains("reject") ||
            l.contains("dismiss") || l.contains("putus") || l.contains("batal") || l.contains("cancel")
    }

    private fun drawMedia(c: Canvas, b: Block, x0: Float, y0: Float, ia: Int) {
        val i = b.item
        val artS = dp(60)
        val ax = x0 + dp(2)
        val ay = y0 + dp(2)
        val sh = b.shader
        if (sh != null && i.art != null) {
            shaderPaint.shader = sh
            shaderPaint.alpha = ia
            c.save()
            c.translate(ax, ay)
            c.drawRoundRect(0f, 0f, artS, artS, dp(12), dp(12), shaderPaint)
            c.restore()
            shaderPaint.shader = null
        } else {
            iconBg.alpha = (ia * 0.16f).toInt()
            rect.set(ax, ay, ax + artS, ay + artS)
            c.drawRoundRect(rect, dp(12), dp(12), iconBg)
            drawIcon(c, i.icon, ax + dp(15), ay + dp(15), dp(28), ia)
        }
        val tx = x0 + dp(74)
        tTitle.alpha = ia
        tTitle.textSize = sp(17f)
        tSub.alpha = ia
        c.drawText(b.title, tx, y0 + dp(26), tTitle)
        tTitle.textSize = sp(15f)
        if (b.sub.isNotEmpty()) c.drawText(b.sub, tx, y0 + dp(48), tSub)
        drawEq(c, x0 + blockW - dp(28), y0 + dp(14), dp(16), ia, i.playing)

        // progress
        var pos = i.positionMs
        if (i.playing) pos += ((SystemClock.elapsedRealtime() - i.posTime) * i.speed).toLong()
        pos = if (i.durationMs > 0) pos.coerceIn(0L, i.durationMs) else max(pos, 0L)
        var frac = if (i.durationMs > 0) pos.toFloat() / i.durationMs else 0f
        if (seekBlock === b && i.durationMs > 0) {
            frac = seekFrac
            pos = (seekFrac * i.durationMs).toLong()
        }
        val px0 = x0 + dp(42)
        val px1 = x0 + blockW - dp(46)
        val py = y0 + dp(90)
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = (ia * 0.16f).toInt()
        rect.set(px0, py, px1, py + dp(6))
        c.drawRoundRect(rect, dp(3), dp(3), fill)
        fill.alpha = ia
        rect.set(px0, py, px0 + max(dp(6), (px1 - px0) * frac.coerceIn(0f, 1f)), py + dp(6))
        c.drawRoundRect(rect, dp(3), dp(3), fill)
        tSmall.alpha = ia
        val ty = py + dp(3) - (tSmall.fontMetrics.ascent + tSmall.fontMetrics.descent) / 2f
        c.drawText(formatMs(pos), x0 + dp(2), ty, tSmall)
        val dur = if (i.durationMs > 0) "-" + formatMs((i.durationMs - pos).coerceAtLeast(0L)) else "--:--"
        tSmall.textAlign = Paint.Align.RIGHT
        c.drawText(dur, x0 + blockW - dp(2), ty, tSmall)
        tSmall.textAlign = Paint.Align.LEFT

        // kontrol
        val cr = controlRects(b)
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = ia
        drawSkip(c, cr[0].centerX(), cr[0].centerY(), false)
        if (i.playing) drawPause(c, cr[1].centerX(), cr[1].centerY()) else drawPlay(c, cr[1].centerX(), cr[1].centerY())
        drawSkip(c, cr[2].centerX(), cr[2].centerY(), true)
        val has = supportsLike(i)
        val liked = has && isLiked(i)
        fill.color = if (liked) 0xFFFFD60A.toInt() else 0xFF8E8E93.toInt()   // kuning = disukai, abu = belum
        fill.alpha = (ia * (if (liked) 1f else if (has) 0.95f else 0.16f)).toInt()   // tidak didukung = sangat redup
        drawStar(c, cr[3].centerX(), cr[3].centerY(), dp(11))
        fill.color = 0xFFFFFFFF.toInt()
        fill.alpha = (ia * 0.9f).toInt()
        drawOutput(c, cr[4].centerX(), cr[4].centerY(), dp(12), ia)
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        for (k in 0 until 10) {
            val rr = if (k % 2 == 0) s else s * 0.45f
            val a = (-90 + k * 36) * (PI / 180.0)
            val px = x + (cos(a) * rr).toFloat()
            val py = y + (sin(a) * rr).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, fill)
    }

    /** Ikon keluaran audio (gaya AirPlay): dua busur + segitiga. */
    private fun drawOutput(c: Canvas, x: Float, y: Float, s: Float, ia: Int) {
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.alpha = (ia * 0.9f).toInt()
        stroke.strokeWidth = dp(1.8f)
        stroke.strokeCap = Paint.Cap.ROUND
        for (k in 0..1) {
            val rr = s * (0.55f + 0.45f * k)
            rect.set(x - rr, y - s * 0.15f - rr, x + rr, y - s * 0.15f + rr)
            c.drawArc(rect, 150f, 240f, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT
        path.reset()
        path.moveTo(x - s * 0.5f, y + s * 0.85f)
        path.lineTo(x + s * 0.5f, y + s * 0.85f)
        path.lineTo(x, y + s * 0.15f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawPlay(c: Canvas, x: Float, y: Float) {
        val s = dp(15)
        path.reset()
        path.moveTo(x - s * 0.7f, y - s)
        path.lineTo(x + s * 0.95f, y)
        path.lineTo(x - s * 0.7f, y + s)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawPause(c: Canvas, x: Float, y: Float) {
        val s = dp(14)
        val w = dp(6)
        rect.set(x - s * 0.8f, y - s, x - s * 0.8f + w, y + s)
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)
        rect.set(x + s * 0.8f - w, y - s, x + s * 0.8f, y + s)
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill)
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, next: Boolean) {
        val s = dp(10)
        val d = if (next) 1f else -1f
        for (k in 0..1) {   // dua segitiga seperti iPhone (rewind / fast-forward)
            val ox = x + d * (k - 0.5f) * s * 1.35f
            path.reset()
            path.moveTo(ox - d * s * 0.7f, y - s)
            path.lineTo(ox + d * s * 0.7f, y)
            path.lineTo(ox - d * s * 0.7f, y + s)
            path.close()
            c.drawPath(path, fill)
        }
    }

    // ----------------------------------------------------------------- teks

    private val msCache = HashMap<Long, String>()

    private fun formatMs(ms: Long): String {
        val sec = ms / 1000
        return msCache.getOrPut(sec) {
            if (msCache.size > 600) msCache.clear()
            String.format("%d:%02d", sec / 60, sec % 60)
        }
    }

    private val timerSb = StringBuilder(12)

    private fun two(sb: StringBuilder, v: Long) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }

    private fun formatTimer(item: IslandItem, short: Boolean): String {
        val now = System.currentTimeMillis()
        var ms = if (item.frozenMs >= 0L) item.frozenMs
        else if (item.countDown) item.whenMs - now else now - item.whenMs
        if (ms < 0) ms = 0
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val cs = (ms / 10) % 100
        val sb = timerSb
        sb.setLength(0)
        if (h > 0) {
            sb.append(h).append(':'); two(sb, m); sb.append(':'); two(sb, s)
        } else {
            sb.append(m).append(':'); two(sb, s)
            if (!short) { sb.append('.'); two(sb, cs) }
        }
        return sb.toString()
    }
}
