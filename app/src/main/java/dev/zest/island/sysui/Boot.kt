package dev.zest.island.sysui

import android.content.Context
import android.util.Log

/**
 * Titik masuk saat APK ini dimuat ke dalam proses SystemUI (lewat ZestBoot.smali).
 * Dipanggil lewat refleksi: dev.zest.island.sysui.Boot.start(Context).
 */
object Boot {
    private var host: SysUiHost? = null

    @JvmStatic
    fun start(app: Context) {
        if (host != null) return
        try {
            val h = SysUiHost(app)
            host = h
            h.start()
        } catch (t: Throwable) {
            Log.e("ZestIsland", "boot failed", t)
        }
    }
}
