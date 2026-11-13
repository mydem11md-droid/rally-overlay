package com.mythic.rally

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFF1E2A22.toInt())
        }
        val start = Button(this).apply {
            text = getString(R.string.start)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val i = Intent(this@MainActivity, RallyOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                }
            }
        }
        val stop = Button(this).apply {
            text = getString(R.string.stop)
            setOnClickListener { stopService(Intent(this@MainActivity, RallyOverlayService::class.java)) }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2))
        root.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        setContentView(root)
    }
}
