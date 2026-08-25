package com.fabrice.droidclean.clean

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Corbeille du système (Android 11+) plutôt que suppression sèche.
 *
 * Un document de l'utilisateur mis à la corbeille reste récupérable ~30 jours
 * depuis l'application Fichiers. Avec « Accès à tous les fichiers », le passage
 * par `IS_TRASHED` ne demande aucune confirmation supplémentaire.
 *
 * Attention : **un fichier à la corbeille occupe toujours l'espace disque.** Il
 * ne doit donc jamais être compté comme « libéré » — d'où la distinction entre
 * `freedBytes` et `trashedBytes` dans [CleanOutcome].
 */
internal object Trash {

    /** Nombre maximum de chemins par requête : au-delà, SQLite refuse le `IN (...)`. */
    private const val BATCH = 200

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Met les fichiers donnés à la corbeille et retourne ceux qui y sont
     * réellement arrivés. Les autres restent à supprimer normalement.
     */
    fun trash(context: Context, files: List<File>): Set<String> {
        if (files.isEmpty()) return emptySet()
        // Contrôle de version écrit ici plutôt que délégué à isSupported() : lint
        // ne suit pas un garde d'API à travers un appel de fonction, et refuserait
        // la compilation en signalant une API trop récente.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val done = HashSet<String>()
        files.chunked(BATCH).forEach { batch -> done += trashBatch(context, batch) }
        return done
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun trashBatch(context: Context, files: List<File>): Set<String> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val byPath = files.associateBy { it.absolutePath }
        val placeholders = files.joinToString(",") { "?" }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }

        return try {
            val updated = resolver.update(
                collection,
                values,
                "${MediaStore.MediaColumns.DATA} IN ($placeholders)",
                byPath.keys.toTypedArray(),
            )
            if (updated <= 0) {
                emptySet()
            } else {
                // `update` ne dit pas QUELS fichiers ont bougé : on vérifie sur le disque.
                // Un fichier à la corbeille est renommé par le système en `.trashed-…`.
                byPath.filterValues { !it.exists() }.keys
            }
        } catch (_: Exception) {
            // Volume non indexé, colonne refusée, fabricant exotique : on retombe
            // simplement sur la suppression classique.
            emptySet()
        }
    }

    /**
     * Prévient l'index multimédia des fichiers disparus : sans cela, l'écran
     * Téléchargements du système continue d'afficher des entrées fantômes.
     */
    fun notifyDeleted(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                paths.toTypedArray(),
                null,
                null,
            )
        } catch (_: Exception) {
            // Purement cosmétique : jamais une raison de faire échouer un nettoyage.
        }
    }
}
