package com.fabrice.droidclean.clean

import java.io.File

/**
 * Catégories de fichiers proposés au nettoyage.
 *
 * Chaque catégorie a un niveau de risque différent : les caches se régénèrent,
 * les téléchargements sont des documents de l'utilisateur. L'interface les
 * traite donc différemment (cochés par défaut ou non).
 */
enum class JunkCategory {
    /** Dossier Téléchargements partagé : documents de l'utilisateur. Jamais coché d'office. */
    DOWNLOADS,

    /** Caches de DroidClean lui-même. Sans risque. */
    APP_CACHE,

    /** Sous-dossiers `cache`/`tmp` d'autres apps dans `Android/media`. Sans risque. */
    APP_MEDIA_CACHE,

    /** Dossiers `Android/media/<paquet>` d'applications désinstallées. */
    LEFTOVER,

    /** Vignettes régénérables (`.thumbnails`). */
    THUMBNAILS,

    /** `LOST.DIR` : fragments récupérés par le système de fichiers. */
    LOST_DIR,

    /** Dossiers vides. Ne libèrent rien, mais font le ménage. */
    EMPTY_DIR,
    ;

    /**
     * Une catégorie est « sûre » si son contenu se régénère ou n'a aucune valeur :
     * elle peut être cochée d'office et nettoyée automatiquement.
     */
    val isSafe: Boolean
        get() = this != DOWNLOADS && this != LEFTOVER
}

/** Un élément nettoyable : un fichier ou une arborescence, avec sa taille déjà mesurée. */
data class JunkItem(
    val file: File,
    val category: JunkCategory,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
}

/** Résultat complet d'une analyse. */
data class JunkScan(
    val items: List<JunkItem>,
    val hasStorageAccess: Boolean,
    val scannedAt: Long,
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }

    /** Total « sans risque » : ce qu'un nettoyage automatique peut prendre. */
    val safeBytes: Long get() = items.filter { it.category.isSafe }.sumOf { it.sizeBytes }

    fun bytesOf(category: JunkCategory): Long =
        items.filter { it.category == category }.sumOf { it.sizeBytes }

    fun countOf(category: JunkCategory): Int = items.count { it.category == category }

    /** Catégories réellement présentes, triées par volume décroissant. */
    fun categoriesByWeight(): List<JunkCategory> =
        items.groupBy { it.category }
            .map { (category, items) -> category to items.sumOf { it.sizeBytes } }
            .sortedWith(compareByDescending<Pair<JunkCategory, Long>> { it.second }.thenBy { it.first.name })
            .map { it.first }

    companion object {
        fun empty(hasStorageAccess: Boolean, at: Long = 0L): JunkScan =
            JunkScan(emptyList(), hasStorageAccess, at)
    }
}

/** Filtre appliqué à la liste avant affichage. */
data class JunkFilter(
    val categories: Set<JunkCategory> = JunkCategory.entries.toSet(),
    val minBytes: Long = 0L,
    val olderThanDays: Int = 0,
) {
    fun matches(item: JunkItem, now: Long): Boolean {
        if (item.category !in categories) return false
        if (item.sizeBytes < minBytes) return false
        if (olderThanDays > 0) {
            val ageDays = (now - item.lastModified) / 86_400_000L
            if (item.lastModified <= 0L || ageDays < olderThanDays) return false
        }
        return true
    }

    fun apply(items: List<JunkItem>, now: Long): List<JunkItem> = items.filter { matches(it, now) }
}
