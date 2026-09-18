package com.droid.flippy

import android.app.Application
import android.content.Context
import com.droid.flippy.plugin.PluginManager
import com.droid.flippy.plugin.PluginDownloadPolicy

class FlippyApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        PluginDownloadPolicy.initialize(this)
        try {
            com.droid.flippy.shizuku.ShizukuManager.init(this)
        } catch (t: Throwable) {
            android.util.Log.w("FlippyApplication", "Shizuku init failed", t)
        }
        try {
            com.droid.flippy.ir.usb.UsbIrManager.init(this)
        } catch (t: Throwable) {
            android.util.Log.w("FlippyApplication", "USB IR init failed", t)
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val log = buildString {
                append("Thread: ")
                append(thread.name)
                append('\n')
                append("Time: ")
                append(java.time.Instant.now().toString())
                append('\n')
                append(android.util.Log.getStackTraceString(throwable))
            }
            runCatching { java.io.File(filesDir, "crash_stack.txt").writeText(log) }
            if (PluginManager.hasRunningPlugins()) {
                PluginManager.activateSafeModeFromCrash(this, log)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        try {
            PluginManager.initialize(this)
        } catch (t: Throwable) {
            android.util.Log.e("FlippyApplication", "Plugin init failed", t)
        }
    }
}

