package io.github.tiltbob.grape.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostic log shared by every part of the app. Pure Kotlin so the protocol
 * code can write to it; the Application installs a [sink] that mirrors lines to logcat.
 */
object DebugLog {
    private const val MAX_LINES = 4000
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _version = MutableStateFlow(0)
    /** Bumps on every line, so the UI knows when to refresh. */
    val version: StateFlow<Int> = _version

    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null

    @Synchronized
    fun log(tag: String, message: String) {
        lines.addLast("${timeFormat.format(Date())} $tag: $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
        _version.value = _version.value + 1
        sink?.invoke(tag, message)
    }

    fun log(tag: String, message: String, t: Throwable) =
        log(tag, "$message: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    fun tail(count: Int): String = lines.toList().takeLast(count).joinToString("\n")

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun clear() {
        lines.clear()
        _version.value = _version.value + 1
    }

    /** Short hex preview of a datagram for the log. */
    fun hex(data: ByteArray, length: Int, max: Int = 16): String {
        val n = minOf(length, max)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(String.format(Locale.US, "%02x", data[i].toInt() and 0xFF)).append(' ')
        if (length > max) sb.append("… (").append(length).append(" bytes)")
        return sb.toString().trim()
    }
}
