package com.ghostgrid.overclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("boot_start", false)
        if (enabled) {
            ContextCompat.startForegroundService(context, Intent(context, HudService::class.java))
        }
    }
}
