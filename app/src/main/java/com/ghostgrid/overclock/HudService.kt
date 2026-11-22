package com.ghostgrid.overclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class HudService : Service() {

    companion object {
        var instance: HudService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private var hudView: View? = null
    private var consoleView: View? = null
    private var tabView: View? = null
    private var fanView: FanView? = null
    private var consoleLp: WindowManager.LayoutParams? = null
    private var tabLp: WindowManager.LayoutParams? = null
    private var stealthOn = false
    private val handler = Handler(Looper.getMainLooper())

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        StatsReader.prime(this)
        addHud()
        addConsole()
        addEdgeTab()
        handler.post(updateLoop)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateLoop)
        listOf(hudView, consoleView, tabView, fanView).forEach { v ->
            v?.let { runCatching { wm.removeView(it) } }
        }
        instance = null
        super.onDestroy()
    }

    // ---------- stealth mode: hide console + tab, keep HUD ----------
    fun setStealth(on: Boolean) {
        stealthOn = on
        if (on) {
            consoleView?.let { runCatching { wm.removeView(it) } }
            tabView?.let { runCatching { wm.removeView(it) } }
            if (fanOpen) toggleFan()
        } else {
            if (consoleView?.windowToken == null && consoleLp != null)
                consoleView?.let { runCatching { wm.addView(it, consoleLp) } }
            if (tabView?.windowToken == null && tabLp != null)
                tabView?.let { runCatching { wm.addView(it, tabLp) } }
        }
    }

    // ---------- foreground notification ----------
    private fun startForegroundWithNotification() {
        val ch = NotificationChannel("hud", "HUD", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, "hud")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("HUD//OVERCLOCK active")
            .setContentText(if (stealthOn) "stealth" else "tap to configure")
            .setContentIntent(pi)
            .build()
        ServiceCompat.startForeground(this, 1, notif,
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    // ---------- the main HUD bar ----------
    private fun addHud() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_hud, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 300 }

        attachDrag(v, lp, snapEdge = true,
            onTap = { HudActions.boost(this) },
            onHold = { HudActions.killBackground(this); toast("kill-all ✔") }
        )
        wm.addView(v, lp)
        hudView = v
    }

    // ---------- console bar ----------
    private fun addConsole() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_console, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 120 }

        val input = v.findViewById<EditText>(R.id.consoleInput)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val out = ConsoleCommands.run(this, input.text.toString())
                if (out.isNotEmpty()) toast(out)
                input.setText("")
                true
            } else false
        }
        wm.addView(v, lp)
        consoleView = v
        consoleLp = lp
    }

    // ---------- stealth edge tab + fan ----------
    private fun addEdgeTab() {
        val v = LayoutInflater.from(this).inflate(R.layout.view_edgetab, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; x = 0; y = -100 }

        attachDrag(v, lp, snapEdge = true,
            onTap = { toggleFan() },
            onHold = { toast("drag to reposition · releases snap to edge") }
        )
        wm.addView(v, lp)
        tabView = v
        tabLp = lp
    }

    private var fanOpen = false
    private fun toggleFan() {
        if (fanOpen) { fanView?.let { runCatching { wm.removeView(it) } }; fanView = null; fanOpen = false; return }
        val fan = FanView(this)
        val pm = packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).take(5)
        launchables.forEach { ri ->
            val item = LayoutInflater.from(this).inflate(R.layout.fan_item, fan, false)
            item.findViewById<ImageView>(R.id.fanIcon)
                .setImageDrawable(pm.getApplicationIcon(ri.activityInfo.packageName))
            item.setOnClickListener {
                pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                toggleFan()
            }
            fan.addView(item)
        }
        val lp = WindowManager.LayoutParams(
            620, 620, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        wm.addView(fan, lp)
        fanView = fan; fanOpen = true
    }

    // ---------- shared drag / tap / hold + magnetic edge snap ----------
    private fun attachDrag(v: View, lp: WindowManager.LayoutParams,
                           snapEdge: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var held = false
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        val screenH = Resources.getSystem().displayMetrics.heightPixels
        val holdRunnable = Runnable {
            held = true
            onHold()
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; held = false
                    handler.postDelayed(holdRunnable, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dx*dx + dy*dy > 64) { moved = true; handler.removeCallbacks(holdRunnable) }
                    lp.x = startX + dx.toInt()
                    lp.y = (startY + dy.toInt()).coerceIn(0, screenH - v.height)
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(holdRunnable)
                    if (moved && snapEdge) {
                        // magnetic snap: pin to nearest left/right edge, keep y
                        val center = lp.x + v.width / 2
                        lp.x = if (center < screenW / 2) 0 else screenW - v.width
                        wm.updateViewLayout(v, lp)
                    }
                    if (!moved && !held) onTap()
                }
            }
            true
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- live stats loop ----------
    private val updateLoop = object : Runnable {
        override fun run() {
            hudView?.let { v ->
                val s = StatsReader.read(this@HudService)
                v.findViewById<ProgressBar>(R.id.cpuBar)?.progress = s.cpuPct
                v.findViewById<ProgressBar>(R.id.ramBar)?.progress = s.ramPct
                v.findViewById<TextView>(R.id.cpuText)?.text = "${s.cpuPct}%"
                v.findViewById<TextView>(R.id.ramText)?.text = "${s.ramPct}%"
                v.findViewById<TextView>(R.id.netText)?.text = "${s.netKbps}K"
                v.findViewById<TextView>(R.id.tempText)?.text = "${s.tempC}°"
                v.findViewById<TextView>(R.id.batText)?.text =
                    if (s.batPct >= 0) "${s.batPct}%" else "--"
                v.findViewById<TextView>(R.id.fpsText)?.text =
                    "fps:" + (if (s.fps > 0) "${s.fps}" else "--") + "  zram:${s.zramPct}%"
            }
            handler.postDelayed(this, 800)
        }
    }
}
