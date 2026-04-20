package io.ahmed.sysmon.util

import android.content.Context
import android.util.Log
import io.ahmed.sysmon.data.AppDatabase
import io.ahmed.sysmon.data.entity.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fire-and-forget on-device log. Writes to Room AND logcat so you can trace
 * behaviour from inside the app (Logs screen) or via `adb logcat sysmon:D`.
 */
object Logger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private const val TAG_PREFIX = "sysmon"

    fun i(context: Context, tag: String, message: String) = emit(context, "INFO", tag, message)
    fun w(context: Context, tag: String, message: String) = emit(context, "WARN", tag, message)
    fun e(context: Context, tag: String, message: String, err: Throwable? = null) =
        emit(context, "ERROR", tag, err?.let { "$message — ${it.javaClass.simpleName}: ${it.message}" } ?: message)

    private fun emit(context: Context, level: String, tag: String, message: String) {
        // logcat
        when (level) {
            "ERROR" -> Log.e("$TAG_PREFIX.$tag", message)
            "WARN" -> Log.w("$TAG_PREFIX.$tag", message)
            else -> Log.i("$TAG_PREFIX.$tag", message)
        }
        // room (non-blocking)
        val entry = LogEntry(
            ts = LocalDateTime.now().format(FMT),
            level = level, tag = tag, message = message
        )
        val dao = AppDatabase.get(context.applicationContext).logDao()
        scope.launch {
            runCatching { dao.insert(entry) }
        }
    }

    /** Delete entries older than 7 days. Call from a daily prune task. */
    suspend fun prune(context: Context, keepDays: Long = 7L) {
        val cutoff = LocalDateTime.now().minusDays(keepDays).format(FMT)
        AppDatabase.get(context.applicationContext).logDao().deleteBefore(cutoff)
    }
}
