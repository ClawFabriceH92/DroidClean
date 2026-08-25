package com.fabrice.droidclean.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Téléchargement (DownloadManager) et installation (FileProvider) de l'APK.
 * Nécessite l'autorisation système « installer des applications inconnues ».
 */
object AutoUpdater {

    private const val PREFS = "droidclean_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val APK_NAME = "droidclean-update.apk"

    /** Avancement d'un téléchargement en cours. */
    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val failed: Boolean) {
        /** 0..100, ou -1 quand la taille totale est encore inconnue. */
        val percent: Int
            get() = if (totalBytes > 0) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                -1
            }
    }

    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)

    fun lastDownloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Ouvre l'écran système qui autorise l'installation par cette app. */
    fun openInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Téléphones exotiques : repli sur les réglages généraux.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Lance le téléchargement en arrière-plan (notification native DownloadManager). */
    fun download(context: Context, url: String, title: String, description: String): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            val target = apkFile(context)
            target.parentFile?.mkdirs()
            target.delete() // nettoyage d'un éventuel ancien fichier
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription(description)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationUri(Uri.fromFile(target))
                .setAllowedOverMetered(false) // pas de MAJ sur données mobiles sans le vouloir
                .setAllowedOverRoaming(false)
            val id = dm.enqueue(request)
            prefs(context).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Avancement du téléchargement en cours, ou null s'il n'y en a pas. */
    fun progress(context: Context): Progress? {
        val id = lastDownloadId(context).takeIf { it >= 0 } ?: return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        return try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status == DownloadManager.STATUS_SUCCESSFUL) return null
                Progress(
                    downloadedBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                        )
                    ),
                    totalBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ),
                    failed = status == DownloadManager.STATUS_FAILED,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Le téléchargement [id] s'est-il terminé avec succès ? */
    fun isDownloadComplete(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return false
        return try {
            dm.getUriForDownloadedFile(id) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intent d'installation de l'APK téléchargé, ou null si l'APK est absent,
     * refusé par [ApkVerifier], ou si l'app n'a pas l'autorisation d'installer.
     */
    fun installIntent(context: Context): Intent? {
        if (!canRequestInstalls(context)) return null
        val file = apkFile(context)
        if (ApkVerifier.verify(context, file) != ApkVerifier.Verdict.OK) return null
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Installe l'APK déjà téléchargé.
     *
     * Ne fonctionne QUE si l'app est au premier plan : depuis Android 10, un
     * démarrage d'activité en arrière-plan est bloqué. Le receiver retombe donc
     * sur une notification.
     */
    fun installDownloaded(context: Context): Boolean {
        val intent = installIntent(context) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Supprime l'APK devenu inutile — installé, ou plus récent que rien.
     *
     * Une app de nettoyage qui laisse traîner dix mégaoctets d'installeur périmé
     * dans son propre dossier serait mal placée pour donner des leçons.
     */
    fun discardObsoleteApk(context: Context): Boolean {
        val file = apkFile(context)
        if (!file.isFile) return false
        val verdict = ApkVerifier.verify(context, file)
        // NOT_NEWER : la mise à jour a été installée (ou l'APK est périmé).
        // MISSING / UNREADABLE / WRONG_* : un fichier inutilisable ne sert à rien.
        if (verdict == ApkVerifier.Verdict.OK) return false
        return file.delete()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
