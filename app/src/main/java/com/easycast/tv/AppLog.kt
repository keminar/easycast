package com.easycast.tv

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory log shown on the main screen, so problems can be seen on the TV without adb.
 * Every line is also forwarded to logcat under the tag "EM".
 */
object AppLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<Pair<Char, String>>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Called (on any thread) whenever a line is added; the UI coalesces and re-renders. */
    @Volatile var listener: (() -> Unit)? = null

    fun i(msg: String) = add('I', msg)
    fun w(msg: String) = add('W', msg)
    fun e(msg: String) = add('E', msg)

    @Synchronized
    private fun add(level: Char, msg: String) {
        when (level) {
            'E' -> Log.e("EM", msg)
            'W' -> Log.w("EM", msg)
            else -> Log.i("EM", msg)
        }
        lines.addLast(level to "${time.format(Date())} $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
        listener?.invoke()
    }

    /** The newest [n] lines, oldest first. */
    @Synchronized
    fun tail(n: Int): List<Pair<Char, String>> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() {
        lines.clear()
        listener?.invoke()
    }
}
