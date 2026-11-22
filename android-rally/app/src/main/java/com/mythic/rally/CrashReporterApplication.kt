package com.mythic.rally

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RallyTime.initialize(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                openFileOutput("last_crash.txt", MODE_PRIVATE).bufferedWriter().use {
                    it.write("MYTHIC Rally Overlay crash\nTime: $timestamp\nThread: ${thread.name}\n\n$stack")
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
