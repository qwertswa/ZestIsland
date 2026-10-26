package dev.zest.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper

/** Tombol aksi dari notifikasi (mis. Berhenti/Jeda pada stopwatch, Tolak/Akhiri pada telepon). */
class IslandAction(val title: String, val intent: PendingIntent)

enum class Kind(val priority: Int) { MEDIA(1), TIMER(2), CALL(3), ALERT(4), NOTICE(5) }

class IslandItem(
    val key: String,
    var kind: Kind,
    var title: String,
    var subtitle: String = "",
) {
    val seq: Long = IslandHub.nextSeq()
    var art: Bitmap? = null
    var icon: Drawable? = null
    var whenMs: Long = 0L        // dasar chronometer (jam dinding, ms)
    var countDown: Boolean = false
    var playing: Boolean = false
    var controller: MediaController? = null
    var tap: PendingIntent? = null          // dibuka saat kartu notifikasi diketuk
    var durationMs: Long = 0L
    var positionMs: Long = 0L
    var posTime: Long = 0L                  // elapsedRealtime saat positionMs dicatat
    var speed: Float = 1f
    var glyph: String = ""                  // NOTICE: ring/silent/vibrate/charge/low/unlock
    var accent: Int = -1                    // NOTICE: warna ikon
    var level: Int = 0                      // NOTICE: persen baterai
    var actions: List<IslandAction> = emptyList()
    var shownAt: Long = 0L                  // NOTICE: uptimeMillis saat muncul (untuk animasi)
    var pkg: String = ""                    // paket aplikasi sumber (untuk membuka aplikasinya)
    var frozenMs: Long = -1L                // >=0: timer/stopwatch sedang berhenti di nilai ini
    val hasTimer: Boolean get() = whenMs > 0L || frozenMs >= 0L
}

/** Penampung status bersama; semua akses dialihkan ke main thread. */
object IslandHub {
    private const val MAX_ALERTS = 3

    private val main = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<String, IslandItem>()
    private val listeners = ArrayList<() -> Unit>()
    private val drops = HashMap<String, Runnable>()
    private var seqCounter = 0L

    fun nextSeq(): Long = ++seqCounter

    /** Notifikasi terbaru paling atas, maksimal 3; lalu telepon, timer, media. */
    /** Aplikasi yang sedang di depan; item dari aplikasi ini disembunyikan (sudah dibuka). */
    @Volatile var fgPkg: String = ""

    fun snapshot(): List<IslandItem> {
        val fg = fgPkg
        val all = items.values.filter { fg.isEmpty() || it.pkg != fg }.sortedWith(
            compareByDescending<IslandItem> { it.kind.priority }.thenByDescending { it.seq }
        )
        val notices = all.filter { it.kind == Kind.NOTICE }.take(1)
        val alerts = all.filter { it.kind == Kind.ALERT }.take(MAX_ALERTS)
        return notices + alerts + all.filter { it.kind != Kind.ALERT && it.kind != Kind.NOTICE }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun put(item: IslandItem) = main.post { items[item.key] = item; fire() }
    fun remove(key: String) = main.post { if (items.remove(key) != null) fire() }

    /** Kartu notifikasi: tiap notifikasi punya key sendiri dan hilang sendiri setelah [ms]. */
    fun alert(
        key: String,
        title: String,
        subtitle: String = "",
        icon: Drawable? = null,
        tap: PendingIntent? = null,
        ms: Long = 5000L,
        pkg: String = "",
    ) {
        main.post {
            val item = IslandItem("alert:$key", Kind.ALERT, title, subtitle)
            item.icon = icon
            item.tap = tap
            item.pkg = pkg
            items[item.key] = item
            drops.remove(item.key)?.let { main.removeCallbacks(it) }
            val r = Runnable {
                drops.remove(item.key)
                if (items.remove(item.key) != null) fire()
            }
            drops[item.key] = r
            main.postDelayed(r, ms)
            fire()
        }
    }

    /** Pemberitahuan kecil gaya iPhone: pil melebar sedikit, tanpa kartu. Satu slot, yang baru menggantikan. */
    fun notice(glyph: String, text: String, accent: Int = -1, level: Int = 0, ms: Long = 2600L) {
        main.post {
            val item = IslandItem("notice", Kind.NOTICE, text, "")
            item.glyph = glyph
            item.accent = accent
            item.level = level
            item.shownAt = android.os.SystemClock.uptimeMillis()
            items[item.key] = item
            drops.remove(item.key)?.let { main.removeCallbacks(it) }
            val r = Runnable {
                drops.remove(item.key)
                if (items.remove(item.key) != null) fire()
            }
            drops[item.key] = r
            main.postDelayed(r, ms)
            fire()
        }
    }

    /** Peringatan sistem singkat (pengisian daya dsb). */
    fun systemAlert(title: String, subtitle: String = "", ms: Long = 3500L) =
        alert("sys:$title", title, subtitle, null, null, ms)

    private fun fire() { ArrayList(listeners).forEach { it() } }
}
