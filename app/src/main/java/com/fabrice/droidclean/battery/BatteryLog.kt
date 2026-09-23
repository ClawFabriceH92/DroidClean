package com.fabrice.droidclean.battery

import android.content.Context
import java.io.File

/**
 * Historique des relevés de batterie, sur disque.
 *
 * Un fichier CSV plutôt que des SharedPreferences : celles-ci sont chargées
 * intégralement en mémoire au premier accès, et y garder plusieurs milliers de
 * lignes serait un mauvais usage. Les données ne quittent jamais l'appareil.
 */
internal object BatteryLog {

    private const val DIR = "battery"
    private const val FILE = "samples.csv"

    /** ~2 relevés/heure pendant 30 jours, avec de la marge. */
    private const val MAX_SAMPLES = 3_000
    private const val MAX_AGE_MS = 30L * 86_400_000L

    /** Deux relevés identiques rapprochés n'apportent rien. */
    private const val MIN_INTERVAL_MS = 4L * 60_000L

    fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    /**
     * Enregistre un relevé, sauf s'il est redondant. Retourne `true` s'il a été
     * écrit. Synchronisé : le worker, le receiver et l'activité peuvent écrire
     * depuis des threads différents.
     */
    @Synchronized
    fun record(context: Context, sample: BatterySample): Boolean {
        val existing = readSamples(context)
        val last = existing.lastOrNull()
        if (last != null &&
            last.percent == sample.percent &&
            last.charging == sample.charging &&
            sample.at - last.at < MIN_INTERVAL_MS
        ) {
            return false
        }

        val kept = (existing + sample)
            .filter { sample.at - it.at <= MAX_AGE_MS }
            .takeLast(MAX_SAMPLES)

        return try {
            file(context).writeText(kept.joinToString("\n") { serialize(it) })
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun samples(context: Context): List<BatterySample> = readSamples(context)

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun readSamples(context: Context): List<BatterySample> = try {
        val f = file(context)
        if (!f.isFile) {
            emptyList()
        } else {
            f.readLines().mapNotNull(::parse).sortedBy { it.at }
        }
    } catch (_: Exception) {
        // Fichier corrompu ou illisible : mieux vaut repartir de zéro que planter.
        emptyList()
    }

    private fun serialize(sample: BatterySample): String =
        "${sample.at},${sample.percent},${if (sample.charging) 1 else 0},${sample.realtimeMs}"

    private fun parse(line: String): BatterySample? {
        val parts = line.split(",")
        if (parts.size != 4) return null
        val at = parts[0].toLongOrNull() ?: return null
        val percent = parts[1].toIntOrNull() ?: return null
        val charging = parts[2] == "1"
        val realtime = parts[3].toLongOrNull() ?: return null
        return BatterySample(at, percent, charging, realtime)
    }
}
