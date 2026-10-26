package dev.zest.island

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews

/** Membaca notifikasi (telepon, timer/stopwatch, notifikasi biasa) dan sesi media. */
class IslandNotificationListener : NotificationListenerService() {
    companion object {
        /** true bila berjalan di dalam proses SystemUI (mode tertanam). */
        @Volatile var inSystemUi = false
        private const val OWN_PACKAGE = "dev.zest.island"
        private const val PAUSED_HIDE_MS = 60_000L
    }

    /** Dipakai host SystemUI: pasang konteks dasar lalu daftar sebagai layanan sistem. */
    fun attachAndRegister(app: android.content.Context): Boolean {
        return try {
            attachBaseContext(app)
            val m = NotificationListenerService::class.java.getMethod(
                "registerAsSystemService",
                android.content.Context::class.java,
                ComponentName::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(this, app, ComponentName(app.packageName, IslandNotificationListener::class.java.name), -1)
            true
        } catch (_: Throwable) { false }
    }

    private val controllers = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private var msm: MediaSessionManager? = null
    private val clockLast = HashMap<String, Long>()

    private class ClockState(var running: Boolean, var base: Long, var frozen: Long, var sig: String, var text: Long)
    private val clockStates = HashMap<String, ClockState>()

    /**
     * Stopwatch/timer tampilan kustom: jalan/berhenti ditebak dari perubahan tombol aksi notifikasi
     * (mis. Berhenti -> Lanjutkan) dan dari teks waktu yang berubah.
     */
    private fun clockState(key: String, n: Notification, text: Long, item: IslandItem) {
        val now = System.currentTimeMillis()
        val sig = (n.actions ?: emptyArray()).joinToString("|") { a ->
            val rid = try { a.getIcon()?.resId ?: 0 } catch (_: Throwable) { 0 }
            a.title.toString() + "#" + rid
        }
        var st = clockStates[key]
        if (st == null) {
            val w = n.`when`
            val base = when {
                text >= 0 -> now - text
                w in 1 until (now - 2000L) && now - w < 86_400_000L -> w
                else -> now
            }
            st = ClockState(true, base, 0L, sig, text)
            clockStates[key] = st
        } else {
            if (sig != st.sig) {
                if (st.running) { st.frozen = if (text >= 0) text else now - st.base; st.running = false }
                else { st.base = now - st.frozen; st.running = true }
                st.sig = sig
            } else if (text >= 0 && text != st.text) {
                st.running = true
                st.base = now - text
            }
            st.text = text
        }
        Dbg.d { "clockState running=${st.running} text=$text sig=$sig" }
        if (st.running) item.whenMs = st.base else item.frozenMs = st.frozen
    }
    private val mainH = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRemovers = HashMap<String, Runnable>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { syncSessions(it) }

    override fun onListenerConnected() {
        val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        // Di SystemUI cukup komponen null (SystemUI punya MEDIA_CONTENT_CONTROL).
        val cn = if (inSystemUi) null else ComponentName(this, IslandNotificationListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn)
            syncSessions(m.getActiveSessions(cn))
        } catch (_: Throwable) {}
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        for ((k, v) in controllers) {
            try { v.first.unregisterCallback(v.second) } catch (_: Throwable) {}
            IslandHub.remove(k)
        }
        controllers.clear()
    }

