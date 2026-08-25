package com.fabrice.droidclean.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * État du stockage, tous volumes confondus.
 *
 * Le volume principal est mesuré via [StorageStatsManager] et non via `StatFs` :
 * `StatFs` ignore les blocs réservés et annonce un total qui ne correspond pas à
 * celui affiché par les Réglages Android. `StatFs` ne sert plus que de repli.
 */
object StorageInfo {

    data class Volume(
        val label: String,
        val totalBytes: Long,
        val freeBytes: Long,
        val isPrimary: Boolean,
        val isRemovable: Boolean,
    ) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    }

    /** Volume principal, ou null si le stockage est indisponible. */
    fun primary(context: Context): Volume? {
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        val fromStats = stats?.let {
            try {
                Volume(
                    label = "",
                    totalBytes = it.getTotalBytes(StorageManager.UUID_DEFAULT),
                    freeBytes = it.getFreeBytes(StorageManager.UUID_DEFAULT),
                    isPrimary = true,
                    isRemovable = false,
                )
            } catch (_: Exception) {
                null
            }
        }
        return fromStats ?: statFsVolume(Environment.getExternalStorageDirectory(), true, false, "")
    }

    /**
     * Tous les volumes montés : interne + carte SD éventuelle.
     * `getExternalFilesDirs` est le seul moyen, dès l'API 26, d'obtenir un chemin
     * exploitable sur chaque volume.
     */
    fun volumes(context: Context): List<Volume> {
        val out = ArrayList<Volume>()
        primary(context)?.let { out.add(it) }

        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .drop(1) // l'index 0 est le volume principal, déjà couvert
            .forEach { dir ->
                val description = manager?.let {
                    runCatching { it.getStorageVolume(dir)?.getDescription(context) }.getOrNull()
                }
                statFsVolume(
                    dir = dir,
                    isPrimary = false,
                    isRemovable = true,
                    label = description.orEmpty(),
                )?.let { out.add(it) }
            }
        return out
    }

    private fun statFsVolume(
        dir: File?,
        isPrimary: Boolean,
        isRemovable: Boolean,
        label: String,
    ): Volume? {
        val path = dir?.takeIf { it.isDirectory } ?: return null
        return try {
            val stat = StatFs(path.absolutePath)
            Volume(
                label = label,
                totalBytes = stat.blockCountLong * stat.blockSizeLong,
                freeBytes = stat.availableBlocksLong * stat.blockSizeLong,
                isPrimary = isPrimary,
                isRemovable = isRemovable,
            )
        } catch (_: Exception) {
            null
        }
    }
}
