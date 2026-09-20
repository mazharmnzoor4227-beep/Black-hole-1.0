package com.blackhole.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.text.format.Formatter
import android.view.*
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val scope = MainScope()
    private var observer: Job? = null
    private lateinit var hole: HoleView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private var sharedLink: String? = null
    private var lastClipboard: String? = null
    private var currentLink: String? = null
    private var historyShown = false
    private var pendingPermission = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String = "", size: Float = 11f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        letterSpacing = .12f; setPadding(dp(8),dp(8),dp(8),dp(8))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sharedLink = savedInstanceState?.getString("shared")
        currentLink = savedInstanceState?.getString("current")
        if(savedInstanceState == null) acceptIntent(intent)
        showHome()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("shared",sharedLink); outState.putString("current", currentLink); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent); if(historyShown) showHome() }
    private fun acceptIntent(intent: Intent) { if(intent.action == Intent.ACTION_SEND) { sharedLink = Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT)); currentLink = sharedLink } }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if(hasFocus && !historyShown) readClipboard() }
    private fun readClipboard() {
        if(sharedLink != null) return
        runCatching {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = if(clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
            val link = Links.extract(text)
            if(link != lastClipboard) { lastClipboard = link; if(link != null) currentLink = link }
        }
    }
    override fun onStart() {
        super.onStart()
        observer = scope.launch { Transfer.state.collect { if(!historyShown) render(it) } }
    }
    override fun onStop() { observer?.cancel(); if(::hole.isInitialized) hole.animateHole(false); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun showHome() {
        historyShown = false
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val side = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) - dp(24)
        hole = HoleView(this)
        root.addView(hole, FrameLayout.LayoutParams(side,side,Gravity.CENTER))
        val stack = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        status=label(); detail=label(size=10f)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; isIndeterminate=false; progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(35,35,35))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        stack.addView(status)
        stack.addView(progress,LinearLayout.LayoutParams(dp(180),dp(2)))
        stack.addView(detail)
        root.addView(stack,FrameLayout.LayoutParams(-1,dp(120),Gravity.CENTER).apply { topMargin = (side * .76f).toInt() })
        val history=label("HISTORY",10f).apply { contentDescription="Download history"; setOnClickListener { showHistory() } }
        root.addView(history,FrameLayout.LayoutParams(dp(90),dp(48),Gravity.BOTTOM or Gravity.END).apply { bottomMargin=dp(24); rightMargin=dp(16) })
        hole.setOnClickListener { startDownload() }
        setContentView(root)
        render(Transfer.state.value)
    }
    private fun render(s: TransferState) {
        hole.animateHole(s.busy)
        progress.visibility=if(s.phase in listOf(Phase.DOWNLOADING,Phase.SAVING,Phase.COMPLETE,Phase.ANALYZING)) View.VISIBLE else View.INVISIBLE
        progress.progress=s.percent ?: 0
        progress.contentDescription=if(s.percent == null) "Size unknown" else "${s.percent} percent"
        status.text=when(s.phase) {
            Phase.IDLE -> ""
            Phase.ANALYZING -> "ANALYZING VIDEO"
            Phase.DOWNLOADING -> s.percent?.let { "DOWNLOADING $it%" } ?: "DOWNLOADING"
            Phase.SAVING -> "SAVING VIDEO"
            Phase.COMPLETE -> "DOWNLOAD COMPLETE"
            Phase.ERROR -> s.message
        }
        detail.text=if(s.phase == Phase.COMPLETE) "100% · SAVED TO GALLERY / DOWNLOADS\n${s.detail}" else s.detail
    }
    private fun startDownload() {
        if(Transfer.state.value.busy) return
        readClipboard()
        val link=sharedLink ?: currentLink
        if(link == null) {
            status.text="COPY A VIDEO LINK FIRST"
            scope.launch { delay(2200); if(!historyShown && !Transfer.state.value.busy) render(Transfer.state.value) }; return
        }
        currentLink=link
        if(Build.VERSION.SDK_INT == 28 && (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            pendingPermission=true; requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),28); return
        }
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("notificationAsked",false)) {
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply()
            pendingPermission=true; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),33); return
        }
        sharedLink=null
        try { startForegroundService(Intent(this, DownloadService::class.java).putExtra("url",link)) }
        catch(e: Exception) { Transfer.update(TransferState(Phase.ERROR,message="CANNOT START DOWNLOAD. REOPEN THE APP")) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(!pendingPermission) return
        pendingPermission=false
        if(requestCode == 28 && (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED })) { status.text="ALLOW STORAGE PERMISSION TO SAVE VIDEO"; return }
        startDownload()
    }
    private fun showHistory() {
        historyShown=true; hole.animateHole(false)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(20),dp(36),dp(20),dp(28)) }
        val back=label("‹  HISTORY",12f).apply { gravity=Gravity.START; minHeight=dp(48); setOnClickListener { showHome() } }
        layout.addView(back)
        val scroll=ScrollView(this); val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list); layout.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(layout)
        scope.launch {
            val items=withContext(Dispatchers.IO) { HistoryStore(this@MainActivity).use { it.list() } }
            if(!historyShown) return@launch
            if(items.isEmpty()) list.addView(label("NO DOWNLOADS YET"))
            for(item in items) {
                val row=label("${item.title}\n${item.source} · ${item.quality}\n${Formatter.formatFileSize(this@MainActivity,item.bytes)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.date))}",12f).apply {
                    gravity=Gravity.START; letterSpacing=.02f; setPadding(0,dp(20),0,dp(20)); setOnClickListener { itemActions(item) }
                }
                list.addView(row)
            }
        }
    }
    private fun itemActions(item: HistoryItem) {
        AlertDialog.Builder(this).setTitle(item.title).setItems(arrayOf("Open / Play","Share","Delete")) { _, which ->
            val uri=Uri.parse(item.uri)
            when(which) {
                0 -> runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { Toast.makeText(this,"Video missing or no video player installed",Toast.LENGTH_SHORT).show() }
                1 -> runCatching {
                    val share=Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    share.clipData=ClipData.newRawUri("Video",uri); startActivity(Intent.createChooser(share,"Share video"))
                }.onFailure { Toast.makeText(this,"Unable to share this video",Toast.LENGTH_SHORT).show() }
                2 -> AlertDialog.Builder(this).setMessage("Delete this downloaded video?").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ ->
                    scope.launch {
                        val ok=withContext(Dispatchers.IO) { runCatching { contentResolver.delete(uri,null,null); HistoryStore(this@MainActivity).use { it.remove(item.id) } }.isSuccess }
                        if(ok && historyShown) showHistory() else if(!ok) Toast.makeText(this@MainActivity,"Cannot delete this file",Toast.LENGTH_SHORT).show()
                    }
                }.show()
            }
        }.show()
    }
    @Deprecated("Legacy back supports all target devices")
    override fun onBackPressed() { if(historyShown) showHome() else super.onBackPressed() }
}
