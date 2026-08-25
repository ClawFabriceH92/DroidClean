package com.fabrice.droidclean.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JunkScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(parent: File, name: String, bytes: Int): File =
        File(parent, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }

    private fun dir(parent: File, path: String): File =
        File(parent, path).apply { mkdirs() }

    @Test
    fun `téléchargements - chaque entrée de premier niveau devient un élément`() {
        val downloads = temp.newFolder("Download")
        file(downloads, "facture.pdf", 100)
        file(downloads, "archive/dedans.bin", 250)

        val items = JunkScanner(JunkScanner.Layout(downloads = downloads)).scan()

        assertEquals(2, items.size)
        assertTrue(items.all { it.category == JunkCategory.DOWNLOADS })
        assertEquals(350L, items.sumOf { it.sizeBytes })
        // Le dossier compte la taille de son contenu, pas la sienne.
        assertEquals(250L, items.first { it.name == "archive" }.sizeBytes)
    }

    @Test
    fun `caches de l'app - contenu listé, dossier de cache lui-même préservé`() {
        val cache = temp.newFolder("cache")
        file(cache, "image-1.tmp", 40)
        file(cache, "sous/dossier/gros.bin", 60)

        val items = JunkScanner(JunkScanner.Layout(appCaches = listOf(cache))).scan()

        assertEquals(2, items.size)
        assertTrue(items.all { it.category == JunkCategory.APP_CACHE })
        assertFalse(items.any { it.file == cache })
    }

    @Test
    fun `Android media - résidu détecté seulement si le paquet est absent`() {
        val root = temp.newFolder("sdcard")
        file(root, "Android/media/com.exemple.vivante/cache/x.bin", 500)
        file(root, "Android/media/com.exemple.vivante/photos/garder.jpg", 900)
        file(root, "Android/media/com.exemple.morte/quoi.bin", 700)

        val items = JunkScanner(
            JunkScanner.Layout(
                externalRoot = root,
                installedPackages = setOf("com.exemple.vivante"),
            )
        ).scan()

        val cache = items.single { it.category == JunkCategory.APP_MEDIA_CACHE }
        assertEquals(500L, cache.sizeBytes)
        val leftover = items.single { it.category == JunkCategory.LEFTOVER }
        assertEquals("com.exemple.morte", leftover.name)
        // Les photos d'une app installée ne sont jamais proposées.
        assertFalse(items.any { it.path.endsWith("garder.jpg") })
    }

    @Test
    fun `Android media - sans liste de paquets, aucun résidu n'est proposé`() {
        val root = temp.newFolder("sdcard")
        file(root, "Android/media/com.exemple.morte/quoi.bin", 700)

        val items = JunkScanner(JunkScanner.Layout(externalRoot = root)).scan()

        // Liste vide = information non fiable : tout passerait pour un résidu.
        assertTrue(items.none { it.category == JunkCategory.LEFTOVER })
    }

    @Test
    fun `vignettes et LOST DIR`() {
        val root = temp.newFolder("sdcard")
        file(root, "DCIM/.thumbnails/1.jpg", 30)
        file(root, "LOST.DIR/fragment.chk", 80)

        val items = JunkScanner(JunkScanner.Layout(externalRoot = root)).scan()

        assertEquals(30L, items.single { it.category == JunkCategory.THUMBNAILS }.sizeBytes)
        assertEquals(80L, items.single { it.category == JunkCategory.LOST_DIR }.sizeBytes)
    }

    @Test
    fun `dossiers vides - les dossiers standard d'Android sont épargnés`() {
        val root = temp.newFolder("sdcard")
        dir(root, "DCIM")               // standard et vide : jamais proposé
        dir(root, "MonDossierVide")     // utilisateur et vide : proposé
        dir(root, "Pictures/Vacances")  // vide, mais imbriqué : proposé
        file(root, "Documents/note.txt", 10)

        val items = JunkScanner(JunkScanner.Layout(externalRoot = root)).scan()
        val empties = items.filter { it.category == JunkCategory.EMPTY_DIR }.map { it.name }

        assertTrue("MonDossierVide" in empties)
        assertTrue("Vacances" in empties)
        assertFalse("DCIM" in empties)
        assertTrue(items.filter { it.category == JunkCategory.EMPTY_DIR }.all { it.sizeBytes == 0L })
    }

    @Test
    fun `chemins protégés - jamais proposés, ni eux ni leurs parents`() {
        val downloads = temp.newFolder("Download")
        val ours = dir(downloads, "DroidClean")
        file(ours, "droidclean-update.apk", 400)
        file(downloads, "autre.bin", 100)

        val items = JunkScanner(
            JunkScanner.Layout(
                downloads = downloads,
                protectedPaths = setOf(ours.absolutePath),
            )
        ).scan()

        assertEquals(1, items.size)
        assertEquals("autre.bin", items.single().name)
    }

    @Test
    fun `tri - du plus volumineux au plus léger`() {
        val downloads = temp.newFolder("Download")
        file(downloads, "petit.bin", 10)
        file(downloads, "gros.bin", 1000)
        file(downloads, "moyen.bin", 100)

        val items = JunkScanner(JunkScanner.Layout(downloads = downloads)).scan()

        assertEquals(listOf("gros.bin", "moyen.bin", "petit.bin"), items.map { it.name })
    }

    @Test
    fun `stockage inaccessible - analyse vide plutôt qu'exception`() {
        val items = JunkScanner(
            JunkScanner.Layout(
                downloads = File(temp.root, "inexistant"),
                externalRoot = File(temp.root, "pas-la-non-plus"),
            )
        ).scan()

        assertTrue(items.isEmpty())
    }
}
