package com.anurag.cctvprimary

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

/**
 * Application entry point for CCTV Primary.
 *
 * Intended purpose:
 * - Install a last-resort uncaught exception handler so unexpected background-thread crashes
 *   always appear in logcat with our tags (sometimes developers miss the AndroidRuntime trace).
 * - Keep the log message high-signal: thread name + exception type/message + stacktrace.
 *
 * Note:
 * - We still delegate to the system default handler to preserve normal crash reporting behavior.
 */
class CctvPrimaryApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // If the app "disappears" without a Java exception (common with MediaCodec / GPU / native crashes,
        // ANRs, or LMK), log the system-recorded exit reason from the previous run.
        logRecentProcessExitReasons()

        // Capture the existing handler so we can delegate after logging.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                @Suppress("DEPRECATION")
                Log.e(
                    "CCTV_APP",
                    "💥 Uncaught exception on thread='${thread.name}' (id=${thread.id}) — app will crash",
                    throwable
                )
            } catch (_: Throwable) {
                // Fallback: if logging itself fails, still ensure we delegate/crash.
            }

            // Delegate to the original handler (AndroidRuntime will still show the standard crash trace).
            try {
                previousHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            } catch (_: Throwable) {
                // If the previous handler fails unexpectedly, fall through to hard-exit.
            }

            // Absolute last resort: terminate process to avoid zombie state.
            try {
                exitProcess(10)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun logRecentProcessExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (exits.isEmpty()) return

            // Log newest first; this is what we’ll use to debug “soft” crashes / sudden process death.
            exits.forEachIndexed { idx, e ->
                val reason = when (e.reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                    android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
                    android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    else -> "UNKNOWN(${e.reason})"
                }

                // Description is often the most useful field (e.g. “Fatal signal 11 (SIGSEGV) …”).
                Log.w(
                    "CCTV_APP",
                    "🧾 Previous process exit[$idx]: reason=$reason status=${e.status} ts=${e.timestamp} pss=${e.pss}kB rss=${e.rss}kB importance=${e.importance} desc=${e.description}"
                )

                // If system kept a trace (ANR/native), dump it too (can be large; still worth it for this issue).
                try {
                    e.traceInputStream?.use { ins ->
                        val text = ins.bufferedReader().readText()
                        if (text.isNotBlank()) {
                            Log.w("CCTV_APP", "🧾 Previous process exit[$idx] trace:\n$text")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("CCTV_APP", "Failed to read exit trace (ignored)", t)
                }
            }
        } catch (t: Throwable) {
            Log.w("CCTV_APP", "Failed to query process exit reasons (ignored)", t)
        }
    }
}

