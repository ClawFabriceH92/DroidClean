package com.fabrice.droidclean.clean

import java.io.File

/**
 * Suppression effective, avec une comptabilité exacte.
 *
 * L'implémentation précédente mesurait la taille de l'arborescence puis testait
 * `File.delete()` sur sa racine : un seul fichier verrouillé faisait échouer la
 * suppression du dossier parent, donc l'octet libéré n'était **jamais** compté
 * alors que les enfants, eux, avaient bien disparu. Ici, chaque fichier est
 * compté individuellement au moment où il est réellement supprimé.
 */
object FileDeleter {

    data class Outcome(
        val freedBytes: Long,
        val deletedFiles: Int,
        val deletedDirs: Int,
        val failed: Int,
        val deletedPaths: List<String>,
    ) {
        val hasFailures: Boolean get() = failed > 0

        operator fun plus(other: Outcome): Outcome = Outcome(
            freedBytes = freedBytes + other.freedBytes,
            deletedFiles = deletedFiles + other.deletedFiles,
            deletedDirs = deletedDirs + other.deletedDirs,
            failed = failed + other.failed,
            deletedPaths = deletedPaths + other.deletedPaths,
        )

        companion object {
            val EMPTY = Outcome(0L, 0, 0, 0, emptyList())
        }
    }

    private class Accumulator {
        var freed = 0L
        var files = 0
        var dirs = 0
        var failed = 0
        val paths = ArrayList<String>()

        fun toOutcome() = Outcome(freed, files, dirs, failed, paths)
    }

    /**
     * Supprime les éléments donnés. [protectedPaths] est un garde-fou de dernier
     * recours : même si un élément protégé arrive jusqu'ici, il n'est pas touché.
     */
    fun delete(items: Iterable<JunkItem>, protectedPaths: Set<String> = emptySet()): Outcome {
        val acc = Accumulator()
        for (item in items) {
            if (!isDeletable(item.file, protectedPaths)) {
                acc.failed++
                continue
            }
            deleteTree(item.file, acc)
        }
        return acc.toOutcome()
    }

    /**
     * Refuse tout ce qui n'a pas de parent (racine du système de fichiers), les
     * liens symboliques et les chemins protégés.
     */
    private fun isDeletable(file: File, protectedPaths: Set<String>): Boolean {
        if (file.parentFile == null) return false
        if (FileTree.isSymlink(file)) return false
        val path = file.absolutePath
        return protectedPaths.none { path == it || path.startsWith("$it/") }
    }

    private fun deleteTree(root: File, acc: Accumulator) {
        if (!root.exists()) return

        if (!root.isDirectory) {
            deleteLeaf(root, acc)
            return
        }

        // Parcours itératif, suppression des enfants avant le parent : un dossier
        // ne se supprime que vide.
        val postOrder = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            postOrder.add(dir)
            val children = dir.listFiles() ?: continue
            for (child in children) {
                when {
                    FileTree.isSymlink(child) -> deleteLeaf(child, acc, countBytes = false)
                    child.isDirectory -> stack.addLast(child)
                    else -> deleteLeaf(child, acc)
                }
            }
        }
        // Les dossiers les plus profonds ont été empilés en dernier : on remonte.
        for (dir in postOrder.asReversed()) {
            if (dir.delete()) {
                acc.dirs++
                acc.paths.add(dir.absolutePath)
            } else {
                acc.failed++
            }
        }
    }

    private fun deleteLeaf(file: File, acc: Accumulator, countBytes: Boolean = true) {
        // La taille se lit AVANT la suppression, sinon on compte toujours zéro.
        val size = if (countBytes) file.length() else 0L
        if (file.delete()) {
            acc.freed += size
            acc.files++
            acc.paths.add(file.absolutePath)
        } else {
            acc.failed++
        }
    }
}
