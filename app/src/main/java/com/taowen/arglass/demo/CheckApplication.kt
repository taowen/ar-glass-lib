package com.taowen.arglass.demo

import android.app.Application
import com.taowen.arglass.ArGlassesDiagnostics

class CheckApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { ArGlassesDiagnostics.recordCrash(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
