package dev.zest.island

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Mengaktifkan layanan lewat Settings.Secure; butuh WRITE_SECURE_SETTINGS (priv-app). */
object AutoEnable {
    private const val A11Y = "dev.zest.island/dev.zest.island.IslandAccessibilityService"
    private const val NL = "dev.zest.island/dev.zest.island.IslandNotificationListener"

    fun canWrite(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun a11yOn(c: Context) = has(c.contentResolver, "enabled_accessibility_services", A11Y)
    fun listenerOn(c: Context) = has(c.contentResolver, "enabled_notification_listeners", NL)

    fun run(c: Context): Boolean {
        if (!canWrite(c)) return false
        if (SysUiMode.active(c)) return true   // mode SystemUI: tidak perlu layanan Aksesibilitas
        return try {
            val cr = c.contentResolver
            add(cr, "enabled_accessibility_services", A11Y)
            Settings.Secure.putInt(cr, "accessibility_enabled", 1)
            add(cr, "enabled_notification_listeners", NL)
            rebindListener(c)
            a11yOn(c) && listenerOn(c)
        } catch (_: SecurityException) { false }
    }

    /** Paksa sistem memuat ulang listener setelah Settings.Secure diubah (tanpa ini kadang baru aktif setelah reboot). */
    fun rebindListener(c: Context) {
        try {
            val cn = android.content.ComponentName(c, IslandNotificationListener::class.java)
            val pm = c.packageManager
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            android.service.notification.NotificationListenerService.requestRebind(cn)
        } catch (_: Throwable) {}
    }

    private fun has(cr: ContentResolver, key: String, comp: String) =
        (Settings.Secure.getString(cr, key) ?: "").split(":").contains(comp)

    private fun add(cr: ContentResolver, key: String, comp: String) {
        val cur = Settings.Secure.getString(cr, key) ?: ""
        if (cur.split(":").contains(comp)) return
        Settings.Secure.putString(cr, key, if (cur.isEmpty()) comp else "$cur:$comp")
    }
}
