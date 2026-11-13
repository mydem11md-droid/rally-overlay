package com.mythic.rally

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rally_native", MODE_PRIVATE) }
    private var language = "en"
    override fun attachBaseContext(newBase: Context) {
        val saved = newBase.getSharedPreferences("rally_native", MODE_PRIVATE).getString("language", null)
        val code = saved ?: if (Locale.getDefault().language.equals("ar", true)) "ar" else "en"
        val locale = Locale.forLanguageTag(code)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale); config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); language = prefs.getString("language", null) ?: if (Locale.getDefault().language.equals("ar", true)) "ar" else "en"; render() }
    override fun onResume() { super.onResume(); refreshPermissionStatus() }
    private fun render() {
        val rtl = language == "ar"
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(30),dp(28),dp(30));setBackgroundColor(0xFF1E2A22.toInt());layoutDirection=if(rtl)View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher);contentDescription=getString(R.string.app_name);scaleType=ImageView.ScaleType.FIT_CENTER }
        root.addView(logo,LinearLayout.LayoutParams(dp(96),dp(96)))
        val title = TextView(this).apply { text=getString(R.string.app_name);textSize=21f;setTextColor(-1);setTypeface(typeface,android.graphics.Typeface.BOLD);gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(16)) }
        root.addView(title,LinearLayout.LayoutParams(-1,-2))
        val languageButton = actionButton("ع | EN",0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()) {
            language=if(language=="ar")"en" else "ar";prefs.edit().putString("language",language).apply();syncLauncherAlias()
            runCatching { sendBroadcast(Intent(RallyOverlayService.ACTION_LANGUAGE_CHANGED).setPackage(packageName)) }
            recreate()
        }
        root.addView(languageButton,LinearLayout.LayoutParams(dp(76),dp(34)).apply { bottomMargin=dp(18) })
        val status = TextView(this).apply { tag="permission_status";textSize=12f;setTextColor(0xFFA5C4AA.toInt());gravity=Gravity.CENTER;setPadding(0,0,0,dp(14)) }
        root.addView(status,LinearLayout.LayoutParams(-1,-2))
        root.addView(actionButton(getString(R.string.start),0xFF4CAF50.toInt(),0xFF102014.toInt()) {
            if(!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            else { val i=Intent(this,RallyOverlayService::class.java);if(Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i);Toast.makeText(this,R.string.started,Toast.LENGTH_SHORT).show() }
        },LinearLayout.LayoutParams(-1,dp(46)))
        root.addView(actionButton(getString(R.string.stop),0xFF2A3A2F.toInt(),-1) { stopService(Intent(this,RallyOverlayService::class.java));Toast.makeText(this,R.string.stopped,Toast.LENGTH_SHORT).show() },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(10) })
        setContentView(root);refreshPermissionStatus()
    }
    private fun syncLauncherAlias() {
        val selected = if(language=="ar") ComponentName(this,packageName+".LauncherArabic") else ComponentName(this,packageName+".LauncherEnglish")
        val other = if(language=="ar") ComponentName(this,packageName+".LauncherEnglish") else ComponentName(this,packageName+".LauncherArabic")
        packageManager.setComponentEnabledSetting(selected,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP)
        packageManager.setComponentEnabledSetting(other,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
    }
    private fun refreshPermissionStatus() {
        val content=findViewById<View>(android.R.id.content); val status=content.findViewWithTag<TextView>("permission_status") ?: return
        status.text=if(Settings.canDrawOverlays(this))getString(R.string.ready) else getString(R.string.permission_needed)
    }
    private fun actionButton(label:String,color:Int,fg:Int,onClick:()->Unit)=TextView(this).apply { text=label;textSize=14f;gravity=Gravity.CENTER;setTextColor(fg);setTypeface(typeface,android.graphics.Typeface.BOLD);background=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(9).toFloat()};setOnClickListener{onClick()} }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
}