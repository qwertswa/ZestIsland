package dev.zest.island

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { a() }
        }
        col.addView(btn(getString(R.string.btn_auto)) { AutoEnable.run(this); update() })
        col.addView(btn(getString(R.string.btn_a11y)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn(getString(R.string.btn_notif)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(btn(getString(R.string.btn_test)) { IslandHub.systemAlert(getString(R.string.test_title), getString(R.string.test_sub)) })
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        update()
    }

    private fun update() {
        fun yn(v: Boolean) = getString(if (v) R.string.st_yes else R.string.st_no)
        fun oo(v: Boolean) = getString(if (v) R.string.st_on else R.string.st_off)
        status.text = getString(R.string.st_perm, yn(AutoEnable.canWrite(this))) +
            "\n" + getString(R.string.st_a11y, oo(AutoEnable.a11yOn(this))) +
            "\n" + getString(R.string.st_notif, oo(AutoEnable.listenerOn(this)))
    }
}
