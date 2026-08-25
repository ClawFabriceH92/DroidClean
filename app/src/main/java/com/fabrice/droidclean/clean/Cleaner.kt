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
import com.fabrice.droidclean.apps.Packages
import java.io.File

/**
 * Façade Android du nettoyage : fournit les chemins réels à [JunkScanner], puis
 * applique la suppression via [FileDeleter] et [Trash].
 *
 * Toute la logique de décision (quoi proposer, quoi compter) vit dans les classes
 * pures voisines, qui sont testées ; cet objet ne fait que traduire le monde
 * Android en `File`.
 *
 * Rappel des limites de la plateforme :
 * - `Android/data` est inaccessible depuis Android 11, même en « Accès à tous les fichiers » ;
 * - les caches des autres applications sont hors de portée sans root depuis Android 7 ;
 * - sans l'accès au stockage partagé, seuls les caches de DroidClean sont nettoyables.
 */
object Cleaner {

    /** Résultat d'un nettoyage, corbeille et suppression comptées séparément. */
    data class CleanOutcome(
        val freedBytes: Long,
        val trashedBytes: Long,
        val deletedFiles: Int,
        val trashedFiles: Int,
        val failed: Int,
    ) {
        val totalHandledBytes: Long get() = freedBytes + trashedBytes

        companion object {
            val EMPTY = CleanOutcome(0L, 0L, 0, 0, 0)
        }
    }

    // ------------------------------------------------------------ permissions

    /** L'app peut-elle lire et supprimer dans le stockage partagé ? */
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
     * Retourne null en dessous : c'est alors une permission d'exécution classique.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    // ----------------------------------------------------------------- chemins

    /** Dossier Téléchargements partagé, ou null s'il est indisponible. */
    fun downloadsDir(): File? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        return Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.takeIf { it.isDirectory }
    }

    private fun externalRoot(): File? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        return Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }
    }

    /** Caches purgeables sans risque : cache interne + cache externe de l'app. */
    private fun cacheDirs(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir).filter { it.isDirectory }

    /**
     * Chemins que DroidClean ne doit jamais proposer ni supprimer : ses propres
     * données, dont l'APK de mise à jour en cours de téléchargement.
     */
    fun protectedPaths(context: Context): Set<String> = buildSet {
        listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(null),
            context.noBackupFilesDir,
            externalRoot()?.let { File(File(it, "Android/media"), context.packageName) },
        ).forEach { add(it.absolutePath) }
    }

    fun layout(context: Context): JunkScanner.Layout {
        val access = hasStorageAccess(context)
        return JunkScanner.Layout(
            downloads = if (access) downloadsDir() else null,
            appCaches = cacheDirs(context),
            externalRoot = if (access) externalRoot() else null,
            installedPackages = Packages.installedNames(context),
            protectedPaths = protectedPaths(context),
        )
    }

    // ------------------------------------------------------------------ actions

    /** Analyse complète. À appeler hors du thread principal. */
    fun scan(context: Context): JunkScan = JunkScan(
        items = JunkScanner(layout(context)).scan(),
        hasStorageAccess = hasStorageAccess(context),
        scannedAt = System.currentTimeMillis(),
    )

    /**
     * Supprime les éléments choisis.
     *
     * Les documents de l'utilisateur ([JunkCategory.isSafe] à `false`) passent par
     * la corbeille du système quand [useTrash] le permet ; les caches, eux, sont
     * toujours supprimés définitivement — ils se régénèrent.
     *
     * À appeler hors du thread principal.
     */
    fun clean(
        context: Context,
        items: List<JunkItem>,
        useTrash: Boolean = true,
    ): CleanOutcome {
        if (items.isEmpty()) return CleanOutcome.EMPTY
        val guarded = protectedPaths(context)

        val (userContent, regenerable) = items.partition { !it.category.isSafe }
        var trashedBytes = 0L
        var trashedFiles = 0
        val alreadyHandled = HashSet<String>()

        if (useTrash && Trash.isSupported() && userContent.isNotEmpty()) {
            val candidates = userContent.flatMap { regularFilesUnder(it.file) }
            val sizes = candidates.associate { it.absolutePath to it.length() }
            val trashed = Trash.trash(context, candidates)
            trashed.forEach { path ->
                trashedBytes += sizes[path] ?: 0L
                trashedFiles++
            }
            alreadyHandled += trashed
        }

        // Ce qui n'a pas pu aller à la corbeille est supprimé pour de bon ;
        // les dossiers vidés par la corbeille disparaissent au passage.
        val outcome = FileDeleter.delete(regenerable + userContent, guarded)
        Trash.notifyDeleted(context, outcome.deletedPaths + alreadyHandled)

        return CleanOutcome(
            freedBytes = outcome.freedBytes,
            trashedBytes = trashedBytes,
            deletedFiles = outcome.deletedFiles,
            trashedFiles = trashedFiles,
            failed = outcome.failed,
        )
    }

    /**
     * Nettoyage automatique : **uniquement** ce qui se régénère. Aucun document
     * de l'utilisateur n'est touché sans qu'il l'ait explicitement demandé.
     */
    fun cleanSafeOnly(context: Context): CleanOutcome {
        val scan = scan(context)
        val safe = scan.items.filter { it.category.isSafe }
        return clean(context, safe, useTrash = false)
    }

    /** Fichiers ordinaires contenus dans [root] (lui-même s'il n'est pas un dossier). */
    private fun regularFilesUnder(root: File): List<File> {
        if (!root.exists() || FileTree.isSymlink(root)) return emptyList()
        if (!root.isDirectory) return listOf(root)
        val out = ArrayList<File>()
        FileTree.walk(listOf(root)) { out.add(it) }
        return out
    }
}
