package com.fabrice.droidclean.update

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérification des mises à jour sur GitHub Releases (repo ClawFabriceH92/DroidClean).
 * Sans token : 60 requêtes/h par IP — largement suffisant pour une vérif quotidienne.
 */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String?,
    val publishedAt: String?,
    /** Taille de l'APK annoncée par GitHub, 0 si inconnue. */
    val sizeBytes: Long = 0L,
)

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/ClawFabriceH92/DroidClean/releases?per_page=10"

    /**
     * Récupère la version la plus récente disposant d'un APK.
     * Retourne null si aucune release exploitable (ou réseau KO).
     */
    fun latestWithApk(): UpdateInfo? {
        val conn = try {
            URL(RELEASES_URL).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "DroidClean-UpdateChecker")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parseReleases(text)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseReleases(jsonText: String): UpdateInfo? {
        return try {
            val releases = JSONArray(jsonText)
            var best: UpdateInfo? = null
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                // Ni brouillon, ni pré-version : on n'installe que du stable.
                if (rel.optBoolean("draft")) continue
                if (rel.optBoolean("prerelease")) continue

                val tag = rel.optString("tag_name", "").trim().removePrefix("v")
                // Les releases « flottantes » (tag "latest") n'ont pas de version
                // comparable : les retenir masquerait la vraie dernière version.
                if (!isVersion(tag)) continue

                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (!asset.optString("name", "").endsWith(".apk")) continue
                    val url = asset.optString("browser_download_url", "")
                    if (url.isEmpty()) continue
                    val info = UpdateInfo(
                        versionName = tag,
                        downloadUrl = url,
                        notes = rel.optString("body").takeIf { it.isNotBlank() },
                        publishedAt = rel.optString("published_at").takeIf { it.isNotBlank() },
                        sizeBytes = asset.optLong("size", 0L),
                    )
                    if (best == null || compareVersions(info.versionName, best.versionName) > 0) {
                        best = info
                    }
                    break // un APK par release suffit
                }
            }
            best
        } catch (_: Exception) {
            null // JSON invalide → pas de mise à jour
        }
    }

    /** Une version exploitable : au moins un segment, tous numériques. Ex. « 1.2.3 ». */
    internal fun isVersion(tag: String): Boolean {
        if (tag.isBlank()) return false
        val segments = tag.split(".")
        return segments.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** Compare deux versions "x.y.z" (segments numériques). >0 si a > b. */
    fun compareVersions(a: String, b: String): Int {
        val sa = a.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val sb = b.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val va = sa.getOrElse(i) { 0 }
            val vb = sb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
