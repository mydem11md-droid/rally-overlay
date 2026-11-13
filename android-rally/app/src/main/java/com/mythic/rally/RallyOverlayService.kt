package com.mythic.rally

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.provider.Settings
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class RallyOverlayService : Service() {
    private val prefs by lazy { getSharedPreferences("rally_native", MODE_PRIVATE) }
    private lateinit var uiContext: Context
    private var language = "en"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var menuOpen = false
    private var resultsMode = false
    private var settingsMode = false
    private var confirming = false
    private var focusable = false
    private var target = ""
    private var targetHour = ""
    private var calculationMode = "now"
    private var prepSeconds = 10
    private var vibrationEnabled = true
    private var targetHourInput: EditText? = null
    private var resultCountdowns = mutableMapOf<Int,TextView>()
    private var resultRowsViews = mutableMapOf<Int,View>()
    private var resultZeroAt = mutableMapOf<Int,Long>()
    private var vibratedIds = mutableSetOf<Int>()
    private var people = mutableListOf<Person>()
    private var results = mutableListOf<ResultRow>()
    private var errors = mutableListOf<String>()
    private var nextId = 1
    private var bubbleX = 24
    private var bubbleY = 180
    private var menuView: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var targetInput: EditText? = null
    private var imeHeightPx = 0
    private var imeWasVisible = false
    private var statusInsetPx = 0
    private var navInsetPx = 0
    private var focusedField: EditText? = null
    private var miniEditor: LinearLayout? = null
    private var miniEditorTitle: TextView? = null
    private var lastScrollMode = "idle"
    private var miniEditorValue: TextView? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private class CappedScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx.coerceAtLeast(0), MeasureSpec.AT_MOST))
        }
    }
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()
    private fun ar() = language == "ar"
    private fun t(a: String, e: String) = if (ar()) a else e
    private fun localizedContext(): Context {
        language = prefs.getString("language", null) ?: if (Locale.getDefault().language.equals("ar", true)) "ar" else "en"
        val locale = Locale.forLanguageTag(language)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return createConfigurationContext(config)
    }
    private fun changeLanguage() {
        language = if (language == "ar") "en" else "ar"
        prefs.edit().putString("language", language).apply()
        syncLauncherAlias()
        uiContext = localizedContext()
        persist()
        if (root != null) { if (menuOpen) showMenu() else buildCollapsed(); startForeground(27, notification()) }
    }
    private fun syncLauncherAlias() {
        val selected=if(language=="ar")ComponentName(this,packageName+".LauncherArabic")else ComponentName(this,packageName+".LauncherEnglish")
        val other=if(language=="ar")ComponentName(this,packageName+".LauncherEnglish")else ComponentName(this,packageName+".LauncherArabic")
        packageManager.setComponentEnabledSetting(selected,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP)
        packageManager.setComponentEnabledSetting(other,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
    }
    private fun direction(view: View) {
        view.layoutDirection = if (ar()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }
    private fun bg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(uiContext).apply { text=value; textSize=size; setTextColor(color); gravity=Gravity.CENTER_VERTICAL; direction(this)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun button(value: String, color: Int, fg: Int, action: () -> Unit) =
        TextView(uiContext).apply { text=value; textSize=13f; gravity=Gravity.CENTER; setTextColor(fg); direction(this)
            background=bg(color,9f); setPadding(dp(10),dp(7),dp(10),dp(7)); setOnClickListener { action() } }
    private fun fitControl(v: TextView) = v.apply { includeFontPadding=false; isSingleLine=true; gravity=Gravity.CENTER; minHeight=dp(40); setPadding(paddingLeft,dp(5),paddingRight,dp(5)) }
    private data class Person(val id:Int,var name:String,var duration:String,var kind:String="solo",var rallyMinutes:Int=5)
    private data class ResultRow(val id:Int,val name:String,val isRally:Boolean,val rallySeconds:Int,val marchSeconds:Int,val actionAt:Long,val departAt:Long,val arrivalAt:Long)
    private val localeReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uiContext=localizedContext()
            if(root!=null){if(menuOpen)showMenu()else buildCollapsed();startForeground(27,notification())}
        }
    }
    private val clockTick = object: Runnable { override fun run() { updateClock(); updateResultCountdowns(); handler.postDelayed(this,1000) } }

    override fun onCreate() {
        super.onCreate()
        uiContext=localizedContext()
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val filter=IntentFilter(ACTION_LANGUAGE_CHANGED)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(localeReceiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(localeReceiver,filter)
        restore()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        startForeground(27,notification())
        if(root==null) createOverlay()
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null

    private fun restore() {
        target=prefs.getString("target","") ?: ""
        targetHour=prefs.getString("target_hour","") ?: ""
        calculationMode=prefs.getString("calculation_mode","now") ?: "now"
        prepSeconds=prefs.getInt("prep_seconds",10)
        vibrationEnabled=prefs.getBoolean("vibration_enabled",true)
        bubbleX=prefs.getInt("x",24); bubbleY=prefs.getInt("y",180)
        menuOpen=prefs.getBoolean("open",false); resultsMode=prefs.getBoolean("results",false); settingsMode=prefs.getBoolean("settings",false)
        nextId=prefs.getInt("next",1)
        runCatching {
            val a=JSONArray(prefs.getString("people","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);people.add(Person(o.getInt("id"),o.optString("name"),o.optString("duration"),o.optString("kind","solo"),o.optInt("rally_minutes",5)))}
        }
        if(people.isEmpty()) people.add(Person(nextId++,"",""))
        runCatching {
            val a=JSONArray(prefs.getString("result_rows","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);results.add(ResultRow(o.getInt("id"),o.optString("name"),o.optBoolean("is_rally"),o.optInt("rally_seconds"),o.optInt("march_seconds"),o.optLong("action_at"),o.optLong("depart_at"),o.optLong("arrival_at")))}
        }
    }
    private fun persist() {
        val a=JSONArray(); people.forEach { a.put(JSONObject().put("id",it.id).put("name",it.name).put("duration",it.duration).put("kind",it.kind).put("rally_minutes",it.rallyMinutes)) }
        val r=JSONArray(); results.forEach { r.put(JSONObject().put("id",it.id).put("name",it.name).put("is_rally",it.isRally).put("rally_seconds",it.rallySeconds).put("march_seconds",it.marchSeconds).put("action_at",it.actionAt).put("depart_at",it.departAt).put("arrival_at",it.arrivalAt)) }
        prefs.edit().putString("target",target).putString("target_hour",targetHour).putString("calculation_mode",calculationMode).putInt("prep_seconds",prepSeconds).putBoolean("vibration_enabled",vibrationEnabled).putString("people",a.toString()).putString("result_rows",r.toString())
            .putInt("next",nextId).putInt("x",bubbleX).putInt("y",bubbleY)
            .putBoolean("open",menuOpen).putBoolean("results",resultsMode).putBoolean("settings",settingsMode).apply()
    }
    private fun notification():Notification {
        val channel="rally_native"
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,t("أداة تنسيق الحشود","Rally Coordinator"),NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,8,Intent(this,RallyOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,channel) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_rally).setContentTitle(t("أداة تنسيق الحشود","Rally Coordinator"))
            .setContentText(t("الأداة العائمة تعمل","Floating tool is active")).setOngoing(true)
            .addAction(Notification.Action.Builder(null,t("إيقاف","Stop"),stop).build()).build()
    }
    private fun createOverlay() {
        statusInsetPx=dp(24); navInsetPx=dp(24)
        val screenW=(resources.displayMetrics.widthPixels/density).toInt()
        val screenH=(resources.displayMetrics.heightPixels/density).toInt()
        bubbleX=bubbleX.coerceIn(0,(screenW-104).coerceAtLeast(0)); bubbleY=bubbleY.coerceIn(((statusInsetPx+dp(8))/density).roundToInt(),(screenH-55).coerceAtLeast(((statusInsetPx+dp(50))/density).roundToInt()))
        val frame=FrameLayout(uiContext).apply { clipChildren=true; clipToPadding=true; direction(this) }
        root=frame
        val p=WindowManager.LayoutParams(dp(58),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=dp(bubbleX); y=dp(bubbleY)
                softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN }
        params=p
        wm.addView(frame,p)
        buildCollapsed()
        installInsetsTracking(frame)
        if(menuOpen) showMenu()
        handler.post(clockTick)
    }
    private fun iconCircle():View = object:View(uiContext) {
        private val paint=Paint(3).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeWidth=dp(2).toFloat(); strokeCap=Paint.Cap.ROUND }
        override fun onDraw(c:Canvas) { super.onDraw(c); val x=width/2f;val y=height/2f
            c.drawCircle(x,y,dp(9).toFloat(),paint);c.drawLine(x,y,x,y-dp(5),paint);c.drawLine(x,y,x+dp(4),y+dp(3),paint) }
    }.apply { contentDescription=t("الوقت","Clock") }
    private fun addPinnedControls(f: FrameLayout) {
        val capsule=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;background=bg(0xFF1E2A22.toInt(),10f);setPadding(dp(12),dp(5),dp(12),dp(5));minimumWidth=dp(104);direction(this) }
        val clock=TextView(uiContext).apply { tag="utc_time";text=SimpleDateFormat("HH:mm:ss",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(System.currentTimeMillis()));textSize=15f;setTextColor(Color.WHITE);setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);fontFeatureSettings="tnum";includeFontPadding=false;isSingleLine=true;ellipsize=null;gravity=Gravity.CENTER;textDirection=View.TEXT_DIRECTION_LTR;textAlignment=View.TEXT_ALIGNMENT_CENTER }
        val utc=TextView(uiContext).apply { text="UTC";textSize=11f;setTextColor(0xFFA5C4AA.toInt());includeFontPadding=false;isSingleLine=true;ellipsize=null;gravity=Gravity.CENTER;textDirection=View.TEXT_DIRECTION_LTR }
        capsule.addView(clock);capsule.addView(utc,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(4)})
        f.addView(capsule,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val orb=FrameLayout(uiContext).apply { background=bg(0xFF2E7D32.toInt(),50f);addView(iconCircle(),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER)) }
        f.addView(orb,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(34)})
        val close=object:View(uiContext) {
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas) { super.onDraw(canvas);val cx=width/2f;val cy=height/2f;paint.style=Paint.Style.FILL;paint.color=0xFF1E2A22.toInt();canvas.drawCircle(cx,cy,dp(10).toFloat(),paint);paint.color=if(isPressed)0xFFE57373.toInt() else 0xFF2A3A2F.toInt();canvas.drawCircle(cx,cy,dp(8).toFloat(),paint);paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f).toFloat();paint.strokeCap=Paint.Cap.ROUND;val d=dp(3).toFloat();canvas.drawLine(cx-d,cy-d,cx+d,cy+d,paint);canvas.drawLine(cx+d,cy-d,cx-d,cy+d,paint) }
            override fun onTouchEvent(event:MotionEvent):Boolean { when(event.actionMasked){MotionEvent.ACTION_DOWN->{isPressed=true;invalidate();return true};MotionEvent.ACTION_UP->{isPressed=false;invalidate();performClick();return true};MotionEvent.ACTION_CANCEL->{isPressed=false;invalidate();return true}};return true }
            override fun performClick():Boolean { super.performClick();showConfirm();return true }
        }
        f.addView(close,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=dp(6);topMargin=dp(16)})
        if(menuOpen) {
            val width=dp(280); val sw=resources.displayMetrics.widthPixels
            val desired=dp(bubbleX+52)
            val expandedLeft=(desired-width/2).coerceIn(0,(sw-width).coerceAtLeast(0))
            val shift=(desired-(expandedLeft+width/2)).toFloat()
            capsule.translationX=shift; orb.translationX=shift; close.translationX=shift
        }
        orb.setOnClickListener { menuOpen=!menuOpen;persist();if(menuOpen)showMenu()else collapse() }
        val drag=View.OnTouchListener { _,ev -> when(ev.actionMasked){MotionEvent.ACTION_DOWN->{downX=ev.rawX;downY=ev.rawY;startX=bubbleX;startY=bubbleY;dragged=false;true};MotionEvent.ACTION_MOVE->{val dx=((ev.rawX-downX)/density).roundToInt();val dy=((ev.rawY-downY)/density).roundToInt();if(abs(dx)+abs(dy)>5)dragged=true;if(dragged){bubbleX=(startX+dx).coerceIn(0,(resources.displayMetrics.widthPixels/density).roundToInt()-104);bubbleY=(startY+dy).coerceIn(((statusInsetPx+dp(50))/density).roundToInt(),(resources.displayMetrics.heightPixels/density).roundToInt()-55);applyLayout();persist()};true};MotionEvent.ACTION_UP->{if(!dragged){if(menuOpen)collapse()else{menuOpen=true;persist();showMenu()}};true};else->true} }
        orb.setOnTouchListener(drag);f.setOnTouchListener(drag)
    }
    private fun buildCollapsed() { if (focusable) { focusedField?.clearFocus(); setFocus(false) }; val f=root?:return;f.removeAllViews();f.clipChildren=true;f.clipToPadding=true;addPinnedControls(f);updateClock();applyLayout() }
    private var downX=0f;private var downY=0f;private var startX=0;private var startY=0;private var dragged=false
    private fun updateClock() {
        val c=root?.findViewWithTag<TextView>("utc_time")?:return
        val formatter=SimpleDateFormat("HH:mm:ss",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        c.text=formatter.format(Date())
    }
    private fun availableOverlayHeight(): Int {
        val full = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels
        return (full - max(statusInsetPx,dp(bubbleY)) - navInsetPx - imeHeightPx - dp(16)).coerceAtLeast(dp(120))
    }
    private fun installInsetsTracking(view: View) {
        statusInsetPx=dp(24); navInsetPx=dp(24)
        view.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                statusInsetPx=bars.top
                navInsetPx=insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                imeHeightPx=insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                val cutoutTop=if(Build.VERSION.SDK_INT>=28)insets.displayCutout?.safeInsetTop ?: 0 else 0
                statusInsetPx=max(insets.systemWindowInsetTop,cutoutTop)
                navInsetPx=insets.systemWindowInsetBottom
            }
            updateImeFocusState()
            resizeForInsets()
            insets
        }
        val listener=ViewTreeObserver.OnGlobalLayoutListener {
            val rect=Rect(); view.getWindowVisibleDisplayFrame(rect)
            val fallback=(resources.displayMetrics.heightPixels-rect.bottom-navInsetPx).coerceAtLeast(0)
            val next=if(fallback>dp(80))fallback else 0
            if(next!=imeHeightPx){imeHeightPx=next;updateImeFocusState();resizeForInsets()}
        }
        globalLayoutListener=listener
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.requestApplyInsets()
    }
    private fun updateImeFocusState() {
        val visible = imeHeightPx > dp(80)
        if (visible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            imeWasVisible = false
            if (focusable) {
                focusedField?.clearFocus()
                setFocus(false)
            }
        }
    }
    private fun resizeForInsets() { if(root!=null){applyLayout();focusedField?.post{ensureFocusedFieldVisible(focusedField!!)}} }
    private fun ensureFocusedFieldVisible(field: EditText) {
        val sc=scroll?:return
        val rect=Rect();field.getDrawingRect(rect);sc.offsetDescendantRectToMyCoords(field,rect)
        val visibleBottom=sc.scrollY+sc.height-dp(12)
        if(rect.bottom>visibleBottom)sc.smoothScrollTo(0,(rect.bottom-sc.height+dp(18)).coerceAtLeast(0))
        val needsMini=imeHeightPx>dp(80) && (sc.height<dp(90) || rect.height()>sc.height)
        miniEditorTitle?.text=field.contentDescription?.toString() ?: field.hint?.toString().orEmpty()
        miniEditorValue?.text=field.text?.toString().orEmpty()
        miniEditor?.visibility=if(needsMini)View.VISIBLE else View.GONE
        if(needsMini)lastScrollMode="mini-editor"
    }
    private fun applyLayout() {
        val r=root?:return;val p=params?:return;val sw=resources.displayMetrics.widthPixels
        val width=if(menuOpen&&menuView!=null)dp(280) else dp(104)
        p.width=width;p.height=WindowManager.LayoutParams.WRAP_CONTENT
        p.x=if(menuOpen&&menuView!=null)(dp(bubbleX+52)-width/2).coerceIn(0,(sw-width).coerceAtLeast(0))else dp(bubbleX)
        p.y=dp(bubbleY.coerceAtLeast(((statusInsetPx+dp(50))/density).roundToInt()))
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        val cap=(availableOverlayHeight()-dp(88+34+17+38+12)).coerceAtLeast(dp(72))
        (scroll as? CappedScrollView)?.maxHeightPx=if(resultsMode)(availableOverlayHeight()-dp(88+34+17)).coerceAtLeast(dp(72))else cap
        r.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(availableOverlayHeight(),View.MeasureSpec.AT_MOST))
        runCatching {
            wm.updateViewLayout(r,p)
            val mode=if((scroll?.getChildAt(0)?.height?:0)>(scroll?.height?:0))"scroll" else "content"
            Log.d("RallyLayout","windowHeight=${r.measuredHeight}px availableHeight=${availableOverlayHeight()}px keyboardHeight=${imeHeightPx}px scrollMode=$mode")
        }.onFailure{Log.e("RallyLayout","update failed",it)}
    }
    private fun collapse(){
        if (focusable) { focusedField?.clearFocus(); setFocus(false) }
        val panel=menuView
        if(panel!=null) {
            panel.animate().alpha(0f).setDuration(150).withEndAction {
                menuOpen=false;persist();menuView=null;buildCollapsed();applyLayout()
            }.start()
        } else { menuOpen=false;persist();buildCollapsed();applyLayout() }
    }
    private fun showMenu() {
        val f=root?:return;f.removeAllViews();f.clipChildren=true;f.clipToPadding=true;addPinnedControls(f)
        val panel=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;background=bg(0xFF1E2A22.toInt(),12f);setPadding(dp(10),dp(8),dp(10),dp(9));clipChildren=true;clipToPadding=true;direction(this)}
        menuView=panel
        val header=LinearLayout(uiContext).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL;direction(this)}
        header.addView(text(if(settingsMode)t("الإعدادات","Settings")else if(resultsMode)t("نتائج التنسيق","Rally results")else t("أداة تنسيق الحشود","Rally Coordinator"),14f,Color.WHITE,true),LinearLayout.LayoutParams(0,dp(34),1f))
        header.addView(button("ع | EN",0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){changeLanguage()},LinearLayout.LayoutParams(dp(58),dp(32)))
        header.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
        header.addView(button("⚙",0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){settingsMode=!settingsMode;if(settingsMode)resultsMode=false;persist();renderMenu()},LinearLayout.LayoutParams(dp(34),dp(32)))
        panel.addView(header)
        miniEditor=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;background=bg(0xFF2A3A2F.toInt(),9f);setPadding(dp(9),dp(5),dp(9),dp(5))}
        miniEditorTitle=text("",10f,0xFFA5C4AA.toInt(),true)
        miniEditorValue=text("",18f,Color.WHITE,true)
        miniEditor?.addView(miniEditorTitle);miniEditor?.addView(miniEditorValue)
        panel.addView(miniEditor,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
        val sc=CappedScrollView(uiContext).apply{isFillViewport=false;clipChildren=true;clipToPadding=true;direction(this)};scroll=sc
        val content=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;clipChildren=true;clipToPadding=true;direction(this)}
        sc.addView(content);panel.addView(sc,LinearLayout.LayoutParams(-1,-2))
        if(resultsMode)buildResults(content)else buildEditor(content)
        if(!resultsMode)panel.addView(button(t("حساب","Calculate"),0xFF4CAF50.toInt(),0xFF102014.toInt()){calculate()},LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(12)})
        panel.alpha=0f
        f.addView(panel,FrameLayout.LayoutParams(dp(280),-2).apply{topMargin=dp(88)})
        applyLayout()
        panel.animate().alpha(1f).setDuration(150).start()
    }
    private fun renderMenu(){showMenu()}
    private fun label(parent:LinearLayout,s:String){parent.addView(text(s,11f,0xFFA5C4AA.toInt(),true),LinearLayout.LayoutParams(-1,dp(23)))}
    private fun input(hint:String,value:String,numeric:Boolean=false):EditText {
        return EditText(uiContext).apply {
            setSingleLine(true); textSize=13f; setTextColor(Color.WHITE); setHintTextColor(0xFFA5C4AA.toInt()); setHint(hint); setText(value); contentDescription=hint
            background=bg(0xFF2A3A2F.toInt(),9f); setPadding(dp(9),0,dp(9),0)
            if(numeric) { inputType=android.text.InputType.TYPE_CLASS_NUMBER; keyListener=android.text.method.DigitsKeyListener.getInstance("0123456789"); imeOptions=EditorInfo.IME_ACTION_NEXT; textDirection=View.TEXT_DIRECTION_LTR; textAlignment=View.TEXT_ALIGNMENT_CENTER }
            else { inputType=android.text.InputType.TYPE_CLASS_TEXT; imeOptions=EditorInfo.IME_ACTION_NEXT; textDirection=if(ar())View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR }
            setOnFocusChangeListener { v,has ->
                if(has) { focusedField=this; setFocus(true); scroll?.post { ensureFocusedFieldVisible(this) } } else if(numeric) finalizeTimeInput(this) {}
            }
            setOnEditorActionListener { v,actionId,_ ->
                if(actionId==EditorInfo.IME_ACTION_NEXT) { if(numeric)finalizeTimeInput(this){}; v.focusSearch(View.FOCUS_FORWARD)?.requestFocus(); true } else false
            }
        }
    }
    private fun timePreview(digits: String): String = RallyTime.preview(digits)
    private fun bindTimeInput(field: EditText, changed: (String) -> Unit) {
        var formatting = false
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (formatting) return
                val digits = (s?.toString() ?: "").filter { it in '0'..'9' }.take(4)
                val rebuilt = timePreview(digits)
                if (rebuilt != s?.toString()) { formatting=true; field.setText(rebuilt); field.setSelection(rebuilt.length); formatting=false }
                changed(rebuilt)
                if(focusedField===field)miniEditorValue?.text=rebuilt
            }
        })
        val initial=RallyTime.preview(field.text.toString())
        if(initial!=field.text.toString()){field.setText(initial);field.setSelection(initial.length)}
        changed(initial)
        field.setOnFocusChangeListener { v,has ->
            if(has) { focusedField=field; setFocus(true); scroll?.post { ensureFocusedFieldVisible(field) } }
            else finalizeTimeInput(field,changed)
        }
        field.setOnEditorActionListener { v,actionId,_ ->
            if(actionId==EditorInfo.IME_ACTION_NEXT) { finalizeTimeInput(field,changed); v.focusSearch(View.FOCUS_FORWARD)?.requestFocus(); true } else false
        }
    }
    private fun finalizeTimeInput(field: EditText, changed: (String) -> Unit) {
        val raw=field.text.toString()
        val normalized=RallyTime.normalize(raw)
        if(normalized!=null) { if(raw!=normalized){field.setText(normalized);field.setSelection(normalized.length)};changed(normalized) }
        else changed(RallyTime.preview(raw))
    }
    private fun buildEditor(c:LinearLayout) {
        if(settingsMode) {
            label(c,t("الإعدادات","Settings"))
            c.addView(button(if(vibrationEnabled)uiContext.getString(R.string.vibration_on)else uiContext.getString(R.string.vibration_off),0xFF2A3A2F.toInt(),Color.WHITE){vibrationEnabled=!vibrationEnabled;persist();renderMenu()})
            c.addView(button(uiContext.getString(R.string.back),0xFF4CAF50.toInt(),0xFF102014.toInt()){settingsMode=false;persist();renderMenu()})
            return
        }
        val modes=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;direction(this)}
        modes.addView(button(uiContext.getString(R.string.mode_now),if(calculationMode=="now")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(calculationMode=="now")0xFF102014.toInt() else Color.WHITE){calculationMode="now";persist();renderMenu()},LinearLayout.LayoutParams(0,dp(32),1f))
        modes.addView(Space(uiContext),LinearLayout.LayoutParams(dp(6),1))
        modes.addView(button(uiContext.getString(R.string.mode_target),if(calculationMode=="target")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(calculationMode=="target")0xFF102014.toInt() else Color.WHITE){calculationMode="target";persist();renderMenu()},LinearLayout.LayoutParams(0,dp(32),1f))
        c.addView(modes)
        if(calculationMode=="now") {
            label(c,uiContext.getString(R.string.prep_delay))
            val delays=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            listOf(5,10,20,30).forEach { value ->
                delays.addView(button(value.toString(),if(prepSeconds==value)0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(prepSeconds==value)0xFF102014.toInt() else Color.WHITE){prepSeconds=value;persist();renderMenu()},LinearLayout.LayoutParams(0,dp(30),1f))
                if(value!=30)delays.addView(Space(uiContext),LinearLayout.LayoutParams(dp(4),1))
            }
            c.addView(delays)
        } else {
            label(c,t("الوقت المستهدف · MM:SS","Target time · MM:SS"))
            targetInput=input("MM:SS",target,true).apply{contentDescription=t("الوقت المستهدف","Target time")}
            c.addView(targetInput,LinearLayout.LayoutParams(-1,dp(38)))
            targetInput?.let { bindTimeInput(it) { value -> target=value; persist() } }
            label(c,uiContext.getString(R.string.utc_hour_optional))
            targetHourInput=input("HH",targetHour,true)
            c.addView(targetHourInput,LinearLayout.LayoutParams(-1,dp(34)))
            targetHourInput?.addTextChangedListener(SimpleWatcher{targetHour=it.filter{ch->ch.isDigit()}.take(2);persist()})
        }
        label(c,t("المشاركون","Participants")+" · ${people.size}")
        people.forEachIndexed{index,p->
            val row=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(5),0,dp(5))}
            row.addView(text(t("مشارك","Participant")+" ${index+1}",11f,0xFFA5C4AA.toInt(),true))
            val fields=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            val name=input(t("اسم اللاعب","Player name"),p.name)
            val duration=input("MM:SS",p.duration,true)
            name.contentDescription=t("مشارك ${index+1} · الاسم","Participant ${index+1} · name")
            duration.contentDescription=t("مشارك ${index+1} · المدة","Participant ${index+1} · duration")
            name.addTextChangedListener(SimpleWatcher{p.name=it;persist();if(focusedField===name)miniEditorValue?.text=it})
            bindTimeInput(duration) { value -> p.duration=value; persist() }
            fields.addView(name,LinearLayout.LayoutParams(0,dp(38),1f))
            fields.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
            fields.addView(duration,LinearLayout.LayoutParams(dp(82),dp(38)))
            row.addView(fields)
            val kindRow=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;direction(this)}
            kindRow.addView(fitControl(button(uiContext.getString(R.string.solo),if(p.kind=="solo")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.kind=="solo")0xFF102014.toInt() else Color.WHITE){p.kind="solo";persist();renderMenu()}),LinearLayout.LayoutParams(0,dp(40),1f))
            kindRow.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
            kindRow.addView(fitControl(button(uiContext.getString(R.string.rally),if(p.kind=="rally")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.kind=="rally")0xFF102014.toInt() else Color.WHITE){p.kind="rally";persist();renderMenu()}),LinearLayout.LayoutParams(0,dp(40),1f))
            row.addView(kindRow,LinearLayout.LayoutParams(-1,dp(40)).apply{topMargin=dp(4)})
            if(p.kind=="rally") {
                duration.hint="MM:SS";duration.contentDescription=uiContext.getString(R.string.rally_march_arrival)
                label(row,uiContext.getString(R.string.rally_march_arrival));label(row,uiContext.getString(R.string.rally_duration))
                val presets=listOf(1,3,5,10,15,30,60)
                presets.chunked(4).forEach { group ->
                    val durations=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
                    group.forEach { mins ->
                        durations.addView(fitControl(button(if(ar())"${mins}د" else "${mins}m",if(p.rallyMinutes==mins)0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.rallyMinutes==mins)0xFF102014.toInt() else Color.WHITE){p.rallyMinutes=mins;persist();renderMenu()}),LinearLayout.LayoutParams(0,dp(40),1f))
                        if(mins!=group.last())durations.addView(Space(uiContext),LinearLayout.LayoutParams(dp(4),1))
                    }
                    row.addView(durations,LinearLayout.LayoutParams(-1,dp(40)).apply{topMargin=dp(3)})
                }
                val custom=input(uiContext.getString(R.string.minutes),if(p.rallyMinutes !in presets)p.rallyMinutes.toString() else "",true)
                custom.visibility=View.GONE
                val customToggle=fitControl(button(uiContext.getString(R.string.custom_minutes),if(p.rallyMinutes !in presets)0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.rallyMinutes !in presets)0xFF102014.toInt() else Color.WHITE){custom.visibility=if(custom.visibility==View.VISIBLE)View.GONE else View.VISIBLE})
                row.addView(customToggle,LinearLayout.LayoutParams(-1,dp(40)).apply{topMargin=dp(8)})
                custom.addTextChangedListener(SimpleWatcher{v->v.toIntOrNull()?.takeIf{it > 0 && it <= Int.MAX_VALUE/60}?.let{p.rallyMinutes=it;persist();customToggle.setBackground(bg(0xFF4CAF50.toInt(),9f));customToggle.setTextColor(0xFF102014.toInt())}})
                row.addView(custom,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(4)})
            }
            val actions=LinearLayout(uiContext).apply{gravity=if(ar())Gravity.END else Gravity.START}
            actions.addView(button(t("تعديل","Edit"),0xFF2A3A2F.toInt(),Color.WHITE){name.requestFocus();name.setSelection(name.text.length);setFocus(true)})
            actions.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
            actions.addView(button(t("حذف","Delete"),0xFFE57373.toInt(),Color.WHITE){people.removeAll{it.id==p.id};if(people.isEmpty())people.add(Person(nextId++,"",""));persist();renderMenu()})
            row.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)});c.addView(row)
            if(index<people.lastIndex)c.addView(View(uiContext).apply{setBackgroundColor(0xFF405447.toInt())},LinearLayout.LayoutParams(-1,dp(1)))
        }
        c.addView(button(t("+ إضافة مشارك","+ Add participant"),0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){people.add(Person(nextId++,"",""));persist();renderMenu();scroll?.post{scroll?.fullScroll(View.FOCUS_DOWN)}})
        errors.forEach{c.addView(text(it,11f,0xFFE57373.toInt()))}
    }
    private fun utcTime(epoch:Long):String = SimpleDateFormat("HH:mm:ss",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(epoch))
    private fun countdown(ms:Long):String {
        val sec=((ms+999L)/1000L).coerceAtLeast(0L)
        return String.format(Locale.US,"%02d:%02d",sec/60,sec%60)
    }
    private fun copyRowText(r:ResultRow):String {
        val verb=if(r.isRally)uiContext.getString(R.string.start_rally) else uiContext.getString(R.string.send_action)
        val extra=if(r.isRally)String.format(Locale.US,uiContext.getString(R.string.rally_copy_suffix),r.rallySeconds/60) else ""
        return "${r.name} — ${verb} ${utcTime(r.actionAt)} UTC${extra}"
    }
    private fun copyRow(r:ResultRow) = copy(copyRowText(r))
    private fun updateResultCountdowns() {
        if(!resultsMode||resultCountdowns.isEmpty())return
        val now=System.currentTimeMillis()
        results.forEach { r ->
            val label=resultCountdowns[r.id]?:return@forEach
            val remaining=r.actionAt-now
            if(remaining>0) {
                label.text=String.format(Locale.US,uiContext.getString(R.string.countdown_in),countdown(remaining))
                label.setTextColor(if(remaining<=10_000L)0xFFFF9100.toInt() else 0xFFA5C4AA.toInt())
            } else {
                val zeroAt=resultZeroAt.getOrPut(r.id){now}
                label.text=uiContext.getString(R.string.now_label)
                label.setTextColor(if(now-zeroAt<3000L)0xFF4CAF50.toInt() else 0xFF78907D.toInt())
                resultRowsViews[r.id]?.alpha=if(now-zeroAt<3000L)1f else 0.55f
                if(vibrationEnabled&&vibratedIds.add(r.id)) {
                    runCatching {
                        val vibrator=if(Build.VERSION.SDK_INT>=31)getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
                        if(Build.VERSION.SDK_INT>=26)vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(70)
                    }
                }
            }
        }
    }
    private fun buildResults(c:LinearLayout) {
        resultCountdowns.clear();resultRowsViews.clear()
        label(c,uiContext.getString(R.string.execution_schedule))
        results.forEachIndexed { index,r ->
            val wrap=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(5),0,dp(5));direction(this)}
            resultRowsViews[r.id]=wrap
            val top=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;direction(this)}
            val info=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL}
            info.addView(text(r.name,12f,Color.WHITE,true))
            info.addView(text(if(r.isRally)String.format(Locale.US,uiContext.getString(R.string.rally_badge),r.rallySeconds/60) else uiContext.getString(R.string.solo),10f,0xFFA5C4AA.toInt()))
            top.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            top.addView(text(if(r.isRally)uiContext.getString(R.string.start_rally) else uiContext.getString(R.string.send_action),11f,0xFFA5C4AA.toInt(),true))
            val time=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_LTR}
            time.addView(text(utcTime(r.actionAt).substring(0,2),11f,0xFFA5C4AA.toInt()))
            time.addView(text(utcTime(r.actionAt).substring(2),17f,Color.WHITE,true))
            top.addView(time,LinearLayout.LayoutParams(-2,dp(34)).apply{marginStart=dp(7)})
            top.addView(button(t("نسخ","Copy"),0xFF2A3A2F.toInt(),Color.WHITE){copyRow(r)})
            wrap.addView(top)
            val timer=text("",11f,0xFFA5C4AA.toInt(),true).apply{textDirection=View.TEXT_DIRECTION_LTR}
            resultCountdowns[r.id]=timer
            wrap.addView(timer,LinearLayout.LayoutParams(-1,dp(22)))
            if(r.isRally)wrap.addView(text(t("ينطلق ","Departs ")+utcTime(r.departAt),10f,0xFFA5C4AA.toInt()))
            c.addView(wrap)
            if(index<results.lastIndex) {
                val next=results[index+1]
                val gap=((next.actionAt-r.actionAt)/1000L).coerceAtLeast(0L)
                c.addView(text(String.format(Locale.US,uiContext.getString(R.string.after_player_by),r.name,countdown(gap*1000)),9f,0xFFA5C4AA.toInt()))
                c.addView(View(uiContext).apply{setBackgroundColor(0xFF405447.toInt())},LinearLayout.LayoutParams(-1,dp(1)))
            }
        }
        errors.forEach{c.addView(text(it,10f,0xFFE57373.toInt()))}
        results.firstOrNull()?.let { r ->
            c.addView(text(String.format(Locale.US,uiContext.getString(R.string.everyone_arrives),utcTime(r.arrivalAt)),11f,0xFF4CAF50.toInt(),true))
        }
        c.addView(button(t("نسخ الكل","Copy all"),0xFF2A3A2F.toInt(),Color.WHITE){copy(results.joinToString("\n"){r->copyRowText(r)})})
        c.addView(button(uiContext.getString(R.string.recalculate_now),0xFF4CAF50.toInt(),0xFF102014.toInt()){calculationMode="now";resultsMode=false;calculate()})
        c.addView(button(t("رجوع للتعديل","Back to edit"),0xFF2A3A2F.toInt(),Color.WHITE){resultsMode=false;persist();renderMenu()})
        updateResultCountdowns()
        val now=System.currentTimeMillis()
        results.forEach { r ->
            val wait=(r.actionAt-now).coerceAtLeast(0L)
            handler.postDelayed({updateResultCountdowns()},wait)
        }
    }
    private fun calculate() {
        errors.clear()
        val valid=mutableListOf<RallyInput>()
        people.forEachIndexed{i,p->
            if(p.name.isBlank()){errors.add(t("أدخل اسم اللاعب للمشارك ","Enter a player name for participant ")+(i+1));return@forEachIndexed}
            val march=parse(p.duration)
            if(march==null){errors.add(t("مدة المسيرة غير صالحة للمشارك: ","Invalid march duration for: ")+p.name);return@forEachIndexed}
            valid.add(RallyInput(p.id,p.name.trim(),march,if(p.kind=="rally")p.rallyMinutes*60 else 0))
        }
        if(valid.isEmpty()){resultsMode=false;renderMenu();return}
        val now=System.currentTimeMillis()
        val rows:List<RallyPlanRow>
        val arrival:Long
        if(calculationMode=="now") {
            val plan=RallyPlanner.planNow(now,prepSeconds,valid);rows=plan.rows;arrival=plan.arrivalAtMillis
        } else {
            val goal=parse(target)
            if(goal==null){errors.add(uiContext.getString(R.string.invalid_target_mmss));resultsMode=false;renderMenu();targetInput?.requestFocus();return}
            val targetAt=if(targetHour.isNotBlank()) {
                val hour=targetHour.toIntOrNull()
                if(hour==null||hour !in 0..23){errors.add(uiContext.getString(R.string.invalid_utc_hour));renderMenu();return}
                val cal=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US).apply{timeInMillis=now;set(Calendar.HOUR_OF_DAY,hour);set(Calendar.MINUTE,goal/60);set(Calendar.SECOND,goal%60);set(Calendar.MILLISECOND,0)}
                cal.timeInMillis
            } else now+goal*1000L
            val late=valid.filter{targetAt-(it.totalSeconds*1000L)<now}
            late.forEach{errors.add(String.format(Locale.US,uiContext.getString(R.string.missed_action_for),it.name))}
            rows=valid.filterNot{it in late}.map{p->
                RallyPlanRow(p.id,p.name,p.isRally,p.rallySeconds,p.marchSeconds,targetAt-p.totalSeconds*1000L,targetAt-p.marchSeconds*1000L,targetAt)
            }.sortedBy{it.actionAtMillis}
            arrival=targetAt
            if(late.isNotEmpty()) {
                results=rows.map{ResultRow(it.id,it.name,it.isRally,it.rallySeconds,it.marchSeconds,it.actionAtMillis,it.departAtMillis,it.arrivalAtMillis)}.toMutableList()
                resultsMode=true;persist();renderMenu()
                val postpone=button(uiContext.getString(R.string.postpone_target_hour),0xFF4CAF50.toInt(),0xFF102014.toInt()){
                    val shifted=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US).apply{timeInMillis=targetAt+3600000L}
                    targetHour=String.format(Locale.US,"%02d",shifted.get(Calendar.HOUR_OF_DAY))
                    target=String.format(Locale.US,"%d:%02d",shifted.get(Calendar.MINUTE),shifted.get(Calendar.SECOND))
                    persist();calculate()
                }
                menuView?.addView(postpone,LinearLayout.LayoutParams(-1,dp(34)).apply{topMargin=dp(5)})
                return
            }
        }
        results=rows.map{ResultRow(it.id,it.name,it.isRally,it.rallySeconds,it.marchSeconds,it.actionAtMillis,it.departAtMillis,arrival)}.toMutableList()
        resultsMode=true;resultZeroAt.clear();vibratedIds.clear();persist();renderMenu()
    }
    private fun parse(raw:String):Int? = RallyTime.seconds(raw)
    private fun format(n:Int):String = RallyTime.format(n)
    private fun copy(s:String){(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rally",s));Toast.makeText(uiContext,t("تم النسخ","Copied"),Toast.LENGTH_SHORT).show()}
    private fun setFocus(on:Boolean) {
        val r=root?:return;val p=params?:return
        if(focusable==on)return
        focusable=on
        p.flags=if(on)p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching{wm.updateViewLayout(r,p)}
        if(on){handler.postDelayed({val focused=r.findFocus()?:r;(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(focused,InputMethodManager.SHOW_IMPLICIT)},120)}
        else{(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(r.windowToken,0)}
    }
    private fun showConfirm() {
        if(confirming)return;confirming=true
        if (focusable) { focusedField?.clearFocus(); setFocus(false) }
        val dialog=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=bg(0xFF1E2A22.toInt(),12f);setPadding(dp(16),dp(12),dp(16),dp(12))}
        dialog.addView(text(t("هل تريد إغلاق الأداة؟","Close the tool?"),13f,Color.WHITE,true))
        val actions=LinearLayout(uiContext).apply{gravity=Gravity.CENTER}
        actions.addView(button(t("نعم","Yes"),0xFF4CAF50.toInt(),Color.WHITE){confirming=false;stopSelf()})
        actions.addView(Space(uiContext),LinearLayout.LayoutParams(dp(8),1))
        actions.addView(button(t("لا","No"),0xFFE57373.toInt(),Color.WHITE){confirming=false;buildCollapsed();applyLayout()})
        dialog.addView(actions)
        val r=root?:return;r.removeAllViews();r.addView(dialog,FrameLayout.LayoutParams(dp(220),dp(100),Gravity.CENTER))
        val p=params?:return;p.width=dp(240);p.height=dp(120);p.x=(resources.displayMetrics.widthPixels-p.width)/2;p.y=(resources.displayMetrics.heightPixels-p.height)/2
        runCatching{wm.updateViewLayout(r,p)}
    }
    override fun onDestroy(){runCatching{unregisterReceiver(localeReceiver)};handler.removeCallbacksAndMessages(null);globalLayoutListener?.let{l->root?.viewTreeObserver?.let{if(it.isAlive)it.removeOnGlobalLayoutListener(l)}};root?.let{runCatching{wm.removeView(it)}};root=null
        if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE)else @Suppress("DEPRECATION") stopForeground(true);super.onDestroy()}
    companion object { const val ACTION_STOP="com.mythic.rally.STOP"; const val ACTION_LANGUAGE_CHANGED="com.mythic.rally.LANGUAGE_CHANGED"; private const val TAG="RallyOverlay" }
    private class SimpleWatcher(val changed:(String)->Unit):android.text.TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed(s?.toString()?: "")}
        override fun afterTextChanged(s:android.text.Editable?){}
    }
}
