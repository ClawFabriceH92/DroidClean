package com.fabrice.droidclean.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDeleterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun item(file: File, category: JunkCategory = JunkCategory.APP_CACHE) = JunkItem(
        file = file,
        category = category,
        sizeBytes = FileTree.sizeOf(file),
        lastModified = file.lastModified(),
        isDirectory = file.isDirectory,
    )

    private fun file(parent: File, name: String, bytes: Int): File =
        File(parent, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 7 })
        }

    @Test
    fun `fichier simple - compté une fois, supprimé`() {
        val f = file(temp.root, "a.bin", 512)

        val outcome = FileDeleter.delete(listOf(item(f)))

        assertEquals(512L, outcome.freedBytes)
        assertEquals(1, outcome.deletedFiles)
        assertEquals(0, outcome.failed)
        assertFalse(f.exists())
    }

    @Test
    fun `arborescence - tout le contenu compté, dossiers supprimés en remontant`() {
        val root = temp.newFolder("arbre")
        file(root, "a.bin", 100)
        file(root, "sous/b.bin", 200)
        file(root, "sous/encore/c.bin", 300)

        val outcome = FileDeleter.delete(listOf(item(root)))

        assertEquals(600L, outcome.freedBytes)
        assertEquals(3, outcome.deletedFiles)
        assertEquals(3, outcome.deletedDirs) // arbre + sous + encore
        assertEquals(0, outcome.failed)
        assertFalse(root.exists())
    }

    @Test
    fun `arborescence profonde - pas de débordement de pile`() {
        var dir = temp.newFolder("profond")
        repeat(2_000) {
            dir = File(dir, "n").apply { mkdir() }
        }
        file(dir, "fond.bin", 42)

        val outcome = FileDeleter.delete(listOf(item(File(temp.root, "profond"))))

        assertEquals(42L, outcome.freedBytes)
        assertEquals(0, outcome.failed)
        assertFalse(File(temp.root, "profond").exists())
    }

    @Test
    fun `chemin protégé - refusé et compté en échec`() {
        val keep = temp.newFolder("intouchable")
        val f = file(keep, "precieux.bin", 999)

        val outcome = FileDeleter.delete(
            items = listOf(item(keep)),
            protectedPaths = setOf(keep.absolutePath),
        )

        assertEquals(0L, outcome.freedBytes)
        assertEquals(1, outcome.failed)
        assertTrue(f.exists())
    }

    @Test
    fun `lien symbolique - jamais suivi, la cible survit`() {
        val cible = temp.newFolder("cible")
        val precieux = file(cible, "precieux.bin", 1234)
        val cache = temp.newFolder("cache")
        java.nio.file.Files.createSymbolicLink(
            File(cache, "raccourci").toPath(),
            cible.toPath(),
        )
        file(cache, "vrai.bin", 10)

        val outcome = FileDeleter.delete(listOf(item(cache)))

        assertTrue("la cible du lien ne doit pas être touchée", precieux.exists())
        assertEquals(10L, outcome.freedBytes)
        assertFalse(cache.exists())
    }

    @Test
    fun `élément déjà disparu - ni gain ni échec`() {
        val f = file(temp.root, "fantome.bin", 50)
        val junk = item(f)
        f.delete()

        val outcome = FileDeleter.delete(listOf(junk))

        assertEquals(0L, outcome.freedBytes)
        assertEquals(0, outcome.deletedFiles)
        assertEquals(0, outcome.failed)
    }

    @Test
    fun `comptabilité - un enfant supprimé compte même si le parent résiste`() {
        // Régression : l'ancien code mesurait la taille puis testait delete() sur la
        // racine ; un parent non supprimable annulait tout le gain.
        val root = temp.newFolder("mixte")
        file(root, "supprimable.bin", 800)
        val outcome = FileDeleter.delete(listOf(item(root)))
        assertEquals(800L, outcome.freedBytes)
    }

    @Test
    fun `somme de deux résultats`() {
        val a = FileDeleter.Outcome(10, 1, 0, 0, listOf("/a"))
        val b = FileDeleter.Outcome(5, 0, 1, 2, listOf("/b"))
        val total = a + b
        assertEquals(15L, total.freedBytes)
        assertEquals(1, total.deletedFiles)
        assertEquals(1, total.deletedDirs)
        assertEquals(2, total.failed)
        assertTrue(total.hasFailures)
        assertEquals(listOf("/a", "/b"), total.deletedPaths)
    }
}
