package com.fabrice.droidclean.apps

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Poids réel de chaque application : APK + données + cache.
 *
 * C'est la réponse honnête à « où sont passés mes 40 Go ». Elle remplace le
 * nettoyage des caches tiers, impossible sans root depuis Android 7.
 *
 * Nécessite l'accès aux statistiques d'usage (`PACKAGE_USAGE_STATS`), accordé
 * manuellement depuis Réglages ▸ Accès spécial. Sans lui, [list] retourne une
 * liste vide et l'interface propose d'ouvrir le bon écran.
 */
object AppStorage {

    data class Entry(
        val packageName: String,
        val label: String,
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
        /** Dernière utilisation, 0 si inconnue. */
        val lastUsedAt: Long,
        val isSystem: Boolean,
    ) {
        val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
    }

    /** L'accès aux statistiques d'usage est-il accordé ? */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        } catch (_: Exception) {
            return false
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT renvoie à la permission déclarée : on vérifie pour de bon.
            AppOpsManager.MODE_DEFAULT -> canQueryStats(context)
            else -> false
        }
    }

    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))

    fun appInfoIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )

    /**
     * Poids de toutes les applications, du plus lourd au plus léger.
     * À appeler hors du thread principal : compte une requête système par paquet.
     */
    fun list(context: Context): List<Entry> {
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: return emptyList()
        val pm = context.packageManager
        val lastUsed = lastUsedByPackage(context)
        val user = Process.myUserHandle()

        return Packages.installed(context).mapNotNull { info ->
            val size = try {
                stats.queryStatsForPackage(info.storageUuid, info.packageName, user)
            } catch (_: Exception) {
                // Paquet disparu entre-temps, ou accès refusé : on l'ignore.
                return@mapNotNull null
            }
            Entry(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                appBytes = size.appBytes,
                dataBytes = (size.dataBytes - size.cacheBytes).coerceAtLeast(0L),
                cacheBytes = size.cacheBytes,
                lastUsedAt = lastUsed[info.packageName] ?: 0L,
                isSystem = !Packages.isUserApp(info),
            )
        }.sortedWith(
            compareByDescending<Entry> { it.totalBytes }.thenBy { it.label.lowercase() }
        )
    }

    /** Dernière utilisation de chaque paquet sur les 365 derniers jours. */
    private fun lastUsedByPackage(context: Context): Map<String, Long> {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val now = System.currentTimeMillis()
        return try {
            usage.queryUsageStats(
                UsageStatsManager.INTERVAL_YEARLY,
                now - 365L * 86_400_000L,
                now,
            ).orEmpty()
                .groupBy { it.packageName }
                .mapValues { (_, entries) -> entries.maxOf { it.lastTimeUsed } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Test réel : une requête sur notre propre paquet échoue si l'accès manque. */
    private fun canQueryStats(context: Context): Boolean = try {
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        stats.queryStatsForPackage(
            android.os.storage.StorageManager.UUID_DEFAULT,
            context.packageName,
            Process.myUserHandle(),
        )
        true
    } catch (_: Exception) {
        false
    }
}
