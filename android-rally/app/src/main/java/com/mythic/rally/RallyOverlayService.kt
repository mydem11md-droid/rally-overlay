package com.mythic.rally

import android.app.*
import android.animation.ValueAnimator
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
import java.util.*
import kotlin.math.*

private class EdgeResizeLayout(context: Context) : FrameLayout(context) {
    companion object { const val L=1; const val T=2; const val R=4; const val B=8 }
    var resizeEnabled=false
    var onResize: ((dx:Int,dy:Int,edges:Int)->Unit)?=null
    var onResizeEnd: (() -> Unit)?=null
    private val edge=(20*resources.displayMetrics.density).roundToInt()
    private val handlePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x668FA493; strokeWidth=(2*resources.displayMetrics.density).coerceAtLeast(1f) }
    private var edges=0
    private var lastX=0f
    private var lastY=0f
    init { setWillNotDraw(false) }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(!resizeEnabled)return
        val d=resources.displayMetrics.density
        val cx=width/2f;val cy=height/2f
        canvas.drawLine(cx-12*d,2*d,cx+12*d,2*d,handlePaint)
        canvas.drawLine(cx-12*d,height-2*d,cx+12*d,height-2*d,handlePaint)
        canvas.drawLine(2*d,cy-12*d,2*d,cy+12*d,handlePaint)
        canvas.drawLine(width-2*d,cy-12*d,width-2*d,cy+12*d,handlePaint)
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(!resizeEnabled)return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            edges=0
            if(event.x<edge)edges=edges or L
            if(event.x>width-edge)edges=edges or R
            if(event.y<edge)edges=edges or T
            if(event.y>height-edge)edges=edges or B
            lastX=event.rawX;lastY=event.rawY
            return edges!=0
        }
        return edges!=0
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!resizeEnabled||edges==0)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx=(event.rawX-lastX).roundToInt();val dy=(event.rawY-lastY).roundToInt()
                lastX=event.rawX;lastY=event.rawY
                if(dx!=0||dy!=0)onResize?.invoke(dx,dy,edges)
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { edges=0;onResizeEnd?.invoke() }
        }
        return true
    }
}

