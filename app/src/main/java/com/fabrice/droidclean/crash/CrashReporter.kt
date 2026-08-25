package com.fabrice.droidclean.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import com.fabrice.droidclean.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapport de plantage local.
 *
 * DroidClean étant distribué hors Play Store, un plantage est autrement
 * totalement invisible : aucune console développeur ne le remonte. Le rapport
 * reste **sur l'appareil** ; c'est l'utilisateur qui décide de le partager.
 * Aucune donnée n'est envoyée automatiquement, nulle part.
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 32_000

    /** Installe le gestionnaire, en chaînant celui déjà en place. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // On ne masque jamais le plantage : la plateforme doit reprendre la main.
            previous?.uncaughtException(thread, error)
        }
    }

    fun reportFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    /** Rapport en attente, ou null s'il n'y en a pas. */
    fun pendingReport(context: Context): String? {
        val file = reportFile(context)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /** Partage du rapport en texte brut : aucun fichier n'est exposé. */
    fun shareIntent(subject: String, report: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, report)
        }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("DroidClean ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Date : $timestamp")
            appendLine("Thread : ${thread.name}")
            appendLine()
            append(stack)
        }
        reportFile(context).writeText(report.take(MAX_CHARS))
    }
}
