package dev.zest.island.sysui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import dev.zest.island.IslandHub
import dev.zest.island.IslandNotificationListener
import dev.zest.island.IslandView
import dev.zest.island.Kind
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.abs

/**
 * Island yang hidup di dalam proses SystemUI (seperti modul Xposed, tapi dimuat lewat smali):
 * satu jendela ber-tinggi tetap, area sentuh dibatasi lewat "touchable region", jadi
 * animasi tidak pernah mengubah ukuran jendela dan sentuhan di luar pil tetap tembus.
 */
class SysUiHost(private val app: Context) {
    private companion object {
        const val TAG = "ZestIsland"
        const val TYPE_ISLAND = 2017                 // TYPE_STATUS_BAR_SUB_PANEL
        const val BASE_FLAGS = 25166120              // NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN|SPLIT_TOUCH|HW_ACCEL
        const val FLAG_WATCH_OUTSIDE = 0x00040000
        const val PRIVATE_FLAGS = 536870992          // SHOW_FOR_ALL_USERS|NO_MOVE_ANIMATION|TRUSTED_OVERLAY
        const val TOUCHABLE_INSETS_REGION = 3
        val PREF_KEYS = listOf("zest_island_width", "zest_island_height", "zest_island_x", "zest_island_y")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wctx: Context
    private lateinit var wm: WindowManager
    private lateinit var view: IslandView
    private lateinit var root: Root
    private lateinit var lp: WindowManager.LayoutParams

    private val km = app.getSystemService(KeyguardManager::class.java)
    private val pm = app.getSystemService(PowerManager::class.java)

    private var attached = false
    private var expanded = false
    private var expandedByAlert = false
    private var touchRegionOk = false
    private var offX = 0
    private var offY = 0
    private val seenAlerts = HashSet<String>()
    private val touch = Rect()
    private val hubListener: () -> Unit = { safe { refresh() } }
    private val collapse = Runnable { setExpanded(false) }
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            view.tick()
            main.postDelayed(this, 50)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            safe {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refresh()
                }
            }
        }
    }

    private val prefObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) { safe { loadPrefs() } }
    }

    // ------------------------------------------------------------------ start

    fun start() {
        val cr = app.contentResolver
        if (geti("zest_island_enabled", 1) == 0) return

        // Pelindung crash-loop: 3x start berturut-turut dalam 30 detik -> matikan sendiri.
        val boots = try { Settings.Secure.getInt(cr, "zest_island_boots", 0) } catch (_: Throwable) { 0 }
        if (boots >= 3) {
            try { Settings.Secure.putInt(cr, "zest_island_boots", 0) } catch (_: Throwable) {}
            try { Settings.System.putInt(cr, "zest_island_enabled", 0) } catch (_: Throwable) {}
            Log.e(TAG, "crash loop detected, island disabled")
            return
        }
        try { Settings.Secure.putInt(cr, "zest_island_boots", boots + 1) } catch (_: Throwable) {}
        main.postDelayed({
            try { Settings.Secure.putInt(cr, "zest_island_boots", 0) } catch (_: Throwable) {}
        }, 30_000)

        // Tandai: mode SystemUI aktif pada boot ini -> mode Aksesibilitas tidak dipakai.
        try {
            val bc = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0)
            Settings.Secure.putInt(cr, "zest_island_sysui_boot", bc)
        } catch (_: Throwable) {}

        IslandNotificationListener.inSystemUi = true

        val display = app.getSystemService(DisplayManager::class.java).getDisplay(0)
        wctx = try {
            app.createWindowContext(display, TYPE_ISLAND, null)
        } catch (t: Throwable) {
            Log.e(TAG, "createWindowContext failed", t)
            app.createDisplayContext(display)
        }
        wm = wctx.getSystemService(WindowManager::class.java)

        view = IslandView(wctx)
        view.onToggle = { setExpanded(!expanded) }
        view.onOutside = { if (expanded) setExpanded(false) }
        view.onDismiss = { keys -> for (k in keys) IslandHub.remove(k) }
        root = Root(wctx)
        root.onOutside = { if (expanded) setExpanded(false) }
        root.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            view.fixedWindowHeight(),
            TYPE_ISLAND,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            windowAnimations = 0
            title = "ZestIsland"
        }
        try {
            val f = WindowManager.LayoutParams::class.java.getField("privateFlags")
            f.setInt(lp, f.getInt(lp) or PRIVATE_FLAGS)
        } catch (t: Throwable) {
            Log.e(TAG, "privateFlags not set", t)
        }

        touchRegionOk = installTouchRegion()

        IslandHub.addListener(hubListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(receiver, f)
        dev.zest.island.SystemNotices.register(app)
        for (k in PREF_KEYS) cr.registerContentObserver(Settings.System.getUriFor(k), false, prefObserver)
        loadPrefs()

        // Pembaca notifikasi + sesi media, di dalam SystemUI sebagai layanan sistem.
        val listener = IslandNotificationListener()
        if (!listener.attachAndRegister(app)) Log.e(TAG, "listener registration failed")

        refresh()
        Log.i(TAG, "island started in SystemUI (touchRegion=$touchRegionOk)")
    }

    private fun geti(k: String, def: Int = 0) =
        try { Settings.System.getInt(app.contentResolver, k, def) } catch (_: Throwable) { def }

    private fun battery(): String {
        val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l >= 0) "${l * 100 / s}%" else ""
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "error", t) }
    }

    // ---------------------------------------------------------------- pengaturan

    private fun loadPrefs() {
        val d = app.resources.displayMetrics.density
        view.setPillSize(geti("zest_island_width"), geti("zest_island_height"))
        offX = (geti("zest_island_x").coerceIn(-400, 400) * d).toInt()
        offY = (geti("zest_island_y").coerceIn(-100, 600) * d).toInt()
        view.setOffset(offX.toFloat(), offY.toFloat())
        applyWindow()
    }

    // ------------------------------------------------------------------- logika

    private fun setExpanded(value: Boolean) {
        expanded = value
        expandedByAlert = false
        main.removeCallbacks(collapse)
        if (value) main.postDelayed(collapse, 7000)
        refresh()
    }

    private fun visible(): Boolean {
        val locked = try { km?.isKeyguardLocked == true } catch (_: Throwable) { false }
        val on = try { pm?.isInteractive != false } catch (_: Throwable) { true }
        return !locked && on
    }

    private fun refresh() {
        if (!visible()) {
            expanded = false
            expandedByAlert = false
            seenAlerts.clear()
            main.removeCallbacks(collapse)
            stopTicker()
            detach()
            return
        }
        val items = IslandHub.snapshot()
        // Notifikasi baru langsung membuka island (gaya HyperOS), menutup sendiri setelah 5 detik.
        val alertKeys = items.filter { it.kind == Kind.ALERT }.map { it.key }.toSet()
        val fresh = alertKeys.any { it !in seenAlerts }
        seenAlerts.retainAll(alertKeys)
        seenAlerts.addAll(alertKeys)
        if (fresh) {
            expanded = true
            expandedByAlert = true
            main.removeCallbacks(collapse)
            main.postDelayed(collapse, 5000)
        } else if (alertKeys.isEmpty() && expandedByAlert) {
            expanded = false
            expandedByAlert = false
        }
        if (items.isEmpty()) {
            expanded = false
            stopTicker()
            detach()
            return
        }
        attach()
        view.bind(items, expanded)
        applyWindow()
        val needTick = items.any { it.hasTimer } ||
            (expanded && items.any { it.kind == Kind.MEDIA && it.playing })
        if (needTick) startTicker() else stopTicker()
    }

    // -------------------------------------------------------------------- jendela

    private fun attach() {
        if (attached) return
        applyWindowParams()
        try {
            wm.addView(root, lp)
            attached = true
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
        }
    }

    private fun detach() {
        if (!attached) return
        try { wm.removeViewImmediate(root) } catch (_: Throwable) {}
        attached = false
    }

    private fun applyWindowParams() {
        if (touchRegionOk) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = view.fixedWindowHeight()
        } else {
            // Cadangan bila touchable region gagal dipasang: jendela mengikuti ukuran pil/kartu.
            val (w, h) = if (expanded) view.expandedSize() else view.collapsedWindowSize()
            lp.width = w + 2 * abs(offX)
            lp.height = h + offY.coerceAtLeast(0)
        }
        lp.flags = if (expanded) BASE_FLAGS or FLAG_WATCH_OUTSIDE else BASE_FLAGS
    }

    private fun applyWindow() {
        if (!attached) return
        val oldW = lp.width
        val oldH = lp.height
        val oldF = lp.flags
        applyWindowParams()
        if (oldW != lp.width || oldH != lp.height || oldF != lp.flags) {
            try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
        }
    }

    private fun installTouchRegion(): Boolean = try {
        val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val regionField = infoCls.getField("touchableRegion")
        val setInsets: Method = infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
        val proxy = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls)) { p, m, a ->
            when (m.name) {
                "onComputeInternalInsets" -> {
                    try {
                        val region = regionField.get(a[0]) as Region
                        view.touchBounds(touch)
                        if (touch.isEmpty) region.setEmpty() else region.set(touch)
                        setInsets.invoke(a[0], TOUCHABLE_INSETS_REGION)
                    } catch (_: Throwable) {}
                    null
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                else -> null
            }
        }
        val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerCls)
        add.invoke(root.viewTreeObserver, proxy)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "touchable region unavailable", t)
        false
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.post(ticker)
    }

    private fun stopTicker() {
        ticking = false
        main.removeCallbacks(ticker)
    }

    /** Akar jendela: menerima ketukan di luar kartu (ACTION_OUTSIDE). */
    private class Root(context: Context) : FrameLayout(context) {
        var onOutside: (() -> Unit)? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutside?.invoke()
            return false
        }
    }
}
