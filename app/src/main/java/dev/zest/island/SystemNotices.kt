package dev.zest.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager

/** Pemberitahuan kecil gaya iPhone: dering/getar/senyap, mengisi daya, baterai lemah, buka kunci. */
object SystemNotices {
    private var registered = false
    private var lastRinger = -1

    private const val GREEN = 0xFF30D158.toInt()
    private const val RED = 0xFFFF453A.toInt()
    private const val ORANGE = 0xFFFF9F0A.toInt()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val mode = am.ringerMode
                        if (mode == lastRinger) return
                        lastRinger = mode
                        when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> IslandHub.notice("silent", s(c, R.string.notice_silent, "Silent"), RED)
                            AudioManager.RINGER_MODE_VIBRATE -> IslandHub.notice("vibrate", s(c, R.string.notice_vibrate, "Vibrate"), ORANGE)
                            else -> IslandHub.notice("ring", s(c, R.string.notice_ring, "Ring"), -1)
                        }
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        val l = level(c)
                        IslandHub.notice("charge", if (l >= 0) "$l%" else "", GREEN, maxOf(l, 0))
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        val l = level(c)
                        IslandHub.notice("low", if (l >= 0) "$l%" else "", RED, maxOf(l, 0), 3500L)
                    }
                    Intent.ACTION_USER_PRESENT -> unlock()
                }
            } catch (_: Throwable) {}
        }
    }

    /** Teks dari resource ZestIsland; di proses SystemUI konteksnya beda paket, jadi ambil lewat paket sendiri. */
    private fun s(c: Context, id: Int, fallback: String): String = try {
        c.createPackageContext("dev.zest.island", 0).getString(id)
    } catch (_: Throwable) { fallback }

    private var lastUnlock = 0L

    /** Ikon gembok terbuka (gaya iPhone). Aman dipanggil dari beberapa tempat: yang kedua diabaikan. */
    fun unlock() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUnlock < 2000L) return
        lastUnlock = now
        IslandHub.notice("unlock", "", -1, 0, 1600L)
    }

    fun level(c: Context): Int {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l >= 0 && s > 0) l * 100 / s else -1
    }

    fun register(c: Context) {
        if (registered) return
        registered = true
        val f = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        c.registerReceiver(receiver, f)
    }

    fun unregister(c: Context) {
        if (!registered) return
        registered = false
        try { c.unregisterReceiver(receiver) } catch (_: Throwable) {}
    }
}
