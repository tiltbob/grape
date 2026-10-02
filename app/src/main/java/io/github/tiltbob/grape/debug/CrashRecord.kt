package io.github.tiltbob.grape.debug

import android.content.Context
import io.github.tiltbob.grape.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last uncaught crash in the app's private files, so the
 * debug report after a restart can show it. Nothing is sent anywhere; Android's own
 * crash handling still runs afterwards.
 */
object CrashRecord {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        read(app)?.let { previous ->
            DebugLog.log("App", "the previous run crashed: ${previous.lineSequence().take(3).joinToString(" | ")}")
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        runCatching { File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) thread ${thread.name}")
            appendLine(trace)
            appendLine("log tail at the time:")
            append(DebugLog.tail(40))
        }
        File(context.filesDir, FILE).writeText(text)
    }
}
