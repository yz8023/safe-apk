package com.adfxcbnm.frostshell.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FrostLogUtils {
    private const val TAG = "ironshell-processor"
    private val openLog = java.util.concurrent.atomic.AtomicBoolean(true)
    private val openNoisyLog = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var logListener: ((String) -> Unit)? = null
    const val ANSI_RESET = "\u001b[0m"
    const val ANSI_RED = "\u001b[31m"
    const val ANSI_GREEN = "\u001b[32m"
    const val ANSI_YELLOW = "\u001b[33m"

    fun setOpenLog(open: Boolean) {
        openLog.set(open)
    }

    fun setOpenNoisyLog(open: Boolean) {
        openNoisyLog.set(open)
    }

    fun info(fmt: String, vararg args: Any?) {
        println(LogType.INFO, TAG, String.format(Locale.US, fmt, *args))
    }

    fun debug(fmt: String, vararg args: Any?) {
        println(LogType.DEBUG, TAG, String.format(Locale.US, fmt, *args))
    }

    fun warn(fmt: String, vararg args: Any?) {
        println(LogType.WARN, TAG, String.format(Locale.US, fmt, *args))
    }

    fun error(fmt: String, vararg args: Any?) {
        println(LogType.ERROR, TAG, String.format(Locale.US, fmt, *args))
    }

    fun noisy(fmt: String, vararg args: Any?) {
        if (openNoisyLog.get()) {
            println(LogType.INFO, TAG, String.format(Locale.US, fmt, *args))
        }
    }

    private fun println(type: LogType, tag: String, msg: String) {
        if (!openLog.get()) return
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val threadName = "[" + Thread.currentThread().name + "]"
        val timeOut = sdf.format(Date())
        val capitalized = FrostStringUtils.capitalizeFirstLetter(msg) ?: msg
        val line = "$timeOut\t$threadName\t$tag\t$capitalized"
        when (type) {
            LogType.ERROR -> Log.e(tag, line)
            LogType.WARN -> Log.w(tag, line)
            else -> Log.i(tag, line)
        }
        logListener?.invoke(line)
    }

    private enum class LogType {
        DEBUG, INFO, WARN, ERROR
    }
}