class RallyOverlayService : Service() {
    private val prefs by lazy { getSharedPreferences("rally_native", MODE_PRIVATE) }
    private lateinit var uiContext: Context
    private var language = "en"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: EdgeResizeLayout? = null
    private var panelWidthPx = 0
    private var panelHeightPx = 0
    private var panelXdp = -1
    private var panelYdp = 180
    private var params: WindowManager.LayoutParams? = null
    private var menuOpen = false
    private var panelAbove = false
    private var panelContentHeightPx = 0
    private var resultsMode = false
    private var settingsMode = false
    private var focusable = false
    private val imm by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }
    private var target = ""
    private var targetHour = ""
    private var calculationMode = "now"
    private var prepSeconds = 10
    private var rallyDurationMinutes = 5
    private var vibrationEnabled = true
    private var targetHourInput: EditText? = null
    private var resultCountdowns = mutableMapOf<Int,TextView>()
    private var resultRowsViews = mutableMapOf<Int,View>()
    private var resultActionTimesViews = mutableMapOf<Int,TextView>()
    private var resultDepartureTimesViews = mutableMapOf<Int,TextView>()
    private var resultArrivalTimeView: TextView? = null
    private var resultZeroAt = mutableMapOf<Int,Long>()
    private var vibratedIds = mutableSetOf<Int>()
    private var people = mutableListOf<Person>()
    private var results = mutableListOf<ResultRow>()
    private var errors = mutableListOf<String>()
    private var nextId = 1
    private var bubbleX = 24
    private var bubbleY = 180
    private var menuView: LinearLayout? = null
    private var activeDropdownAnchor: View? = null
    private var activeDropdownView: View? = null
    private var activeDropdownHost: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var participantRowsScroll: CappedScrollView? = null
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
    private var panelParams: WindowManager.LayoutParams? = null
    private class DropdownPanelLayout(context: Context, private val onDown: (MotionEvent) -> Unit) : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onDown(event)
            return super.dispatchTouchEvent(event)
        }
    }
    private class CappedScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val parentHeight=MeasureSpec.getSize(heightMeasureSpec)
            val targetHeight=min(parentHeight,maxHeightPx.coerceAtLeast(0))
            val mode=if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY)MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
            super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(targetHeight,mode))
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
    private val clockListener: (Long) -> Unit = { epochMillis ->
        updateClock(epochMillis)
        updateResultCountdowns(epochMillis)
    }
    private val calibrationListener: (Long) -> Unit = { deltaMillis ->
        shiftCalculatedResults(deltaMillis)
        persist()
    }

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
        running=true;sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null

    private fun restore() {
        target=prefs.getString("target","") ?: ""
        targetHour=prefs.getString("target_hour","") ?: ""
        calculationMode=prefs.getString("calculation_mode","now") ?: "now"
        prepSeconds=prefs.getInt("prep_seconds",10)
        rallyDurationMinutes=prefs.getInt("rally_duration_minutes",5).coerceAtLeast(1)
        vibrationEnabled=prefs.getBoolean("vibration_enabled",true)
        bubbleX=prefs.getInt("x",24); bubbleY=prefs.getInt("y",180)
        panelWidthPx=dp(prefs.getInt("panel_w",320)).coerceAtLeast(dp(240))
        panelHeightPx=dp(prefs.getInt("panel_h",520)).coerceAtLeast(dp(280))
        panelXdp=prefs.getInt("panel_x",-1); panelYdp=prefs.getInt("panel_y",bubbleY)
        menuOpen=false; resultsMode=prefs.getBoolean("results",false); settingsMode=prefs.getBoolean("settings",false)
        prefs.edit().putBoolean("open",false).apply()
        nextId=prefs.getInt("next",1)
        runCatching {
            val a=JSONArray(prefs.getString("people","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);people.add(Person(o.getInt("id"),o.optString("name"),o.optString("duration"),o.optString("kind","solo"),o.optInt("rally_minutes",5)))}
        }
        if(people.isEmpty()) people.add(Person(nextId++,"",""))
        if(!prefs.contains("rally_duration_minutes")) rallyDurationMinutes=people.firstOrNull{it.kind=="rally"}?.rallyMinutes ?: 5
        runCatching {
            val a=JSONArray(prefs.getString("result_rows","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);results.add(ResultRow(o.getInt("id"),o.optString("name"),o.optBoolean("is_rally"),o.optInt("rally_seconds"),o.optInt("march_seconds"),o.optLong("action_at"),o.optLong("depart_at"),o.optLong("arrival_at")))}
        }
        // Results saved before calibration became adjustable were based on the previous +350 ms default.
        val savedCalibration = prefs.getLong("results_calibration_offset_millis", 350L)
        val calibrationDelta = RallyTime.calibrationOffsetMillis() - savedCalibration
        if (calibrationDelta != 0L) {
            shiftCalculatedResults(calibrationDelta)
            persist()
        }
    }
    private fun shiftCalculatedResults(deltaMillis: Long) {
        if (deltaMillis == 0L) return
        results.indices.forEach { index ->
            val row = results[index]
            results[index] = row.copy(
                actionAt = row.actionAt + deltaMillis,
                departAt = row.departAt + deltaMillis,
                arrivalAt = row.arrivalAt + deltaMillis
            )
        }
        resultZeroAt.keys.toList().forEach { id ->
            resultZeroAt[id] = (resultZeroAt[id] ?: 0L) + deltaMillis
        }
        results.forEach { row ->
            resultActionTimesViews[row.id]?.text = utcTime(row.actionAt)
            resultDepartureTimesViews[row.id]?.text = t("ينطلق ", "Departs ") + utcTime(row.departAt)
        }
        resultArrivalTimeView?.let { view ->
            results.firstOrNull()?.arrivalAt?.let { view.text = utcTime(it) }
        }
    }
    private fun persist() {
        val a=JSONArray(); people.forEach { a.put(JSONObject().put("id",it.id).put("name",it.name).put("duration",it.duration).put("kind",it.kind).put("rally_minutes",it.rallyMinutes)) }
        val r=JSONArray(); results.forEach { r.put(JSONObject().put("id",it.id).put("name",it.name).put("is_rally",it.isRally).put("rally_seconds",it.rallySeconds).put("march_seconds",it.marchSeconds).put("action_at",it.actionAt).put("depart_at",it.departAt).put("arrival_at",it.arrivalAt)) }
        prefs.edit().putString("target",target).putString("target_hour",targetHour).putString("calculation_mode",calculationMode).putInt("prep_seconds",prepSeconds).putInt("rally_duration_minutes",rallyDurationMinutes).putBoolean("vibration_enabled",vibrationEnabled).putString("people",a.toString()).putString("result_rows",r.toString())
            .putInt("next",nextId).putInt("x",bubbleX).putInt("y",bubbleY)
            .putBoolean("open",menuOpen).putBoolean("results",resultsMode).putBoolean("settings",settingsMode)
            .putLong("results_calibration_offset_millis",RallyTime.calibrationOffsetMillis()).apply()
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
        statusInsetPx=dp(24);navInsetPx=dp(24)
        val sw=resources.displayMetrics.widthPixels
        val sh=resources.displayMetrics.heightPixels
        val maxW=(sw*0.80f).roundToInt().coerceAtLeast(dp(1))
        panelWidthPx=(panelWidthPx.takeIf{it>0} ?: min(dp(320),maxW)).coerceIn(min(dp(240),maxW),maxW)
        bubbleX=bubbleX.coerceIn(0,((sw-dp(84))/density).roundToInt().coerceAtLeast(0))
        bubbleY=bubbleY.coerceIn(((statusInsetPx+dp(8))/density).roundToInt(),((sh-dp(84)-navInsetPx)/density).roundToInt().coerceAtLeast(0))
        val frame=EdgeResizeLayout(uiContext).apply{clipChildren=true;clipToPadding=true;direction(this);isFocusableInTouchMode=true}
        root=frame
        val p=WindowManager.LayoutParams(dp(84),dp(84),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply{
            gravity=Gravity.TOP or Gravity.START;x=dp(bubbleX);y=dp(bubbleY)
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        params=p;frame.resizeEnabled=false
        frame.setOnKeyListener{_,code,event->if(code==KeyEvent.KEYCODE_BACK&&event.action==KeyEvent.ACTION_UP&&focusable){focusedField?.let{dismissIme(it)}?:dismissIme(null);true}else false}
        wm.addView(frame,p);buildCollapsed()
        if(menuOpen)showMenu()
        UtcClockTicker.addListener(clockListener)
        UtcClockTicker.addCalibrationListener(calibrationListener)
    }
    private fun resizePanelBy(dx:Int,dy:Int,edges:Int) {
        val r=root?:return
        val p=params?:return
        val sw=resources.displayMetrics.widthPixels
        val sh=resources.displayMetrics.heightPixels
        val minW=dp(240);val minH=dp(280)
        var x=p.x;var y=p.y;var w=p.width;var h=p.height
        if(edges and EdgeResizeLayout.R!=0)w=(w+dx).coerceIn(minW,(sw-x).coerceAtLeast(minW))
        if(edges and EdgeResizeLayout.L!=0) {
            val right=x+w
            w=(w-dx).coerceIn(minW,sw.coerceAtLeast(minW))
            x=right-w
        }
        if(edges and EdgeResizeLayout.B!=0)h=(h+dy).coerceIn(minH,(sh-y).coerceAtLeast(minH))
        if(edges and EdgeResizeLayout.T!=0) {
            val bottom=y+h
            h=(h-dy).coerceIn(minH,sh.coerceAtLeast(minH))
            y=bottom-h
        }
        if(x<0){w=(x+w).coerceIn(minW,sw.coerceAtLeast(minW));x=0}
        if(y<0){h=(y+h).coerceIn(minH,sh.coerceAtLeast(minH));y=0}
        if(x+w>sw)w=(sw-x).coerceAtLeast(minW)
        if(y+h>sh)h=(sh-y).coerceAtLeast(minH)
        x=x.coerceAtLeast(0);y=y.coerceAtLeast(0)
        p.width=w;p.height=h;p.x=x;p.y=y
        panelWidthPx=w;panelHeightPx=h
        panelXdp=(x/density).roundToInt();panelYdp=(y/density).roundToInt()
        runCatching { wm.updateViewLayout(r,p) }
    }
    private fun addPinnedControls(f: FrameLayout) {
        val clock=TextView(uiContext).apply{tag="utc_time";text=RallyTime.utcClock(UtcClockTicker.currentTimeMillis());textSize=12f;setTextColor(Color.WHITE);setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);fontFeatureSettings="tnum";includeFontPadding=false;isSingleLine=true;gravity=Gravity.CENTER;textDirection=View.TEXT_DIRECTION_LTR}
        val utc=TextView(uiContext).apply{text="UTC";textSize=8f;setTextColor(0xFFE1F5E8.toInt());includeFontPadding=false;gravity=Gravity.CENTER;textDirection=View.TEXT_DIRECTION_LTR}
        val clockStack=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;addView(clock,LinearLayout.LayoutParams(-1,dp(19)));addView(utc,LinearLayout.LayoutParams(-1,dp(12)))}
        val orb=object:FrameLayout(uiContext){
            private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
            private val border=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=dp(3).toFloat()}
            private val glow=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x5541B86C;style=Paint.Style.STROKE;strokeWidth=dp(3).toFloat()}
            init{setWillNotDraw(false);setLayerType(View.LAYER_TYPE_SOFTWARE,null)}
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fill.shader=RadialGradient(w*.32f,h*.28f,max(w,h)*.9f,intArrayOf(Color.rgb(65,174,96),Color.rgb(10,94,48)),null,Shader.TileMode.CLAMP);fill.setShadowLayer(dp(14).toFloat(),0f,dp(5).toFloat(),0x59000000)}
            override fun onDraw(canvas:Canvas){super.onDraw(canvas);val cx=width/2f;val cy=height/2f;val radius=width/2f-dp(4);canvas.drawCircle(cx,cy,radius+dp(3),glow);canvas.drawCircle(cx,cy,radius,fill);canvas.drawCircle(cx,cy,radius,border)}
        }.apply{tag="rally_orb";addView(clockStack,FrameLayout.LayoutParams(dp(62),dp(34),Gravity.CENTER));contentDescription=t("فتح أو إغلاق أداة تنسيق الحشود","Open or close Rally Coordinator")}
        f.addView(orb,FrameLayout.LayoutParams(dp(76),dp(76),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(4)})
        orb.setOnTouchListener{_,event->handleCircleTouch(event)}
    }
    private var downX=0f
    private var downY=0f
    private var startWindowX=0
    private var startWindowY=0
    private var trackingCircle=false
    private var draggingCircle=false
    private var dragFramePending=false
    private val touchSlop by lazy{ViewConfiguration.get(this).scaledTouchSlop}
    private val dragFrameCallback=Choreographer.FrameCallback{dragFramePending=false;applyLayout()}
    private fun handleCircleTouch(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{trackingCircle=true;draggingCircle=false;downX=event.rawX;downY=event.rawY;val p=params?:return false;startWindowX=p.x;startWindowY=p.y;return true}
            MotionEvent.ACTION_MOVE->{
                if(!trackingCircle)return false
                val dx=(event.rawX-downX).roundToInt();val dy=(event.rawY-downY).roundToInt()
                if(!draggingCircle&&(abs(dx)>touchSlop||abs(dy)>touchSlop))draggingCircle=true
                if(draggingCircle){
                    val screen=resources.displayMetrics;val p=params?:return true
                    val height=dp(84)
                    val maxY=(screen.heightPixels-(if(menuOpen)dp(88)+panelHeightPx else height)-navInsetPx).coerceAtLeast(statusInsetPx+dp(8))
                    p.x=(startWindowX+dx).coerceIn(0,(screen.widthPixels-dp(84)).coerceAtLeast(0))
                    p.y=(startWindowY+dy).coerceIn(statusInsetPx+dp(8),maxY)
                    bubbleX=(p.x/density).roundToInt();bubbleY=(p.y/density).roundToInt()
                    if(!dragFramePending){dragFramePending=true;Choreographer.getInstance().postFrameCallback(dragFrameCallback)}
                };return true
            }
            MotionEvent.ACTION_UP->{if(!trackingCircle)return false;val moved=draggingCircle;trackingCircle=false;draggingCircle=false;if(moved){flushDragLayout();persist()}else{if(menuOpen)collapse()else{menuOpen=true;persist();showMenu()}};return true}
            MotionEvent.ACTION_CANCEL->{val was=trackingCircle;if(draggingCircle)flushDragLayout();trackingCircle=false;draggingCircle=false;return was}
        };return trackingCircle
    }
    private fun flushDragLayout(){if(dragFramePending){Choreographer.getInstance().removeFrameCallback(dragFrameCallback);dragFramePending=false};applyLayout()}
    private fun buildCollapsed() {
        if(focusable){focusedField?.clearFocus();setFocus(false)}
        val f=root?:return
        f.resizeEnabled=false
        f.removeAllViews();f.clipChildren=true;f.clipToPadding=true
        menuView=null;scroll=null;miniEditor=null;miniEditorTitle=null;miniEditorValue=null
        addPinnedControls(f);updateClock();applyLayout()
    }
    private fun updateClock(epochMillis: Long = UtcClockTicker.currentTimeMillis()) {
        val c=root?.findViewWithTag<TextView>("utc_time")?:return
        c.text=RallyTime.utcClock(epochMillis)
    }
    private fun availableOverlayHeight(): Int {
        val full=if(Build.VERSION.SDK_INT>=30)wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels
        val top=dp(bubbleY)+(if(menuOpen)dp(88) else 0)
        return (full-max(statusInsetPx,top)-navInsetPx-imeHeightPx-dp(16)).coerceAtLeast(dp(120))
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
    private fun resizeForInsets() {
        if(root==null)return
        val screenCap=(resources.displayMetrics.heightPixels*.65f).roundToInt()
        val cap=(min(screenCap,availableOverlayHeight())-dp(32+18+42)).coerceAtLeast(dp(64))
        (scroll as? CappedScrollView)?.maxHeightPx=cap
        scroll?.requestLayout()
        focusedField?.let { field -> field.post { if(field.hasFocus())ensureFocusedFieldVisible(field) } }
    }
    private fun ensureFocusedFieldVisible(field: EditText) {
        val sc=scroll?:return
        // Some editor fields (for example the target-time controls) live outside
        // the participant-list ScrollView. Only convert coordinates when the
        // focused field is actually a descendant of this scroll container.
        var ancestor: ViewParent? = field.parent
        var isDescendant = false
        while (ancestor != null) {
            if (ancestor === sc) {
                isDescendant = true
                break
            }
            ancestor = ancestor.parent
        }
        val rect=Rect()
        var needsMini = imeHeightPx>dp(80) && sc.height<dp(90)
        if (isDescendant) {
            field.getDrawingRect(rect)
            sc.offsetDescendantRectToMyCoords(field,rect)
            val visibleBottom=sc.scrollY+sc.height-dp(12)
            if(rect.bottom>visibleBottom)sc.smoothScrollTo(0,(rect.bottom-sc.height+dp(18)).coerceAtLeast(0))
            needsMini = needsMini || (imeHeightPx>dp(80) && rect.height()>sc.height)
        }
        miniEditorTitle?.text=field.contentDescription?.toString() ?: field.hint?.toString().orEmpty()
        miniEditorValue?.text=field.text?.toString().orEmpty()
        miniEditor?.visibility=if(needsMini)View.VISIBLE else View.GONE
        if(needsMini)lastScrollMode="mini-editor"
    }
    private fun applyLayout(){
        val r=root?:return;val p=params?:return
        val screen=resources.displayMetrics
        val width=dp(84);val height=dp(84)
        val x=dp(bubbleX).coerceIn(0,(screen.widthPixels-width).coerceAtLeast(0))
        val maxY=(screen.heightPixels-(if(menuOpen)dp(88)+panelHeightPx else height)-navInsetPx).coerceAtLeast(statusInsetPx+dp(8))
        val y=dp(bubbleY).coerceIn(statusInsetPx+dp(8),maxY)
        if(p.width!=width||p.height!=height||p.x!=x||p.y!=y){p.width=width;p.height=height;p.x=x;p.y=y;runCatching{wm.updateViewLayout(r,p)}}
        if(menuOpen){
            val panel=menuView;val pp=panelParams
            if(panel!=null&&pp!=null){
                val panelX=(x+dp(42)-panelWidthPx/2).coerceIn(0,(screen.widthPixels-panelWidthPx).coerceAtLeast(0))
                val panelY=y+dp(88)
                if(pp.x!=panelX||pp.y!=panelY){pp.x=panelX;pp.y=panelY;runCatching{wm.updateViewLayout(panel,pp)}}
            }
        }
    }
    private var panelAnimator:ValueAnimator?=null
    private fun collapse(){
        dismissDropdown()
        if(focusable){dismissIme(focusedField);setFocus(false)}
        val panel=menuView
        if(panel==null){menuOpen=false;persist();buildCollapsed();applyLayout();return}
        panel.animate().scaleY(.96f).setDuration(180).withEndAction{
            menuOpen=false;persist()
            removePanelWindow()
            panelHeightPx=0;panelContentHeightPx=0
            buildCollapsed();applyLayout()
        }.start()
    }
    private fun removePanelWindow(){
        val panel=menuView
        globalLayoutListener?.let{l->panel?.viewTreeObserver?.let{if(it.isAlive)it.removeOnGlobalLayoutListener(l)}}
        globalLayoutListener=null
        dismissDropdown()
        if(panel!=null)runCatching{wm.removeView(panel)}
        menuView=null;panelParams=null
    }
    private fun showMenu(){
        val f=root?:return
        if(focusable){dismissIme(focusedField);setFocus(false)}
        removePanelWindow()
        f.removeAllViews();f.clipChildren=true;f.clipToPadding=true
        addPinnedControls(f)
        val screen=resources.displayMetrics
        val maxPanel=(screen.heightPixels*.65f).roundToInt().coerceAtLeast(dp(180))
        val below=(screen.heightPixels-dp(bubbleY)-dp(88)-navInsetPx-dp(8)).coerceAtLeast(dp(160))
        val panelCap=min(maxPanel,below)
        val panel=DropdownPanelLayout(uiContext,::onPanelTouchDown).apply{orientation=LinearLayout.VERTICAL;background=bg(0xFF1E2A22.toInt(),12f);setPadding(dp(10),dp(9),dp(10),dp(9));clipChildren=true;clipToPadding=true;direction(this);isFocusable=true;isFocusableInTouchMode=true}
        menuView=panel
        panel.setOnKeyListener{_,code,event->if(code==KeyEvent.KEYCODE_BACK&&event.action==KeyEvent.ACTION_UP&&focusable){focusedField?.let{dismissIme(it)}?:dismissIme(null);true}else false}
        val header=LinearLayout(uiContext).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL;direction(this)}
        header.addView(text(if(settingsMode)t("الإعدادات","Settings")else if(resultsMode)t("نتائج التنسيق","Rally results")else t("أداة تنسيق الحشود","Rally Coordinator"),13f,Color.WHITE,true),LinearLayout.LayoutParams(0,dp(32),1f))
        header.addView(button("EN | ع",0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){changeLanguage()}.apply{textDirection=View.TEXT_DIRECTION_LTR;textSize=10f},LinearLayout.LayoutParams(dp(54),dp(30)))
        header.addView(Space(uiContext),LinearLayout.LayoutParams(dp(4),1))
        header.addView(button("⚙",0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){settingsMode=!settingsMode;if(settingsMode)resultsMode=false;persist();renderMenu()}.apply{textSize=12f},LinearLayout.LayoutParams(dp(32),dp(30)))
        panel.addView(header)
        miniEditor=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;background=bg(0xFF2A3A2F.toInt(),9f);setPadding(dp(8),dp(4),dp(8),dp(4))}
        miniEditorTitle=text("",9f,0xFFA5C4AA.toInt(),true);miniEditorValue=text("",16f,Color.WHITE,true)
        miniEditor?.addView(miniEditorTitle);miniEditor?.addView(miniEditorValue)
        panel.addView(miniEditor,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        val content=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;clipChildren=true;clipToPadding=true;direction(this)}
        val sc=CappedScrollView(uiContext).apply{isFillViewport=false;clipChildren=true;clipToPadding=true;direction(this);maxHeightPx=(panelCap-dp(32+18+42)).coerceAtLeast(dp(64))}
        scroll=sc;participantRowsScroll=null;sc.addView(content);panel.addView(sc,LinearLayout.LayoutParams(-1,-2))
        if(resultsMode)buildResults(content)else buildEditor(content)
        if(!resultsMode)panel.addView(button(t("حساب","Calculate"),0xFF4CAF50.toInt(),0xFF102014.toInt()){calculate()}.apply{textSize=12f},LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(5)})
        panel.measure(View.MeasureSpec.makeMeasureSpec(panelWidthPx,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(panelCap,View.MeasureSpec.AT_MOST))
        panelContentHeightPx=panel.measuredHeight.coerceAtMost(panelCap).coerceAtLeast(min(dp(100),panelCap));panelHeightPx=panelContentHeightPx
        bubbleY=bubbleY.coerceIn(((statusInsetPx+dp(8))/density).roundToInt(),((screen.heightPixels-panelHeightPx-dp(88)-navInsetPx)/density).roundToInt().coerceAtLeast(0))
        applyLayout()
        val panelX=(dp(bubbleX+38)-panelWidthPx/2).coerceIn(0,(screen.widthPixels-panelWidthPx).coerceAtLeast(0))
        val panelY=dp(bubbleY)+dp(88)
        val pp=WindowManager.LayoutParams(panelWidthPx,panelHeightPx,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply{
            gravity=Gravity.TOP or Gravity.START;x=panelX;y=panelY
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        panelParams=pp
        runCatching{wm.addView(panel,pp)}.onFailure{
            Log.e("RallyLayout","panel attach failed",it)
            menuView=null;panelParams=null;menuOpen=false;persist();buildCollapsed()
            return
        }
        installInsetsTracking(panel)
        panel.scaleY=.98f;panel.pivotY=0f
        panel.animate().scaleY(1f).setDuration(180).start()
        updateClock()
    }
    private fun renderMenu(){showMenu()}
    private fun label(parent:LinearLayout,s:String){parent.addView(text(s,10f,0xFFA5C4AA.toInt(),true),LinearLayout.LayoutParams(-1,dp(21)))}
    private fun onPanelTouchDown(event: MotionEvent) {
        val anchor = activeDropdownAnchor ?: return
        val menu = activeDropdownView
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        val anchorRect = Rect()
        val menuRect = Rect()
        val insideAnchor = anchor.getGlobalVisibleRect(anchorRect) && anchorRect.contains(x,y)
        val insideMenu = menu?.getGlobalVisibleRect(menuRect) == true && menuRect.contains(x,y)
        if (!insideAnchor && !insideMenu) dismissDropdown()
    }
    private fun dismissDropdown() {
        val host = activeDropdownHost
        val menu = activeDropdownView
        if (host != null && menu != null) runCatching { host.removeView(menu) }
        activeDropdownAnchor = null
        activeDropdownView = null
        activeDropdownHost = null
    }
    private fun dropdown(selected:String, options:List<Pair<String,()->Unit>>, host:LinearLayout):TextView {
        val anchor=button("$selected  ▾",0xFF2A3A2F.toInt(),0xFFE7F0EA.toInt()) {}
        anchor.textSize=10f;anchor.includeFontPadding=false;anchor.setPadding(dp(3),dp(4),dp(3),dp(4))
        anchor.setOnClickListener {
            if (activeDropdownAnchor === anchor) {
                dismissDropdown()
                return@setOnClickListener
            }
            dismissDropdown()
            val rows=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5))}
            val selectedIndex=options.indexOfFirst{(title,_)->title==selected||selected.startsWith("$title ·")}
            options.forEachIndexed{index,entry->
                val(title,action)=entry;val chosen=index==selectedIndex
                rows.addView(TextView(uiContext).apply{
                    text=if(chosen)"$title   ✓" else title;textSize=12f;gravity=Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE);includeFontPadding=false;setPadding(dp(12),dp(5),dp(12),dp(5))
                    background=bg(if(chosen)0xFF347B4A.toInt() else Color.TRANSPARENT,8f);minHeight=dp(42);isSingleLine=true
                    setOnClickListener{dismissDropdown();action()}
                },LinearLayout.LayoutParams(-1,dp(42)))
                if(index<options.lastIndex)rows.addView(View(uiContext).apply{setBackgroundColor(0xFF39483E.toInt())},LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(2);bottomMargin=dp(2)})
            }
            val desiredHeight=min(dp(options.size*46+10),dp(260))
            val scroller=CappedScrollView(uiContext).apply{
                isFillViewport=false;clipChildren=true;clipToPadding=true
                maxHeightPx=dp(260)
                background=bg(0xFF1E2A22.toInt(),10f)
                addView(rows,FrameLayout.LayoutParams(-1,-2))
                isVerticalScrollBarEnabled=options.size>5
                overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val anchorLoc=IntArray(2);val hostLoc=IntArray(2)
            anchor.getLocationOnScreen(anchorLoc);host.getLocationOnScreen(hostLoc)
            val available=host.width.takeIf{it>0} ?: (panelWidthPx-dp(20)).coerceAtLeast(dp(80))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=12f*resources.displayMetrics.scaledDensity}
            val textWidth=options.maxOfOrNull{paint.measureText(it.first).roundToInt()} ?: 0
            val menuWidth=min(available,max(anchor.width, textWidth+dp(34))).coerceAtLeast(min(dp(80),available))
            val rtl=host.layoutDirection==View.LAYOUT_DIRECTION_RTL
            val anchorStart=anchorLoc[0]-hostLoc[0]
            val desiredStart=if(rtl)anchorStart+anchor.width-menuWidth else anchorStart
            val left=desiredStart.coerceIn(0,(available-menuWidth).coerceAtLeast(0))
            val insertion=(host.indexOfChild(anchor.parent as? View ?: return@setOnClickListener)+1).coerceIn(0,host.childCount)
            host.addView(scroller,insertion,LinearLayout.LayoutParams(menuWidth,desiredHeight).apply{
                gravity=Gravity.LEFT
                leftMargin=left
                topMargin=dp(6)
            })
            activeDropdownAnchor=anchor
            activeDropdownView=scroller
            activeDropdownHost=host
        }
        return anchor
    }
    private fun miniIconButton(symbol:String, action:()->Unit, tint:Int=0xFFE7F0EA.toInt()):TextView =
        TextView(uiContext).apply {
            text=symbol; textSize=16f; gravity=Gravity.CENTER; setTextColor(tint)
            background=bg(0xFF2A3A2F.toInt(),8f); includeFontPadding=false
            setPadding(0,0,0,0); minWidth=dp(44); minHeight=dp(44)
            setOnClickListener { action() }
        }
    private fun dottedButton(value:String, action:()->Unit):TextView =
        TextView(uiContext).apply {
            text=value; textSize=12f; gravity=Gravity.CENTER; setTextColor(0xFFA5C4AA.toInt())
            background=GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius=dp(8).toFloat()
                setStroke(dp(1),0xFF78907D.toInt(),dp(3).toFloat(),dp(3).toFloat())
            }
            setPadding(dp(8),dp(5),dp(8),dp(5)); minHeight=dp(36)
            setOnClickListener { action() }
        }
    private fun editRallyDuration() {
        val field=EditText(uiContext).apply {
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true); setText(rallyDurationMinutes.toString())
            setSelection(text.length); textDirection=View.TEXT_DIRECTION_LTR
        }
        val dialog=AlertDialog.Builder(uiContext)
            .setTitle(t("مدة حشد مخصصة بالدقائق","Custom rally duration in minutes"))
            .setView(field)
            .setNegativeButton(t("إلغاء","Cancel"),null)
            .setPositiveButton(t("حفظ","Save")) { _,_ ->
                field.text.toString().toIntOrNull()?.takeIf { it>0 && it<=Int.MAX_VALUE/60 }?.let {
                    rallyDurationMinutes=it; persist(); renderMenu()
                }
            }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
    private fun timelineRail(dotColor:Int=0xFF4CAF50.toInt()):View = object:View(uiContext) {
        private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF42634B.toInt(); strokeWidth=dp(1.5f).toFloat() }
        private val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=dotColor; style=Paint.Style.FILL }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val x=width/2f
            canvas.drawLine(x,0f,x,height.toFloat(),line)
            canvas.drawCircle(x,dp(15).toFloat(),dp(4.5f).toFloat(),dot)
        }
    }
    private inner class OverlayEditText(context:Context):EditText(context) {
        override fun onKeyPreIme(keyCode:Int,event:KeyEvent):Boolean {
            if(keyCode==KeyEvent.KEYCODE_BACK && this@RallyOverlayService.focusable) {
                if(event.action==KeyEvent.ACTION_UP)dismissIme(this)
                return true
            }
            return super.onKeyPreIme(keyCode,event)
        }
    }
    private fun dismissIme(field:EditText?) {
        val token=field?.windowToken ?: root?.windowToken
        if(token!=null)imm.hideSoftInputFromWindow(token,0)
        field?.clearFocus()
        if(focusedField===field)focusedField=null
    }
    private fun handleEditorAction(field:EditText,actionId:Int):Boolean {
        if(actionId==EditorInfo.IME_ACTION_DONE) { dismissIme(field);return true }
        if(actionId==EditorInfo.IME_ACTION_NEXT) {
            val next=field.focusSearch(View.FOCUS_FORWARD)
            if(next!=null) { next.requestFocus();if(next is EditText)next.post { if(next.hasFocus())imm.showSoftInput(next,InputMethodManager.SHOW_IMPLICIT) } }
            else dismissIme(field)
            return true
        }
        return false
    }
    private fun input(hint:String,value:String,numeric:Boolean=false):EditText {
        return OverlayEditText(uiContext).apply {
            setSingleLine(true); textSize=13f; setTextColor(Color.WHITE); setHintTextColor(0xFFA5C4AA.toInt()); setHint(hint); setText(value); contentDescription=hint
            background=bg(0xFF2A3A2F.toInt(),9f); setPadding(dp(9),0,dp(9),0)
            if(numeric) { inputType=android.text.InputType.TYPE_CLASS_NUMBER; keyListener=android.text.method.DigitsKeyListener.getInstance("0123456789"); imeOptions=EditorInfo.IME_ACTION_NEXT; textDirection=View.TEXT_DIRECTION_LTR; textAlignment=View.TEXT_ALIGNMENT_CENTER }
            else { inputType=android.text.InputType.TYPE_CLASS_TEXT; imeOptions=EditorInfo.IME_ACTION_NEXT; textDirection=if(ar())View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR }
            setOnTouchListener { v,event ->
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    if(!this@RallyOverlayService.focusable)setFocus(true)
                    v.post { if(v.isAttachedToWindow) { v.requestFocus();imm.showSoftInput(v,InputMethodManager.SHOW_IMPLICIT) } }
                }
                false
            }
            setOnFocusChangeListener { _,has ->
                if(has) {
                    focusedField=this;setFocus(true)
                    post { if(hasFocus()) { imm.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);scroll?.post { ensureFocusedFieldVisible(this) } } }
                } else {
                    if(focusedField===this)focusedField=null
                    if(numeric)finalizeTimeInput(this) {}
                }
            }
            setOnEditorActionListener { _,actionId,_ -> if(actionId==EditorInfo.IME_ACTION_NEXT && numeric)finalizeTimeInput(this) {};handleEditorAction(this,actionId) }
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
        field.setOnFocusChangeListener { _,has ->
            if(has) { focusedField=field;setFocus(true);scroll?.post { ensureFocusedFieldVisible(field) } }
            else { if(focusedField===field)focusedField=null;finalizeTimeInput(field,changed) }
        }
        field.setOnEditorActionListener { _,actionId,_ ->
            if(actionId==EditorInfo.IME_ACTION_NEXT)finalizeTimeInput(field,changed)
            handleEditorAction(field,actionId)
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
        val modeNow=uiContext.getString(R.string.mode_now)
        val modeTarget=uiContext.getString(R.string.mode_target)
        val prepOptions=listOf(5,10,20,30)
        val prepLabels=prepOptions.associateWith { if(ar())"تجهيز ${it}ث" else "Prep ${it}s" }
        val rallyPresets=listOf(1,3,5,10,15,30,60)
        val rallyLabels=rallyPresets.associateWith { if(ar())"حشد ${it}د" else "Rally ${it}m" }
        val selectors=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; direction(this) }
        selectors.addView(dropdown(if(calculationMode=="now")modeNow else modeTarget,listOf(
            modeNow to { calculationMode="now"; persist(); renderMenu() },
            modeTarget to { calculationMode="target"; persist(); renderMenu() }
        ),c),LinearLayout.LayoutParams(0,dp(42),1f))
        selectors.addView(Space(uiContext),LinearLayout.LayoutParams(dp(4),1))
        selectors.addView(dropdown(prepLabels[prepSeconds] ?: (if(ar())"تجهيز ${prepSeconds}ث" else "Prep ${prepSeconds}s"),prepOptions.map { value ->
            prepLabels.getValue(value) to { prepSeconds=value; persist(); renderMenu() }
        },c),LinearLayout.LayoutParams(0,dp(42),1f))
        selectors.addView(Space(uiContext),LinearLayout.LayoutParams(dp(4),1))
        val customLabel=if(ar())"مخصص" else "Custom"
        val selectedRally=rallyLabels[rallyDurationMinutes] ?: (if(ar())"مخصص · ${rallyDurationMinutes}د" else "Custom · ${rallyDurationMinutes}m")
        val rallyOptions=rallyPresets.map { value ->
            rallyLabels.getValue(value) to { rallyDurationMinutes=value; persist(); renderMenu() }
        } + (customLabel to { editRallyDuration() })
        selectors.addView(dropdown(selectedRally,rallyOptions,c),LinearLayout.LayoutParams(0,dp(42),1f))
        c.addView(selectors)
        if(calculationMode=="target") {
            val targetRow=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;direction(this)}
            val timeCol=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,-2,1.35f)}
            val hourCol=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,-2,.75f)}
            timeCol.addView(text(t("الوقت المستهدف · MM:SS","Target time · MM:SS"),9f,0xFFA5C4AA.toInt(),true),LinearLayout.LayoutParams(-1,dp(19)))
            targetInput=input("MM:SS",target,true).apply{contentDescription=t("الوقت المستهدف","Target time");textSize=12f}
            timeCol.addView(targetInput,LinearLayout.LayoutParams(-1,dp(42)))
            targetInput?.let{bindTimeInput(it){value->target=value;persist()}}
            hourCol.addView(text(uiContext.getString(R.string.utc_hour_optional)+" · HH",9f,0xFFA5C4AA.toInt(),true),LinearLayout.LayoutParams(-1,dp(19)))
            targetHourInput=input("HH",targetHour,true).apply{textSize=12f}
            hourCol.addView(targetHourInput,LinearLayout.LayoutParams(-1,dp(42)))
            targetHourInput?.addTextChangedListener(SimpleWatcher{targetHour=it.filter{ch->ch.isDigit()}.take(2);persist()})
            targetRow.addView(timeCol);targetRow.addView(Space(uiContext),LinearLayout.LayoutParams(dp(7),1));targetRow.addView(hourCol)
            c.addView(targetRow,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
        }
        label(c,t("المشاركون · الاسم ووقت المسير · النوع","Participants · Name and march time · Type"))
        val participantRows=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;clipChildren=true;clipToPadding=true;direction(this)}
        participantRowsScroll=null
        people.forEachIndexed { index,p ->
            val card=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;direction(this);setPadding(dp(6),dp(5),dp(6),dp(5));background=bg(0xFF26382C.toInt(),9f)}
            val first=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;direction(this)}
            val name=input(t("الاسم","Name"),p.name).apply{textSize=12f;contentDescription=t("مشارك ${index+1} · الاسم","Participant ${index+1} · name")}
            val duration=input("MM:SS",p.duration,true).apply{textSize=12f;contentDescription=t("مشارك ${index+1} · وقت المسير","Participant ${index+1} · march time");imeOptions=if(index==people.lastIndex)EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT}
            name.addTextChangedListener(SimpleWatcher{p.name=it;persist();if(focusedField===name)miniEditorValue?.text=it})
            bindTimeInput(duration){value->p.duration=value;persist()}
            first.addView(name,LinearLayout.LayoutParams(0,dp(40),1f));first.addView(Space(uiContext),LinearLayout.LayoutParams(dp(6),1));first.addView(duration,LinearLayout.LayoutParams(dp(76),dp(40)))
            card.addView(first)
            val second=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;direction(this)}
            val kind=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;direction(this)}
            kind.addView(button(uiContext.getString(R.string.rally),if(p.kind=="rally")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.kind=="rally")0xFF102014.toInt() else Color.WHITE){p.kind="rally";persist();renderMenu()}.apply{textSize=10f;setPadding(dp(3),0,dp(3),0);minHeight=dp(44)},LinearLayout.LayoutParams(0,dp(44),1f))
            kind.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
            kind.addView(button(uiContext.getString(R.string.solo),if(p.kind=="solo")0xFF4CAF50.toInt() else 0xFF2A3A2F.toInt(),if(p.kind=="solo")0xFF102014.toInt() else Color.WHITE){p.kind="solo";persist();renderMenu()}.apply{textSize=10f;setPadding(dp(3),0,dp(3),0);minHeight=dp(44)},LinearLayout.LayoutParams(0,dp(44),1f))
            second.addView(kind,LinearLayout.LayoutParams(0,dp(44),1f));second.addView(Space(uiContext),LinearLayout.LayoutParams(dp(8),1))
            val actions=LinearLayout(uiContext).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
            actions.addView(miniIconButton("✎",{name.requestFocus();name.setSelection(name.text.length);setFocus(true)}),LinearLayout.LayoutParams(dp(44),dp(44)))
            actions.addView(Space(uiContext),LinearLayout.LayoutParams(dp(8),1))
            actions.addView(miniIconButton("×",{people.removeAll{it.id==p.id};if(people.isEmpty())people.add(Person(nextId++,"",""));persist();renderMenu()},0xFFFF7777.toInt()),LinearLayout.LayoutParams(dp(44),dp(44)))
            second.addView(actions,LinearLayout.LayoutParams(dp(96),dp(44)));card.addView(second,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(4)})
            participantRows.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(5)})
        }
        c.addView(participantRows,LinearLayout.LayoutParams(-1,-2))
        c.addView(dottedButton(t("+ إضافة مشارك","+ Add participant")){people.add(Person(nextId++,"",""));persist();renderMenu()},LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(3)})
        errors.forEach{c.addView(text(it,10f,0xFFE57373.toInt()))}
    }
    private fun utcTime(epoch:Long):String = RallyTime.utcClock(epoch)
    private fun countdown(ms:Long):String {
        val sec=((ms+999L)/1000L).coerceAtLeast(0L)
        return String.format(Locale.US,"%02d:%02d",sec/60,sec%60)
    }
    private fun copyRowText(r:ResultRow):String =
        "${r.name} — ابدأ الحشد ${utcTime(r.actionAt)} UTC"
    private fun copyRow(r:ResultRow) = copy(copyRowText(r))
    private fun updateResultCountdowns(now: Long = UtcClockTicker.currentTimeMillis()) {
        if(!resultsMode||resultCountdowns.isEmpty())return
        results.forEach { r ->
            val label=resultCountdowns[r.id]?:return@forEach
            val remaining=r.actionAt-now
            val status=if(remaining>0) {
                label.setTextColor(if(remaining<=10_000L)0xFFFF9100.toInt() else 0xFFA5C4AA.toInt())
                if(ar())"بعد ${countdown(remaining)}" else "In ${countdown(remaining)}"
            } else {
                val zeroAt=resultZeroAt.getOrPut(r.id){now}
                label.setTextColor(if(now-zeroAt<3000L)0xFF4CAF50.toInt() else 0xFF78907D.toInt())
                uiContext.getString(R.string.now_label)
            }
            label.text="${r.name} · ${uiContext.getString(R.string.start_rally)} · $status"
            resultRowsViews[r.id]?.alpha=if(remaining>0 || now-resultZeroAt.getOrDefault(r.id,now)<3000L)1f else 0.65f
            if(remaining<=0 && vibrationEnabled&&vibratedIds.add(r.id)) {
                runCatching {
                    val vibrator=if(Build.VERSION.SDK_INT>=31)getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
                    if(Build.VERSION.SDK_INT>=26)vibrator.vibrate(VibrationEffect.createOneShot(70,VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(70)
                }
            }
        }
    }
    private fun buildResults(c:LinearLayout) {
        resultCountdowns.clear();resultRowsViews.clear()
        resultActionTimesViews.clear();resultDepartureTimesViews.clear();resultArrivalTimeView=null
        results.forEachIndexed { index,r ->
            val item=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; direction(this) }
            item.addView(timelineRail(),LinearLayout.LayoutParams(dp(18),dp(78)))
            val details=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; direction(this) }
            val timeLine=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_LTR }
            val actionTimeView=text(utcTime(r.actionAt),19f,Color.WHITE,true).apply { textDirection=View.TEXT_DIRECTION_LTR }
            resultActionTimesViews[r.id]=actionTimeView
            timeLine.addView(actionTimeView)
            timeLine.addView(text("UTC",9f,0xFFA5C4AA.toInt(),true).apply { textDirection=View.TEXT_DIRECTION_LTR },LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(5)})
            details.addView(timeLine,LinearLayout.LayoutParams(-1,dp(25)))
            val status=text("",10f,0xFFA5C4AA.toInt(),true).apply { textDirection=View.TEXT_DIRECTION_FIRST_STRONG }
            resultCountdowns[r.id]=status
            details.addView(status,LinearLayout.LayoutParams(-1,dp(19)))
            if(r.isRally) {
                val departureView=text(t("ينطلق ","Departs ")+utcTime(r.departAt),10f,0xFFA5C4AA.toInt()).apply{textDirection=View.TEXT_DIRECTION_LTR}
                resultDepartureTimesViews[r.id]=departureView
                details.addView(departureView,LinearLayout.LayoutParams(-1,dp(16)))
            } else details.addView(Space(uiContext),LinearLayout.LayoutParams(-1,dp(16)))
            if(index<results.lastIndex) {
                val next=results[index+1]
                val gap=((next.actionAt-r.actionAt)/1000L).coerceAtLeast(0L)
                details.addView(text(String.format(Locale.US,uiContext.getString(R.string.after_player_by),r.name,countdown(gap*1000)),9f,0xFFA5C4AA.toInt()))
            }
            item.addView(details,LinearLayout.LayoutParams(0,dp(78),1f))
            item.addView(button(t("نسخ","Copy"),0xFF2A3A2F.toInt(),Color.WHITE){copyRow(r)},LinearLayout.LayoutParams(dp(48),dp(30)))
            resultRowsViews[r.id]=item
            c.addView(item,LinearLayout.LayoutParams(-1,dp(78)))
        }
        errors.forEach{c.addView(text(it,10f,0xFFE57373.toInt()))}
        val arrival=results.firstOrNull()?.arrivalAt
        if(arrival!=null) {
            val finalRow=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; direction(this) }
            finalRow.addView(timelineRail(0xFF9BE7B0.toInt()),LinearLayout.LayoutParams(dp(18),dp(52)))
            val finalInfo=LinearLayout(uiContext).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL }
            finalInfo.addView(text(t("وصول الجميع","Everyone arrives"),12f,0xFF9BE7B0.toInt(),true))
            val finalTime=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_LTR }
            val arrivalTimeView=text(utcTime(arrival),18f,Color.WHITE,true).apply{textDirection=View.TEXT_DIRECTION_LTR}
            resultArrivalTimeView=arrivalTimeView
            finalTime.addView(arrivalTimeView)
            finalTime.addView(text("UTC",9f,0xFFA5C4AA.toInt(),true),LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(5)})
            finalInfo.addView(finalTime)
            finalRow.addView(finalInfo,LinearLayout.LayoutParams(0,dp(52),1f))
            c.addView(finalRow,LinearLayout.LayoutParams(-1,dp(52)))
        }
        c.addView(button(t("نسخ الكل","Copy all"),0xFF4CAF50.toInt(),0xFF102014.toInt()){copy(results.joinToString("\n"){r->copyRowText(r)})},LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(8)})
        val actions=LinearLayout(uiContext).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; direction(this) }
        actions.addView(button(t("حساب من جديد","Calculate again"),0xFF4CAF50.toInt(),0xFF102014.toInt()){calculationMode="now";resultsMode=false;calculate()},LinearLayout.LayoutParams(0,dp(36),1f))
        actions.addView(Space(uiContext),LinearLayout.LayoutParams(dp(6),1))
        actions.addView(button(t("رجوع للتعديل","Back to edit"),0xFF2A3A2F.toInt(),Color.WHITE){resultsMode=false;persist();renderMenu()},LinearLayout.LayoutParams(0,dp(36),1f))
        c.addView(actions,LinearLayout.LayoutParams(-1,dp(36)).apply{topMargin=dp(6)})
        updateResultCountdowns()
        // Countdown refreshes come only from the shared second-boundary UTC ticker.
    }
    private fun calculate() {
        errors.clear()
        val valid=mutableListOf<RallyInput>()
        people.forEachIndexed{i,p->
            if(p.name.isBlank()){errors.add(t("أدخل اسم اللاعب للمشارك ","Enter a player name for participant ")+(i+1));return@forEachIndexed}
            val march=parse(p.duration)
            if(march==null){errors.add(t("مدة المسيرة غير صالحة للمشارك: ","Invalid march duration for: ")+p.name);return@forEachIndexed}
            valid.add(RallyInput(p.id,p.name.trim(),march,if(p.kind=="rally")rallyDurationMinutes*60 else 0))
        }
        if(valid.isEmpty()){resultsMode=false;renderMenu();return}
        val now=UtcClockTicker.currentTimeMillis()
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
                (scroll?.getChildAt(0) as? LinearLayout)?.addView(postpone,LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(5)})
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
        val panel=menuView
        val p=panelParams
        if(panel==null||p==null){if(!on)focusable=false;return}
        if(focusable==on)return
        focusable=on
        p.flags=if(on)p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        runCatching { wm.updateViewLayout(panel,p) }
        if(on)focusedField?.let { field -> field.post { if(field.hasFocus())imm.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) } }
    }
    override fun onDestroy(){running=false;sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName));Choreographer.getInstance().removeFrameCallback(dragFrameCallback);UtcClockTicker.removeListener(clockListener);UtcClockTicker.removeCalibrationListener(calibrationListener);runCatching{unregisterReceiver(localeReceiver)};handler.removeCallbacksAndMessages(null);removePanelWindow();root?.let{runCatching{wm.removeView(it)}};root=null
        if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE)else @Suppress("DEPRECATION") stopForeground(true);super.onDestroy()}
    companion object { @Volatile var running:Boolean=false; private set; const val ACTION_STATE_CHANGED="com.mythic.rally.STATE_CHANGED"; const val ACTION_STOP="com.mythic.rally.STOP"; const val ACTION_LANGUAGE_CHANGED="com.mythic.rally.LANGUAGE_CHANGED"; private const val TAG="RallyOverlay" }
    private class SimpleWatcher(val changed:(String)->Unit):android.text.TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed(s?.toString()?: "")}
        override fun afterTextChanged(s:android.text.Editable?){}
    }
}
