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
    private data class Person(val id:Int,var name:String,var duration:String)
    private data class ResultRow(val id:Int,val name:String,val launch:Int)
    private val localeReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            uiContext=localizedContext()
            if(root!=null){if(menuOpen)showMenu()else buildCollapsed();startForeground(27,notification())}
        }
    }
    private val clockTick = object: Runnable { override fun run() { updateClock(); handler.postDelayed(this,1000) } }

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
        bubbleX=prefs.getInt("x",24); bubbleY=prefs.getInt("y",180)
        menuOpen=prefs.getBoolean("open",false); resultsMode=prefs.getBoolean("results",false); settingsMode=prefs.getBoolean("settings",false)
        nextId=prefs.getInt("next",1)
        runCatching {
            val a=JSONArray(prefs.getString("people","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);people.add(Person(o.getInt("id"),o.optString("name"),o.optString("duration")))}
        }
        if(people.isEmpty()) people.add(Person(nextId++,"",""))
        runCatching {
            val a=JSONArray(prefs.getString("result_rows","[]"))
            for(i in 0 until a.length()){val o=a.getJSONObject(i);results.add(ResultRow(o.getInt("id"),o.optString("name"),o.getInt("launch")))}
        }
    }
    private fun persist() {
        val a=JSONArray(); people.forEach { a.put(JSONObject().put("id",it.id).put("name",it.name).put("duration",it.duration)) }
        val r=JSONArray(); results.forEach { r.put(JSONObject().put("id",it.id).put("name",it.name).put("launch",it.launch)) }
        prefs.edit().putString("target",target).putString("people",a.toString()).putString("result_rows",r.toString())
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
        bubbleX=bubbleX.coerceIn(0,(screenW-104).coerceAtLeast(0)); bubbleY=bubbleY.coerceIn(((statusInsetPx+dp(50))/density).roundToInt(),(screenH-55).coerceAtLeast(((statusInsetPx+dp(50))/density).roundToInt()))
        val frame=FrameLayout(uiContext).apply { clipChildren=false; clipToPadding=false; direction(this) }
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
    private fun buildCollapsed() { val f=root?:return;f.removeAllViews();f.clipChildren=false;f.clipToPadding=false;addPinnedControls(f);updateClock();applyLayout() }
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
        if (Build.VERSION.SDK_INT >= 30) view.setOnApplyWindowInsetsListener { _, insets ->
            val bars=insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            statusInsetPx=bars.top
            navInsetPx=insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            imeHeightPx=insets.getInsets(WindowInsets.Type.ime()).bottom
            resizeForInsets()
            insets
        }
        val listener=ViewTreeObserver.OnGlobalLayoutListener {
            val rect=Rect(); view.getWindowVisibleDisplayFrame(rect)
            val fallback=(resources.displayMetrics.heightPixels-rect.bottom-navInsetPx).coerceAtLeast(0)
            val next=if(fallback>dp(80))fallback else 0
            if(next!=imeHeightPx){imeHeightPx=next;resizeForInsets()}
        }
        globalLayoutListener=listener
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.requestApplyInsets()
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
        p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
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
        val panel=menuView
        if(panel!=null) {
            panel.animate().alpha(0f).setDuration(150).withEndAction {
                menuOpen=false;persist();menuView=null;buildCollapsed();applyLayout()
            }.start()
        } else { menuOpen=false;persist();buildCollapsed();applyLayout() }
    }
    private fun showMenu() {
        val f=root?:return;f.removeAllViews();f.clipChildren=false;f.clipToPadding=false;addPinnedControls(f)
        val panel=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;background=bg(0xFF1E2A22.toInt(),12f);setPadding(dp(10),dp(8),dp(10),dp(9));clipChildren=false;clipToPadding=false;direction(this)}
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
        val sc=CappedScrollView(uiContext).apply{isFillViewport=false;clipChildren=false;clipToPadding=false;direction(this)};scroll=sc
        val content=LinearLayout(uiContext).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false;direction(this)}
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
        label(c,t("الوقت المستهدف","Target time"))
        targetInput=input("MM:SS",target,true).apply{contentDescription=t("الوقت المستهدف","Target time")}
        c.addView(targetInput,LinearLayout.LayoutParams(-1,dp(38)))
        targetInput?.let { bindTimeInput(it) { value -> target=value; persist() } }
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
            val actions=LinearLayout(uiContext).apply{gravity=if(ar())Gravity.END else Gravity.START}
            actions.addView(button(t("تعديل","Edit"),0xFF2A3A2F.toInt(),Color.WHITE){name.requestFocus();name.setSelection(name.text.length);setFocus(true)})
            actions.addView(Space(uiContext),LinearLayout.LayoutParams(dp(5),1))
            actions.addView(button(t("حذف","Delete"),0xFFE57373.toInt(),Color.WHITE){people.removeAll{it.id==p.id};if(people.isEmpty())people.add(Person(nextId++,"",""));persist();renderMenu()})
            row.addView(actions);c.addView(row)
            if(index<people.lastIndex)c.addView(View(uiContext).apply{setBackgroundColor(0xFF405447.toInt())},LinearLayout.LayoutParams(-1,dp(1)))
        }
        c.addView(button(t("+ إضافة مشارك","+ Add participant"),0xFF2A3A2F.toInt(),0xFFA5C4AA.toInt()){people.add(Person(nextId++,"",""));persist();renderMenu();scroll?.post{scroll?.fullScroll(View.FOCUS_DOWN)}})
        errors.forEach{c.addView(text(it,11f,0xFFE57373.toInt()))}
    }
    private fun buildResults(c:LinearLayout) {
        label(c,t("وقت الوصول المستهدف","Target arrival time")+" · "+format(parse(target)?:0))
        results.forEach { r ->
            val row=LinearLayout(uiContext).apply{gravity=Gravity.CENTER_VERTICAL}
            row.addView(text(r.name,12f,Color.WHITE,true),LinearLayout.LayoutParams(0,dp(38),1f))
            row.addView(text(if(r.launch<0)"—" else format(r.launch),14f,0xFF4CAF50.toInt(),true))
            row.addView(button(t("نسخ","Copy"),0xFF2A3A2F.toInt(),Color.WHITE){copy("${r.name} — ${if(r.launch<0) "−"+format(-r.launch) else format(r.launch)}")})
            c.addView(row)
            if(r.launch<0)c.addView(text(t("وقت الإرسال سالب للمشارك: ","Negative launch time for: ")+r.name,10f,0xFFE57373.toInt()))
        }
        c.addView(button(t("نسخ الكل","Copy all"),0xFF2A3A2F.toInt(),Color.WHITE){copy(results.joinToString("\n"){"${it.name} — ${if(it.launch<0)"−"+format(-it.launch)else format(it.launch)}"})})
        c.addView(button(t("رجوع للتعديل","Back to edit"),0xFF4CAF50.toInt(),0xFF102014.toInt()){resultsMode=false;persist();renderMenu()})
    }
    private fun calculate() {
        errors.clear()
        val goal=parse(target)
        if(goal==null){errors.add(t("أدخل الوقت المستهدف بصيغة MM:SS (حتى 60:00).","Enter a target time up to 60:00."));renderMenu();targetInput?.requestFocus();return}
        val rows=mutableListOf<ResultRow>()
        people.forEachIndexed{i,p->
            if(p.name.isBlank()){errors.add(t("أدخل اسم اللاعب للمشارك ","Enter a player name for participant ")+(i+1));return@forEachIndexed}
            val d=parse(p.duration)
            if(d==null){errors.add(t("مدة المسيرة غير صالحة للمشارك: ","Invalid march duration for: ")+p.name);return@forEachIndexed}
            rows.add(ResultRow(p.id,p.name.trim(),goal-d))
        }
        if(rows.isEmpty()){renderMenu();return}
        results=rows;resultsMode=true;persist();renderMenu()
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
