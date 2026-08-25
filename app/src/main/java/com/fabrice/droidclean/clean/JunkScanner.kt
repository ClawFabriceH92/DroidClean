package com.fabrice.droidclean.clean

import java.io.File

/**
 * Analyse du stockage : produit la liste des éléments nettoyables.
 *
 * Sans dépendance Android — la couche [Cleaner] fournit les chemins réels et le
 * scanner se contente de `File`. C'est ce qui rend le code le plus dangereux de
 * l'app (celui qui décide quoi supprimer) entièrement testable.
 *
 * Ce que le scanner **ne fait pas**, et pourquoi :
 * - `Android/data` : inaccessible depuis Android 11, même avec « Accès à tous les fichiers » ;
 * - les caches des autres applications : impossible sans root depuis Android 7 ;
 * - `Android/obb` : données de jeux, leur re-téléchargement coûte des gigaoctets.
 */
class JunkScanner(private val layout: Layout) {

    data class Layout(
        /** Dossier Téléchargements partagé, si accessible. */
        val downloads: File? = null,
        /** Caches de DroidClean (interne + externe). */
        val appCaches: List<File> = emptyList(),
        /** Racine du stockage partagé (`/sdcard`), si accessible. */
        val externalRoot: File? = null,
        /**
         * Paquets installés. **Laisser vide désactive la détection de résidus** :
         * sans liste fiable, tout dossier passerait pour un résidu.
         */
        val installedPackages: Set<String> = emptySet(),
        /** Chemins que le scanner ne doit jamais proposer (dont ceux de DroidClean). */
        val protectedPaths: Set<String> = emptySet(),
    )

    fun scan(): List<JunkItem> {
        val items = ArrayList<JunkItem>()
        scanDownloads(items)
        scanAppCaches(items)
        layout.externalRoot?.takeIf { it.isDirectory }?.let { root ->
            scanAndroidMedia(root, items)
            scanThumbnails(root, items)
            scanLostDir(root, items)
            scanEmptyDirs(root, items)
        }
        return items
            .filterNot { isProtected(it.file) }
            .sortedWith(compareByDescending<JunkItem> { it.sizeBytes }.thenBy { it.path })
    }

    // ------------------------------------------------------------------ zones

    private fun scanDownloads(out: MutableList<JunkItem>) {
        val dir = layout.downloads?.takeIf { it.isDirectory } ?: return
        dir.listFiles()?.forEach { child -> out.addItem(child, JunkCategory.DOWNLOADS) }
    }

    private fun scanAppCaches(out: MutableList<JunkItem>) {
        layout.appCaches.filter { it.isDirectory }.forEach { cache ->
            cache.listFiles()?.forEach { child -> out.addItem(child, JunkCategory.APP_CACHE) }
        }
    }

    /**
     * `Android/media/<paquet>` est le seul dossier d'app resté lisible après Android 11.
     * Deux cas : le paquet n'est plus installé (résidu complet), ou il l'est encore et
     * seuls ses sous-dossiers de cache sont proposés.
     */
    private fun scanAndroidMedia(root: File, out: MutableList<JunkItem>) {
        val media = File(File(root, ANDROID_DIR), MEDIA_DIR).takeIf { it.isDirectory } ?: return
        val canDetectLeftovers = layout.installedPackages.isNotEmpty()

        media.listFiles()?.forEach { packageDir ->
            if (!packageDir.isDirectory) return@forEach
            val name = packageDir.name
            if (!looksLikePackageName(name)) return@forEach

            if (canDetectLeftovers && name !in layout.installedPackages) {
                out.addItem(packageDir, JunkCategory.LEFTOVER)
                return@forEach
            }
            packageDir.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name.lowercase() in CACHE_DIR_NAMES) {
                    out.addItem(child, JunkCategory.APP_MEDIA_CACHE)
                }
            }
        }
    }

    private fun scanThumbnails(root: File, out: MutableList<JunkItem>) {
        THUMBNAILS_RELATIVE.forEach { relative ->
            val dir = File(root, relative)
            if (dir.isDirectory) out.addItem(dir, JunkCategory.THUMBNAILS)
        }
    }

    private fun scanLostDir(root: File, out: MutableList<JunkItem>) {
        val lost = File(root, LOST_DIR).takeIf { it.isDirectory } ?: return
        lost.listFiles()?.forEach { child -> out.addItem(child, JunkCategory.LOST_DIR) }
    }

    /**
     * Dossiers vides, hors dossiers standard d'Android (le système les recrée
     * aussitôt) et hors `Android/`. Profondeur bornée : un stockage plein de
     * milliers de dossiers ne doit pas transformer l'analyse en attente.
     */
    private fun scanEmptyDirs(root: File, out: MutableList<JunkItem>) {
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_DIRS_FOR_EMPTY_SCAN) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory || FileTree.isSymlink(child)) continue
                if (depth == 0 && child.name in NEVER_ENTERED_TOP_LEVEL) continue
                visited++
                // Les dossiers standard ne sont jamais proposés (le système les recrée
                // aussitôt), mais on descend quand même dedans.
                val proposable = !(depth == 0 && child.name in STANDARD_TOP_LEVEL)
                if (proposable && FileTree.isEmptyDir(child)) {
                    out.add(
                        JunkItem(
                            file = child,
                            category = JunkCategory.EMPTY_DIR,
                            sizeBytes = 0L,
                            lastModified = child.lastModified(),
                            isDirectory = true,
                        )
                    )
                } else if (depth + 1 < MAX_DEPTH_FOR_EMPTY_SCAN) {
                    stack.addLast(child to depth + 1)
                }
            }
        }
    }

    // ----------------------------------------------------------------- outils

    private fun MutableList<JunkItem>.addItem(file: File, category: JunkCategory) {
        if (FileTree.isSymlink(file)) return
        val size = FileTree.sizeOf(file)
        add(
            JunkItem(
                file = file,
                category = category,
                sizeBytes = size,
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory,
            )
        )
    }

    private fun isProtected(file: File): Boolean {
        val path = file.absolutePath
        return layout.protectedPaths.any { protected ->
            path == protected || path.startsWith("$protected/") || protected.startsWith("$path/")
        }
    }

    private companion object {
        const val ANDROID_DIR = "Android"
        const val MEDIA_DIR = "media"
        const val LOST_DIR = "LOST.DIR"
        const val MAX_DEPTH_FOR_EMPTY_SCAN = 4
        const val MAX_DIRS_FOR_EMPTY_SCAN = 20_000

        val CACHE_DIR_NAMES = setOf("cache", ".cache", "caches", "tmp", ".tmp", "temp", "cached")

        val THUMBNAILS_RELATIVE = listOf(
            "DCIM/.thumbnails",
            "Pictures/.thumbnails",
            "Movies/.thumbnails",
            ".thumbnails",
        )

        /**
         * Dossiers standard d'Android : jamais proposés à la suppression même vides,
         * le système les recrée immédiatement.
         */
        val STANDARD_TOP_LEVEL = setOf(
            "DCIM", "Download", "Downloads", "Pictures", "Movies", "Music",
            "Documents", "Alarms", "Notifications", "Podcasts", "Ringtones", "Audiobooks",
            "Recordings",
        )

        /** Dossiers dans lesquels la recherche de dossiers vides ne descend pas du tout. */
        val NEVER_ENTERED_TOP_LEVEL = setOf("Android", "LOST.DIR")

        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun looksLikePackageName(name: String): Boolean = PACKAGE_NAME.matches(name)
    }
}
