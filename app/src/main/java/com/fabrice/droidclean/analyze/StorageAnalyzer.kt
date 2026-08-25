package com.fabrice.droidclean.analyze

import com.fabrice.droidclean.clean.FileTree
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Analyse « où sont passés mes gigaoctets » : plus gros fichiers et doublons.
 *
 * Sans dépendance Android, donc testable. La détection de doublons procède en
 * trois passes de coût croissant, pour ne hacher intégralement que ce qui a une
 * vraie chance d'être un doublon :
 *
 * 1. regroupement par **taille exacte** (gratuit, élimine 99 % des candidats) ;
 * 2. empreinte **partielle** (début + fin du fichier) ;
 * 3. empreinte **complète** SHA-256, uniquement sur les groupes survivants.
 */
object StorageAnalyzer {

    data class FileEntry(val file: File, val sizeBytes: Long, val lastModified: Long)

    data class DuplicateGroup(val sizeBytes: Long, val files: List<File>) {
        /** Octets récupérables : tout sauf un exemplaire. */
        val wastedBytes: Long get() = sizeBytes * (files.size - 1)
    }

    private const val PARTIAL_CHUNK = 64 * 1024
    private const val BUFFER = 64 * 1024

    /** Les [limit] plus gros fichiers, du plus lourd au plus léger. */
    fun largestFiles(
        roots: List<File>,
        limit: Int = 50,
        minBytes: Long = 5L * 1024 * 1024,
        maxFiles: Int = 300_000,
        skip: (File) -> Boolean = { false },
    ): List<FileEntry> {
        val found = ArrayList<FileEntry>()
        FileTree.walk(
            roots = roots,
            maxFiles = maxFiles,
            enterDir = { dir, _ -> !skip(dir) },
        ) { file ->
            val size = file.length()
            if (size >= minBytes && !skip(file)) {
                found.add(FileEntry(file, size, file.lastModified()))
            }
        }
        return found
            .sortedWith(compareByDescending<FileEntry> { it.sizeBytes }.thenBy { it.file.absolutePath })
            .take(limit)
    }

    /** Groupes de fichiers strictement identiques, du plus coûteux au moins coûteux. */
    fun duplicates(
        roots: List<File>,
        minBytes: Long = 1024L * 1024,
        maxGroups: Int = 200,
        maxFiles: Int = 300_000,
        skip: (File) -> Boolean = { false },
    ): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        FileTree.walk(
            roots = roots,
            maxFiles = maxFiles,
            enterDir = { dir, _ -> !skip(dir) },
        ) { file ->
            val size = file.length()
            if (size >= minBytes && !skip(file)) {
                bySize.getOrPut(size) { ArrayList() }.add(file)
            }
        }

        val groups = ArrayList<DuplicateGroup>()
        for ((size, candidates) in bySize) {
            if (candidates.size < 2) continue
            for (partialGroup in groupBy(candidates) { partialDigest(it, size) }) {
                if (partialGroup.size < 2) continue
                for (exactGroup in groupBy(partialGroup) { fullDigest(it) }) {
                    if (exactGroup.size < 2) continue
                    groups.add(
                        DuplicateGroup(size, exactGroup.sortedBy { it.absolutePath })
                    )
                }
            }
        }
        return groups
            .sortedWith(
                compareByDescending<DuplicateGroup> { it.wastedBytes }
                    .thenBy { it.files.firstOrNull()?.absolutePath.orEmpty() }
            )
            .take(maxGroups)
    }

    /** Regroupe par clé, en écartant silencieusement les fichiers illisibles (clé nulle). */
    private fun groupBy(files: List<File>, key: (File) -> String?): List<List<File>> =
        files.mapNotNull { file -> key(file)?.let { it to file } }
            .groupBy({ it.first }, { it.second })
            .values
            .toList()

    /**
     * Empreinte partielle : début et fin du fichier. Deux fichiers de même taille
     * dont les extrémités diffèrent ne peuvent pas être identiques.
     */
    private fun partialDigest(file: File, size: Long): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val head = ByteArray(minOf(PARTIAL_CHUNK.toLong(), size).toInt())
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            digest.update(head, 0, read)
        }
        if (size > PARTIAL_CHUNK * 2L) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - PARTIAL_CHUNK)
                val tail = ByteArray(PARTIAL_CHUNK)
                raf.readFully(tail)
                digest.update(tail)
            }
        }
        hex(digest.digest())
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun fullDigest(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
