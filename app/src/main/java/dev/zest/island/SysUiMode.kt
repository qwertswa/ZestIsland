package dev.zest.island

import android.content.Context
import android.provider.Settings

/** Mode SystemUI aktif bila ditandai pada boot yang sama (SysUiHost menulis penanda ini). */
object SysUiMode {
    fun active(c: Context): Boolean = try {
        val cr = c.contentResolver
        val mark = Settings.Secure.getInt(cr, "zest_island_sysui_boot", -1)
        mark >= 0 && mark == Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0)
    } catch (_: Throwable) { false }
}