    // ---- Notifikasi ----

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == OWN_PACKAGE) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return   // ditangani sesi media

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = appLabel(sbn.packageName)
        val icon = try { n.smallIcon?.loadDrawable(this) } catch (_: Throwable) { null }
        var chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        var chronoWhen = n.`when`
        var chronoDown = ex.getBoolean("android.chronometerCountDown", false)
        var frozenFromView = -1L
        val isCall = n.category == Notification.CATEGORY_CALL
        val ongoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        // Stopwatch/timer Samsung Jam sering memakai tampilan kustom tanpa chronometer standar.
        val isClock = ongoing && (
            sbn.packageName.contains("clock", true) ||
                n.category == "stopwatch"
            )
        Dbg.d { "notif pkg=${sbn.packageName} cat=${n.category} flags=${n.flags} chrono=$chrono clock=$isClock" }
        if (!chrono && !isCall && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            // Stopwatch/timer aplikasi jam sering memakai tampilan kustom berisi Chronometer.
            scanChronometer(sbn)?.let { cs ->
                if (cs.started) {
                    chrono = true
                    chronoWhen = cs.whenMs
                    chronoDown = cs.down
                } else {
                    // Chronometer ada tapi dihentikan -> tampilkan nilai yang membeku.
                    frozenFromView = parseTime(cs.text)
                }
            }
        }
        var clockText: Long = -1L
        if (!chrono && !isCall && isClock && frozenFromView < 0) {
            clockText = scanTexts(sbn).asSequence().map { parseTime(it) }.firstOrNull { it >= 0 } ?: -1L
        }

        // Hanya aplikasi yang mendukung "Ongoing Activity" One UI 8 (chip Live Notification / Now Bar),
        // atau Android 16 promoted-ongoing. Notifikasi biasa tidak ditampilkan (kecuali opsi dinyalakan).
        val ongoingAct = try {
            ex.containsKey("android.ongoingActivityNoti.style") || ex.getBoolean("android.requestPromotedOngoing", false)
        } catch (_: Throwable) { false }
        val primaryInfo = if (ongoingAct) cs(ex, "android.ongoingActivityNoti.primaryInfo") else ""
        val secondaryInfo = if (ongoingAct) cs(ex, "android.ongoingActivityNoti.secondaryInfo") else ""
        when {
            chrono || isCall || isClock || ongoingAct -> {
                val item = IslandItem(
                    sbn.key,
                    if (isCall) Kind.CALL else Kind.TIMER,
                    primaryInfo.ifEmpty { title.ifEmpty { app } },
                    secondaryInfo.ifEmpty { text.ifEmpty { app } }
                )
                item.icon = icon
                item.tap = n.contentIntent
                item.pkg = sbn.packageName
                item.actions = (n.actions ?: emptyArray()).mapNotNull { a ->
                    val pi = a.actionIntent
                    val t = a.title?.toString().orEmpty()
                    if (pi == null || t.isEmpty() || a.remoteInputs != null) null else IslandAction(t, pi)
                }.take(3)
                if (chrono) {
                    item.whenMs = chronoWhen
                    item.countDown = chronoDown
                } else if (frozenFromView >= 0) {
                    item.frozenMs = frozenFromView
                } else if (isClock) {
                    clockState(sbn.key, n, clockText, item)
                }
                IslandHub.put(item)
            }
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 -> Unit   // abaikan layanan latar
            allNotifs() && (title.isNotEmpty() || text.isNotEmpty()) ->
                IslandHub.alert(sbn.key, title.ifEmpty { app }, text.ifEmpty { app }, icon, n.contentIntent, 5000L, sbn.packageName)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        clockLast.remove(sbn.key)
        clockStates.remove(sbn.key)
        IslandHub.remove(sbn.key)
        IslandHub.remove("alert:" + sbn.key)
    }

    private class ClockScan(val whenMs: Long, val down: Boolean, val started: Boolean, val text: String)

    private fun applied(sbn: StatusBarNotification): List<View> {
        val n = sbn.notification
        val views = listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView)
        if (views.isEmpty()) return emptyList()
        val ctx = try { createPackageContext(sbn.packageName, 0) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<View>()
        for (rv in views) {
            try { out.add(rv.apply(ctx, FrameLayout(ctx))) } catch (_: Throwable) {}
        }
        return out
    }

    /** Cari Chronometer di RemoteViews notifikasi (berjalan atau berhenti). */
    private fun scanChronometer(sbn: StatusBarNotification): ClockScan? {
        for (root in applied(sbn)) {
            val ch = findChronometer(root) ?: continue
            val started = try {
                val f = Chronometer::class.java.getDeclaredField("mStarted")
                f.isAccessible = true
                f.getBoolean(ch)
            } catch (_: Throwable) { true }
            val wall = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() + ch.base
            return ClockScan(wall, ch.isCountDown, started, ch.text?.toString().orEmpty())
        }
        return null
    }

    private fun scanTexts(sbn: StatusBarNotification): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView) v.text?.toString()?.let { if (it.isNotEmpty()) out.add(it) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        for (r in applied(sbn)) walk(r)
        Dbg.d { "clock texts=$out" }
        return out
    }

    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?""")

    /** "1:02:03", "02:03.45" -> milidetik; -1 bila bukan format waktu. */
    private fun parseTime(t: String): Long {
        val m = timeRe.find(t) ?: return -1L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mi = m.groupValues[2].toLongOrNull() ?: return -1L
        val s = m.groupValues[3].toLongOrNull() ?: return -1L
        val fr = m.groupValues[4]
        val ms = if (fr.isEmpty()) 0L else (fr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
        return ((h * 60 + mi) * 60 + s) * 1000 + ms
    }

    private fun findChronometer(v: View): Chronometer? {
        if (v is Chronometer) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findChronometer(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun cs(ex: android.os.Bundle, key: String): String = try {
        ex.getCharSequence(key)?.toString().orEmpty()
    } catch (_: Throwable) { "" }

    /** Opsional: settings put system zest_island_all_notifs 1 -> semua notifikasi tampil di island. */
    private fun allNotifs(): Boolean = try {
        android.provider.Settings.System.getInt(contentResolver, "zest_island_all_notifs", 0) == 1
    } catch (_: Throwable) { false }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    // ---- Media ----

    private fun syncSessions(list: List<MediaController>?) {
        val live = HashSet<String>()
        list?.forEach { c ->
            val key = "media:" + c.packageName
            live.add(key)
            if (!controllers.containsKey(key)) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia(c)
                    override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia(c)
                    override fun onSessionDestroyed() {
                        controllers.remove(key)
                        IslandHub.remove(key)
                    }
                }
                c.registerCallback(cb)
                controllers[key] = c to cb
            }
            updateMedia(c)
        }
        val stale = controllers.keys.filter { it !in live }
        for (k in stale) {
            controllers.remove(k)?.let { try { it.first.unregisterCallback(it.second) } catch (_: Throwable) {} }
            IslandHub.remove(k)
        }
    }

    private fun updateMedia(c: MediaController) {
        val key = "media:" + c.packageName
        val state = c.playbackState?.state
        if (state == null || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_ERROR
        ) {
            IslandHub.remove(key)
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifEmpty { appLabel(c.packageName) }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val item = IslandItem(key, Kind.MEDIA, title, artist)
        item.art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        item.playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        item.controller = c
        item.pkg = c.packageName
        item.durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        item.positionMs = c.playbackState?.position ?: 0L
        item.posTime = c.playbackState?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime()
        item.speed = c.playbackState?.playbackSpeed ?: 1f
        IslandHub.put(item)
        // Musik yang di-pause lama tidak terus menempel di island (tetap kembali saat diputar lagi).
        idleRemovers.remove(key)?.let { mainH.removeCallbacks(it) }
        if (state == PlaybackState.STATE_PAUSED) {
            val r = Runnable {
                idleRemovers.remove(key)
                IslandHub.remove(key)
            }
            idleRemovers[key] = r
            mainH.postDelayed(r, PAUSED_HIDE_MS)
        }
    }
}
