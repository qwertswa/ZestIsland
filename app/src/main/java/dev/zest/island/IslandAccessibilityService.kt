package dev.zest.island

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout

/**
 * Memasang island memakai DUA jendela overlay:
 *  1. jendela gambar: ukuran tetap, tidak menerima sentuhan, tidak pernah diubah ukurannya
 *     (mengubah ukuran jendela saat animasi membuat satu frame bergeser/berkedip);
 *  2. jendela sentuh: transparan, hanya seukuran pil/kartu; ukurannya boleh berubah karena tak terlihat.
 */
class IslandAccessibilityService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var view: IslandView
    private lateinit var holder: FrameLayout
    private lateinit var touchView: TouchCatcher
    private var lpVis: WindowManager.LayoutParams? = null
    private var lpTouch: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var expanded = false
    private val seenAlerts = HashSet<String>()
    private var expandedByAlert = false
    private var shadeOpen = false
    private var lastLocked = false
    private val km by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val tb = Rect()

    private var offX = 0
    private var offY = 0
    private val prefKeys = listOf("zest_island_width", "zest_island_height", "zest_island_x", "zest_island_y", "zest_island_debug")
    private val prefObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { loadPrefs() }
    }

    private fun geti(k: String) = Settings.System.getInt(contentResolver, k, 0)

    private fun screenW(): Int = try {
        wm.currentWindowMetrics.bounds.width()
    } catch (_: Throwable) { resources.displayMetrics.widthPixels }

    /** Baca ukuran/posisi pill dari UN1CA Settings (dp), lalu terapkan. */
    private fun loadPrefs() {
        Dbg.on = geti("zest_island_debug") == 1
        val d = resources.displayMetrics.density
        view.setPillSize(geti("zest_island_width"), geti("zest_island_height"))
        offX = (geti("zest_island_x").coerceIn(-400, 400) * d).toInt()
        offY = (geti("zest_island_y").coerceIn(-100, 600) * d).toInt()
        // Pergeseran digambar oleh view (kartu terbuka tetap di tengah); jendela cukup lebar untuk keduanya.
        view.setOffset(offX.toFloat(), offY.toFloat())
        val p = lpVis
        if (attached && p != null) {
            p.width = view.fixedWindowSize().first + 2 * Math.abs(offX)
            p.height = view.fixedWindowHeight()
            p.x = (screenW() - p.width) / 2
            p.y = 0
            try { wm.updateViewLayout(holder, p) } catch (_: Throwable) {}
            updateTouchWindow()
        }
    }

    private val hubListener: () -> Unit = {
        // Gabungkan banyak pembaruan beruntun (media/progress) jadi satu refresh.
        handler.removeCallbacks(refreshRun)
        handler.postDelayed(refreshRun, 16)
    }
    private val refreshRun = Runnable { refresh() }
    private val collapse = Runnable { setExpanded(false) }
    private val hideRun: Runnable = Runnable {
        val locked = try { km.isKeyguardLocked } catch (_: Throwable) { false }
        if (locked || shadeOpen || isLandscape()) detach()
    }
    private val detachRun = Runnable { if (IslandHub.snapshot().isEmpty()) detach() }

    /**
     * Panel QS / shade terbuka = jendela sistem fokus berjudul "notification shade" dst.
     * Hanya membaca daftar jendela (murah); root jendela dibaca hanya untuk satu kandidat.
     */
    private val checkShade: Runnable = Runnable {
        val open = try {
            val cand = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }
            if (cand == null) false else {
                val t = cand.title?.toString()?.lowercase().orEmpty()
                t.contains("shade") || t.contains("notifikasi") || t.contains("notification") ||
                    t.contains("quick") || t.contains("pengaturan cepat") ||
                    (t.isEmpty() && cand.root?.packageName?.toString() == "com.android.systemui")
            }
        } catch (_: Throwable) { false }
        val locked = try { km.isKeyguardLocked } catch (_: Throwable) { false }
        val fg = foregroundPkg()
        val fgChanged = fg != IslandHub.fgPkg
        IslandHub.fgPkg = fg
        if (open != shadeOpen || locked != lastLocked || fgChanged) {
            shadeOpen = open
            refresh()
        }
        // Selama terkunci, cek berkala: peristiwa aksesibilitas tidak selalu datang saat kunci dibuka.
        if (locked) handler.postDelayed(checkShadeAgain, 350)
    }

    /** Paket aplikasi biasa yang sedang tampil di depan ("" bila launcher/sistem/tidak diketahui). */
    private fun foregroundPkg(): String = try {
        // daftar jendela terurut dari paling atas -> jendela aplikasi teratas = aplikasi di depan
        val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val p = w?.root?.packageName?.toString().orEmpty()
        if (p == "com.android.systemui" || p == packageName) "" else p
    } catch (_: Throwable) { "" }

    private val checkShadeAgain: Runnable = Runnable { handler.post(checkShade) }

    private val ticker = object : Runnable {
        override fun run() {
            view.tick()
            handler.postDelayed(this, 50)
        }
    }
    private var ticking = false

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> {
                    SystemNotices.unlock()
                    refresh()
                }
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> refresh()
            }
        }
    }

    override fun onServiceConnected() {
        if (SysUiMode.active(this)) {   // island sudah berjalan di dalam SystemUI
            disableSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = IslandView(this)
        view.onToggle = { setExpanded(!expanded) }
        view.onOutside = { if (expanded) setExpanded(false) }
        view.onDismiss = { keys -> for (k in keys) IslandHub.remove(k) }
        view.onSettled = { updateTouchWindow() }
        holder = FrameLayout(this)
        holder.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        touchView = TouchCatcher(this)
        touchView.onTouch = { action, rx, ry ->
            val p = lpVis
            if (p == null) false else view.touchAt(action, rx - p.x, ry - p.y)
        }
        touchView.onOutside = { if (expanded) setExpanded(false) }
        IslandHub.addListener(hubListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(power, f)
        SystemNotices.register(applicationContext)
        for (k in prefKeys) contentResolver.registerContentObserver(Settings.System.getUriFor(k), false, prefObserver)
        loadPrefs()
        lastLocked = try { km.isKeyguardLocked } catch (_: Throwable) { false }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return   // abaikan jendela island sendiri
        handler.removeCallbacks(checkShade)
        handler.postDelayed(checkShade, 150)
    }

    override fun onInterrupt() {}

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::view.isInitialized) return
        // Putar layar: hitung ulang lebar layar & posisi, lalu tampil/sembunyi sesuai orientasi.
        handler.post {
            loadPrefs()
            refresh()
        }
    }

    override fun onDestroy() {
        IslandHub.removeListener(hubListener)
        try { unregisterReceiver(power) } catch (_: Throwable) {}
        SystemNotices.unregister(applicationContext)
        try { contentResolver.unregisterContentObserver(prefObserver) } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        detach()
        super.onDestroy()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        expandedByAlert = false
        handler.removeCallbacks(collapse)
        if (value) handler.postDelayed(collapse, 7000)
        refresh()
    }

    private fun refresh() {
        val locked = try { km.isKeyguardLocked } catch (_: Throwable) { false }
        // Baru saja dibuka kuncinya -> ikon gembok terbuka (sekali, dedupe di SystemNotices).
        if (lastLocked && !locked) SystemNotices.unlock()
        lastLocked = locked
        val unlocking = IslandHub.snapshot().any { it.kind == Kind.NOTICE && it.glyph == "unlock" }

        // Sembunyikan di layar kunci / layar mati / saat panel QS terbuka (kecuali animasi buka kunci).
        // Landscape: island disembunyikan. Layar kunci/panel: disembunyikan kecuali animasi buka kunci.
        if (isLandscape() || ((locked || shadeOpen) && !unlocking)) {
            expanded = false
            seenAlerts.clear()
            expandedByAlert = false
            handler.removeCallbacks(collapse)
            if (attached) {
                // Menyusut halus dulu (seperti iPhone), baru jendela dilepas.
                view.bind(IslandHub.snapshot(), false)
                view.setHidden(true)
                updateTouchWindow()
                handler.removeCallbacks(detachRun)
                handler.postDelayed(hideRun, 420)
            }
            stopTicker()
            return
        }
        handler.removeCallbacks(hideRun)
        if (attached && view.isHidden()) view.setHidden(false)
        val items = IslandHub.snapshot()
        // Notifikasi baru langsung membuka island (gaya HyperOS), tutup sendiri setelah 5 detik.
        // Telepon masuk (belum tersambung) diperlakukan seperti notifikasi baru: island terbuka dan tetap terbuka selama berdering.
        val incoming = items.any { it.kind == Kind.CALL && !it.hasTimer }
        val alertKeys = items.filter { it.kind == Kind.ALERT || (it.kind == Kind.CALL && !it.hasTimer) }
            .map { it.key }.toSet()
        val fresh = alertKeys.any { it !in seenAlerts }
        seenAlerts.retainAll(alertKeys)
        seenAlerts.addAll(alertKeys)
        if (fresh) {
            expanded = true
            expandedByAlert = true
            handler.removeCallbacks(collapse)
            if (!incoming) handler.postDelayed(collapse, 5000)
        } else if (alertKeys.isEmpty() && expandedByAlert) {
            expanded = false
            expandedByAlert = false
        }
        if (items.isEmpty()) {
            expanded = false
            stopTicker()
            // Pil bulat kecil selalu tampil walau tidak ada apa-apa (menyusut ke sini).
            handler.removeCallbacks(detachRun)
            attach()
            view.bind(items, false)
            updateTouchWindow()
            return
        }
        handler.removeCallbacks(detachRun)
        attach()
        view.bind(items, expanded)
        updateTouchWindow()
        val needTick = items.any { it.hasTimer || it.kind == Kind.NOTICE } ||
            (expanded && items.any { it.kind == Kind.MEDIA && it.playing }) ||
            items.firstOrNull()?.let { it.kind == Kind.MEDIA && it.playing } == true
        if (needTick) startTicker() else stopTicker()
    }

    private fun attach() {
        if (attached) return
        view.resetHidden()
        val vw = view.fixedWindowSize().first + 2 * Math.abs(offX)
        val vh = view.fixedWindowHeight()
        val vis = WindowManager.LayoutParams(
            vw,
            vh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW() - vw) / 2
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "ZestIslandVisual"
        }
        val touch = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "ZestIslandTouch"
        }
        try {
            wm.addView(holder, vis)
            lpVis = vis
            wm.addView(touchView, touch)
            lpTouch = touch
            attached = true
            updateTouchWindow()
        } catch (_: Throwable) {
            try { wm.removeView(holder) } catch (_: Throwable) {}
            attached = false
        }
    }

    /** Jendela sentuh mengikuti area pil/kartu (gabungan posisi sekarang dan tujuan). */
    private fun updateTouchWindow() {
        if (!attached) return
        val p = lpTouch ?: return
        val v = lpVis ?: return
        view.touchBounds(tb)
        var x = 0
        var y = 0
        var w = 1
        var h = 1
        if (!tb.isEmpty) {
            x = v.x + tb.left
            y = v.y + tb.top
            w = tb.width().coerceAtLeast(1)
            h = tb.height().coerceAtLeast(1)
        }
        if (p.x == x && p.y == y && p.width == w && p.height == h) return
        p.x = x
        p.y = y
        p.width = w
        p.height = h
        try { wm.updateViewLayout(touchView, p) } catch (_: Throwable) {}
    }

    private fun detach() {
        if (!attached) return
        try { wm.removeView(touchView) } catch (_: Throwable) {}
        try { wm.removeView(holder) } catch (_: Throwable) {}
        attached = false
        lpVis = null
        lpTouch = null
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.post(ticker)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(ticker)
    }
}

/** Jendela sentuh transparan: meneruskan sentuhan (koordinat layar) ke island. */
private class TouchCatcher(context: Context) : View(context) {
    var onTouch: ((Int, Float, Float) -> Boolean)? = null
    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return false
        }
        return onTouch?.invoke(e.actionMasked, e.rawX, e.rawY) ?: false
    }
}
