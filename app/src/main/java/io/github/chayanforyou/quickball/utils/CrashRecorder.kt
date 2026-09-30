package io.github.chayanforyou.quickball.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the last crash (stack trace) locally so it can be shown on the
 * "Background & Huawei" screen and sent to the developer. Nothing is uploaded.
 */
object CrashRecorder {
    private const val PREFS = "quick_ball_crash"
    private const val KEY = "last_crash"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(app, "uncaught in ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(context: Context, where: String, e: Throwable) {
        Log.e("CrashRecorder", where, e)
        runCatching {
            val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
            val text = buildString {
                append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
                append(" | ").append(where)
                append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                append(" | SDK ").append(Build.VERSION.SDK_INT).append('\n')
                append(sw.toString().take(6000))
            }
            // commit() (synchronous) so it survives the process dying right after.
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, text).commit()
        }
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
