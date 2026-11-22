package com.ghostgrid.overclock

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.widget.Toast
import java.net.NetworkInterface

object ConsoleCommands {

    fun run(ctx: Context, raw: String): String {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return ""
        val head = cmd.split(" ").first().lowercase()
        val args = cmd.removePrefix(head).trim()

        return when (head) {
            "help" -> "open <app> · boost · killall · status · torch on/off · stealth on/off · ip · hud off · dock"
            "boost" -> { HudActions.boost(ctx); "boost applied ⚡" }
            "killall" -> { HudActions.killBackground(ctx); "background purged ✔" }
            "status" -> {
                val s = StatsReader.read(ctx)
                "cpu:${s.cpuPct}% ram:${s.ramPct}% net:${s.netKbps}K/s " +
                "temp:${s.tempC}°C bat:${s.batPct}% fps:" + (if (s.fps > 0) "${s.fps}" else "--")
            }
            "torch" -> HudActions.torch(ctx, args != "off")
            "flash" -> HudActions.torch(ctx, args != "off")
            "stealth" -> {
                val on = args != "off"
                HudService.instance?.setStealth(on)
                if (on) "stealth on — HUD only" else "stealth off — full rig restored"
            }
            "ip" -> "ip: ${HudActions.localIp()}"
            "open" -> HudActions.openApp(ctx, args)
            "hud" -> {
                if (args == "off") { ctx.stopService(Intent(ctx, HudService::class.java)); "hud offline" }
                else "usage: hud off"
            }
            "dock" -> { HudActions.openLauncher(ctx); "dock open" }
            else -> "unknown cmd — type help"
        }
    }
}

object HudActions {

    fun boost(ctx: Context) {
        killBackground(ctx)
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; " +
                "do echo performance > \$g; done")).waitFor()
        }
        toast(ctx, "⚡ SYSBOOST — governor=performance, RAM purged")
    }

    fun killBackground(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ctx.packageManager.getInstalledApplications(0).forEach { am.killBackgroundProcesses(it.packageName) }
    }

    fun torch(ctx: Context, on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.setTorchMode(id, on)
            if (on) "torch ON 🔦" else "torch off"
        } catch (e: Exception) { "torch failed (no flash?)" }
    }

    fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }
                ?.hostAddress ?: "no wifi ip"
        } catch (e: Exception) { "unknown" }
    }

    fun openApp(ctx: Context, name: String): String {
        if (name.isEmpty()) return "usage: open <app name>"
        val pm = ctx.packageManager
        val candidates = pm.getInstalledApplications(0).filter {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label.contains(name.lowercase()) || it.packageName.contains(name.lowercase())
        }
        val target = candidates.firstOrNull() ?: return "app not found: $name"
        val intent = pm.getLaunchIntentForPackage(target.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (intent != null) { ctx.startActivity(intent); "launching ${pm.getApplicationLabel(target)}" }
               else "no launcher activity"
    }

    fun openLauncher(ctx: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(home)
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
