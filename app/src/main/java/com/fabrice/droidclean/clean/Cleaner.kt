package com.fabrice.droidclean.clean

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Nettoyage réel du stockage.
 *
 * Le point clé : depuis Android 11 (API 30), le *scoped storage* interdit de lire
 * ou supprimer les fichiers du dossier Téléchargements partagé avec la seule
 * permission WRITE_EXTERNAL_STORAGE. DroidClean étant distribué hors Play Store
 * (GitHub Releases), il utilise « Accès à tous les fichiers »
 * (MANAGE_EXTERNAL_STORAGE), accordé par l'utilisateur depuis les Réglages.
 *
 * - API 26-29 : WRITE_EXTERNAL_STORAGE demandée à l'exécution.
 * - API 30+   : Environment.isExternalStorageManager().
 *
 * Sans cet accès, seuls les caches de l'application sont nettoyables — et l'UI
 * le dit explicitement au lieu d'échouer en silence.
 */
object Cleaner {

    /** Résultat d'une analyse. */
    data class Scan(
        val downloadsBytes: Long,
        val cacheBytes: Long,
        val hasStorageAccess: Boolean,
    ) {
        val totalBytes: Long get() = downloadsBytes + cacheBytes
    }

    /** Résultat d'un nettoyage. */
    data class Result(val freedBytes: Long, val failedFiles: Int)

    /** L'app peut-elle lire/supprimer le dossier Téléchargements partagé ? */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Intent vers l'écran système « Accès à tous les fichiers » (API 30+).
     * Retourne null sous Android 11 : c'est alors une permission d'exécution
     * classique, demandée par l'activité.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** Analyse : taille des téléchargements + des caches. À appeler hors du thread principal. */
    fun scan(context: Context): Scan {
        val access = hasStorageAccess(context)
        val downloads = if (access) folderSize(downloadsDir()) else 0L
        return Scan(
            downloadsBytes = downloads,
            cacheBytes = cacheDirs(context).sumOf { folderSize(it) },
            hasStorageAccess = access,
        )
    }

    /**
     * Supprime le contenu du dossier Téléchargements (si accessible) et des caches.
     * À appeler hors du thread principal.
     */
    fun clean(context: Context): Result {
        var freed = 0L
        var failed = 0

        if (hasStorageAccess(context)) {
            val downloads = downloadsDir()
            downloads?.listFiles()?.forEach { file ->
                // La taille se mesure AVANT la suppression, sinon on compte toujours 0.
                val size = folderSize(file)
                if (deleteRecursive(file)) freed += size else failed++
            }
        }

        cacheDirs(context).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val size = folderSize(file)
                if (deleteRecursive(file)) freed += size else failed++
            }
        }

        return Result(freed, failed)
    }

    /** Dossier Téléchargements partagé, ou null s'il est indisponible. */
    fun downloadsDir(): File? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        return Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.takeIf { it.isDirectory }
    }

    /** Caches purgeables sans risque : cache interne + cache externe de l'app. */
    private fun cacheDirs(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir).filter { it.isDirectory }

    /** Taille totale d'un fichier ou d'une arborescence. */
    fun folderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (!file.isDirectory) return file.length()
        var size = 0L
        // Parcours itératif : pas de débordement de pile sur une arborescence profonde.
        val stack = ArrayDeque<File>()
        stack.addLast(file)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else size += child.length()
            }
        }
        return size
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }
}
