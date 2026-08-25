package com.fabrice.droidclean.clean

import java.io.File
import java.io.IOException

/**
 * Primitives de parcours de fichiers, partagées par l'analyse et la suppression.
 *
 * Deux règles tiennent tout le reste :
 * - le parcours est **itératif** (pas de débordement de pile sur une arborescence profonde) ;
 * - les **liens symboliques ne sont jamais suivis** : un lien vers `/` transformerait
 *   une analyse en parcours du système entier, et une suppression en catastrophe.
 */
object FileTree {

    /** Le chemin est-il un lien symbolique ? En cas de doute, on répond « oui » (prudence). */
    fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (_: IOException) {
        true
    } catch (_: SecurityException) {
        true
    } catch (_: RuntimeException) {
        // InvalidPathException sur un nom de fichier exotique.
        true
    }

    /**
     * Taille totale d'un fichier ou d'une arborescence.
     * Les liens symboliques comptent pour 0 : leur cible est ailleurs.
     */
    fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (isSymlink(file)) return 0L
        if (!file.isDirectory) return file.length()

        var size = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(file)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) stack.addLast(child) else size += child.length()
            }
        }
        return size
    }

    /**
     * Parcours en profondeur borné, sans suivre les liens symboliques.
     * [onFile] reçoit chaque fichier ordinaire ; [enterDir] décide de descendre ou non.
     * S'arrête après [maxFiles] fichiers visités pour ne jamais bloquer indéfiniment.
     */
    fun walk(
        roots: List<File>,
        maxDepth: Int = Int.MAX_VALUE,
        maxFiles: Int = 300_000,
        enterDir: (File, Int) -> Boolean = { _, _ -> true },
        onFile: (File) -> Unit,
    ) {
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.filter { it.isDirectory && !isSymlink(it) }.forEach { stack.addLast(it to 0) }

        while (stack.isNotEmpty()) {
            if (visited >= maxFiles) return
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (visited >= maxFiles) return
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    if (depth + 1 <= maxDepth && enterDir(child, depth + 1)) {
                        stack.addLast(child to depth + 1)
                    }
                } else {
                    visited++
                    onFile(child)
                }
            }
        }
    }

    /** Le dossier est-il vide (aucun enfant) ? `false` s'il est illisible. */
    fun isEmptyDir(dir: File): Boolean =
        dir.isDirectory && !isSymlink(dir) && (dir.listFiles()?.isEmpty() == true)
}
