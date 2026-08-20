package com.fabrice.droidclean.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compareVersions - égalité`() {
        assertEquals(0, UpdateChecker.compareVersions("0.2.0", "0.2.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("0.3", "0.3.0"))
    }

    @Test
    fun `compareVersions - plus récent`() {
        assertTrue(UpdateChecker.compareVersions("0.3.0", "0.2.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("0.2.10", "0.2.9") > 0)
    }

    @Test
    fun `compareVersions - plus ancien`() {
        assertTrue(UpdateChecker.compareVersions("0.2.0", "0.3.0") < 0)
        assertTrue(UpdateChecker.compareVersions("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun `isVersion - seules les versions numériques comptent`() {
        assertTrue(UpdateChecker.isVersion("1.0.1"))
        assertTrue(UpdateChecker.isVersion("2"))
        assertFalse(UpdateChecker.isVersion("latest"))
        assertFalse(UpdateChecker.isVersion(""))
        assertFalse(UpdateChecker.isVersion("1.0.0-beta"))
        assertFalse(UpdateChecker.isVersion("1..0"))
    }

    @Test
    fun `parseReleases - prend la version la plus haute avec APK`() {
        val json = """
            [
              {"tag_name": "v0.2.0", "draft": false,
               "body": "fix caméra", "published_at": "2026-08-15T20:20:33Z",
               "assets": [{"name": "droidclean-v0.2.0.apk", "browser_download_url": "https://x/v0.2.0.apk"}]},
              {"tag_name": "v0.3.0", "draft": false,
               "body": "mise à jour auto", "published_at": "2026-08-16T08:00:00Z",
               "assets": [{"name": "droidclean-v0.3.0.apk", "browser_download_url": "https://x/v0.3.0.apk"}]},
              {"tag_name": "v0.4.0-beta", "draft": true,
               "assets": [{"name": "droidclean-v0.4.0.apk", "browser_download_url": "https://x/v0.4.0.apk"}]}
            ]
        """.trimIndent()
        val info = UpdateChecker.parseReleases(json)
        assertEquals("0.3.0", info?.versionName)
        assertEquals("https://x/v0.3.0.apk", info?.downloadUrl)
        assertEquals("mise à jour auto", info?.notes)
    }

    @Test
    fun `parseReleases - ignore les drafts`() {
        val json = """
            [
              {"tag_name": "v9.9.9", "draft": true,
               "assets": [{"name": "droidclean-v9.9.9.apk", "browser_download_url": "https://x/9.9.9.apk"}]}
            ]
        """.trimIndent()
        assertNull(UpdateChecker.parseReleases(json))
    }

    @Test
    fun `parseReleases - ignore les pré-versions`() {
        val json = """
            [
              {"tag_name": "v9.9.9", "draft": false, "prerelease": true,
               "assets": [{"name": "droidclean-v9.9.9.apk", "browser_download_url": "https://x/9.9.9.apk"}]},
              {"tag_name": "v1.0.0", "draft": false, "prerelease": false,
               "assets": [{"name": "droidclean-v1.0.0.apk", "browser_download_url": "https://x/1.0.0.apk"}]}
            ]
        """.trimIndent()
        assertEquals("1.0.0", UpdateChecker.parseReleases(json)?.versionName)
    }

    @Test
    fun `parseReleases - ignore la release flottante latest`() {
        // La CI publie une release "latest" écrasée à chaque push sur main :
        // son tag n'est pas une version, elle ne doit jamais être proposée.
        val json = """
            [
              {"tag_name": "latest", "draft": false,
               "assets": [{"name": "droidclean-v1.0.1.apk", "browser_download_url": "https://x/latest.apk"}]},
              {"tag_name": "v1.0.0", "draft": false,
               "assets": [{"name": "droidclean-v1.0.0.apk", "browser_download_url": "https://x/1.0.0.apk"}]}
            ]
        """.trimIndent()
        val info = UpdateChecker.parseReleases(json)
        assertEquals("1.0.0", info?.versionName)
        assertEquals("https://x/1.0.0.apk", info?.downloadUrl)
    }

    @Test
    fun `parseReleases - ignore les releases sans APK`() {
        val json = """
            [
              {"tag_name": "v2.0.0", "draft": false,
               "assets": [{"name": "sources.zip", "browser_download_url": "https://x/sources.zip"}]}
            ]
        """.trimIndent()
        assertNull(UpdateChecker.parseReleases(json))
    }

    @Test
    fun `parseReleases - vide`() {
        assertNull(UpdateChecker.parseReleases("[]"))
        assertNull(UpdateChecker.parseReleases("pas du json"))
    }
}
