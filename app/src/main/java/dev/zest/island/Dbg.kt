package dev.zest.island

/** Log diagnostik: MATI secara bawaan (tidak ada isi notifikasi yang dicatat). Nyalakan: settings put system zest_island_debug 1 */
object Dbg {
    @Volatile var on = false
    inline fun d(msg: () -> String) {
        if (on) android.util.Log.d("ZestIsland", msg())
    }
}
