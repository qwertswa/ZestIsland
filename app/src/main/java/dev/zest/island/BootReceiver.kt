package dev.zest.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Menyalakan layanan otomatis saat boot / setelah update. Dicoba beberapa kali karena
 * SettingsProvider dan manajer Aksesibilitas kadang belum siap tepat saat BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val h = Handler(Looper.getMainLooper())
        val delays = longArrayOf(0L, 2500L, 6000L, 9000L)
        for ((i, d) in delays.withIndex()) {
            h.postDelayed({
                try {
                    val ok = AutoEnable.run(app)
                    Dbg.d { "AutoEnable try=$i ok=$ok perm=${AutoEnable.canWrite(app)}" }
                } catch (_: Throwable) {
                }
                if (i == delays.lastIndex) pending.finish()
            }, d)
        }
    }
}
