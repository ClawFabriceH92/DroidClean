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
 * Téléchargement (DownloadManager) + installation (FileProvider) de l'APK.
 * Nécessite la permission système « installer des apps inconnues » (REQUEST_INSTALL_PACKAGES).
 */
object AutoUpdater {

    private const val PREFS = "droidclean_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val APK_NAME = "droidclean-update.apk"

    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)

    fun lastDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Ouvre l'écran système qui autorise l'installation par cette app. */
    fun openInstallSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Téléphones exotiques : fallback sur les réglages généraux
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** Lance le téléchargement en arrière-plan (notification native DownloadManager). */
    fun download(context: Context, url: String): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return try {
            val target = apkFile(context)
            target.parentFile?.mkdirs()
            target.delete() // nettoyage d'un éventuel ancien fichier
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("DroidClean")
                .setDescription("Téléchargement de la mise à jour…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target))
                .setAllowedOverMetered(false) // pas de MAJ sur données mobiles sans le vouloir
                .setAllowedOverRoaming(false)
            val id = dm.enqueue(request)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DOWNLOAD_ID, id).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Vérifie si le téléchargement [id] est terminé avec succès. */
    fun isDownloadComplete(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return try {
            dm.getUriForDownloadedFile(id) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Intent d'installation de l'APK téléchargé, ou null si l'APK est absent ou
     * si l'app n'a pas l'autorisation d'installer des sources inconnues.
     */
    fun installIntent(context: Context): Intent? {
        if (!canRequestInstalls(context)) return null
        val file = apkFile(context)
        if (!file.exists() || file.length() == 0L) return null
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
     * sur une notification ([UpdateNotifier.notifyReadyToInstall]).
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
}
