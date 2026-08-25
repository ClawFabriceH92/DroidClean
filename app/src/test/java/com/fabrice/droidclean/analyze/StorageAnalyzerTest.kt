package com.fabrice.droidclean.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageAnalyzerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, content: ByteArray): File =
        File(temp.root, path).apply {
            parentFile?.mkdirs()
            writeBytes(content)
        }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `plus gros fichiers - triés, seuil respecté, limite appliquée`() {
        write("a.bin", bytes(5_000, 1))
        write("dossier/b.bin", bytes(9_000, 2))
        write("c.bin", bytes(100, 3))

        val top = StorageAnalyzer.largestFiles(
            roots = listOf(temp.root),
            limit = 10,
            minBytes = 1_000,
        )

        assertEquals(listOf("b.bin", "a.bin"), top.map { it.file.name })
        assertEquals(9_000L, top.first().sizeBytes)
    }

    @Test
    fun `plus gros fichiers - la limite tronque au sommet`() {
        repeat(5) { write("f$it.bin", bytes(1_000 + it, 0)) }

        val top = StorageAnalyzer.largestFiles(listOf(temp.root), limit = 2, minBytes = 0)

        assertEquals(2, top.size)
        assertEquals(1_004L, top.first().sizeBytes)
    }

    @Test
    fun `doublons - même contenu regroupé, exemplaire unique déduit`() {
        val contenu = bytes(4_096, 11)
        write("photos/vue.jpg", contenu)
        write("copies/vue.jpg", contenu)
        write("copies/vue-2.jpg", contenu)
        write("autre.jpg", bytes(4_096, 12)) // même taille, contenu différent

        val groups = StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1_000)

        assertEquals(1, groups.size)
        assertEquals(3, groups.single().files.size)
        assertEquals(4_096L, groups.single().sizeBytes)
        assertEquals(2 * 4_096L, groups.single().wastedBytes)
    }

    @Test
    fun `doublons - taille identique mais contenu différent n'est pas un doublon`() {
        write("x.bin", bytes(2_048, 1))
        write("y.bin", bytes(2_048, 2))

        assertTrue(StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1_000).isEmpty())
    }

    @Test
    fun `doublons - gros fichiers ne différant que par leur fin`() {
        // Vérifie que l'empreinte partielle lit bien la FIN du fichier :
        // 300 Ko identiques au début, un seul octet différent à la fin.
        val a = ByteArray(300_000) { (it % 251).toByte() }
        val b = a.copyOf().also { it[it.size - 1] = 42 }
        write("gros-a.bin", a)
        write("gros-b.bin", b)

        assertTrue(StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1_000).isEmpty())
    }

    @Test
    fun `doublons - seuil de taille respecté`() {
        val petit = bytes(10, 5)
        write("p1.bin", petit)
        write("p2.bin", petit)

        assertTrue(StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1_000).isEmpty())
        assertEquals(1, StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1).size)
    }

    @Test
    fun `doublons - classés par octets récupérables`() {
        val gros = bytes(50_000, 1)
        val petit = bytes(2_000, 2)
        write("g1.bin", gros); write("g2.bin", gros)
        write("p1.bin", petit); write("p2.bin", petit)

        val groups = StorageAnalyzer.duplicates(listOf(temp.root), minBytes = 1_000)

        assertEquals(2, groups.size)
        assertEquals(50_000L, groups.first().sizeBytes)
    }

    @Test
    fun `filtre skip - les dossiers exclus ne sont pas parcourus`() {
        write("garde/gros.bin", bytes(9_000, 1))
        write("ignore/enorme.bin", bytes(90_000, 2))

        val top = StorageAnalyzer.largestFiles(
            roots = listOf(temp.root),
            minBytes = 1_000,
            skip = { it.name == "ignore" },
        )

        assertEquals(listOf("gros.bin"), top.map { it.file.name })
    }
}
