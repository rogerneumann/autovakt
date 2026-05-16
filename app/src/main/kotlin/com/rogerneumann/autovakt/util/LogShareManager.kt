package com.rogerneumann.autovakt.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures logcat output and any saved crash report into a shareable text file.
 *
 * Two entry points:
 *  - [shareLogs] — called from Settings button or shake gesture; opens Android share sheet.
 *  - [saveCrash] — called by uncaught-exception handler; persists the stack trace to
 *    [crashFile] so it survives the process death and can be included in the next share.
 *
 * Firebase migration note: this class stays local regardless of CrashReporter backend.
 * Crashlytics has its own persistence; [LogShareManager] is the developer-side escape hatch.
 */
@Singleton
class LogShareManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val crashFile = File(context.filesDir, "autovakt_last_crash.txt")

    fun saveCrash(throwable: Throwable) {
        runCatching {
            crashFile.writeText(buildString {
                appendLine("=== AutoVakt Crash Report ===")
                appendLine("Time   : ${Date()}")
                appendLine()
                appendLine(throwable.stackTraceToString())
            })
        }
    }

    fun hasPendingCrash(): Boolean = crashFile.exists()

    fun clearPendingCrash() { crashFile.delete() }

    /**
     * Captures logcat + any saved crash file, writes to a temp share file,
     * then fires an ACTION_SEND intent via the system share sheet.
     * Must be called from a Fragment/Activity with a lifecycle scope.
     */
    fun shareLogs(activity: FragmentActivity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val logcat = captureLogcat()
                val crashReport = if (crashFile.exists()) crashFile.readText() else null

                val content = buildString {
                    appendLine("=== AutoVakt Diagnostic Report ===")
                    appendLine("Time   : ${Date()}")
                    appendLine()
                    if (crashReport != null) {
                        append(crashReport)
                        appendLine()
                        appendLine("=== Recent Logcat (last 500 lines) ===")
                    } else {
                        appendLine("=== Logcat (last 500 lines) ===")
                    }
                    append(logcat)
                }

                val shareFile = File(context.cacheDir, "vakt_diagnostics.txt")
                shareFile.writeText(content)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "AutoVakt Diagnostics — ${Date()}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "Share Diagnostic Logs"))
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Failed to capture logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun captureLogcat(): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "-v", "threadtime"))
        process.inputStream.bufferedReader().readText()
    }.getOrDefault("(logcat unavailable)")
}
