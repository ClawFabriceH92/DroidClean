package com.fabrice.droidclean.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Historique des nettoyages : « 4,2 Go libérés ce mois-ci ».
 *
 * Sérialisation compacte et sans dépendance (`2026-08=1234;2026-07=99|total|dernier|nombre`),
 * stockée telle quelle dans les SharedPreferences par [CleanHistory]. Pure, donc testée.
 */
object CleanStats {

    /** Nombre de mois conservés : au-delà, la ligne de préférences grossit pour rien. */
    const val MONTHS_KEPT = 24

    data class Snapshot(
        val months: Map<String, Long> = emptyMap(),
        val allTimeBytes: Long = 0L,
        val lastCleanAt: Long = 0L,
        val cleanCount: Int = 0,
    ) {
        fun bytesIn(monthKey: String): Long = months[monthKey] ?: 0L

        companion object {
            val EMPTY = Snapshot()
        }
    }

    private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")

    fun monthKey(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        MONTH_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Ajoute un nettoyage. Un gain nul ou négatif compte comme un passage sans gain. */
    fun record(
        snapshot: Snapshot,
        freedBytes: Long,
        at: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        val freed = freedBytes.coerceAtLeast(0L)
        val key = monthKey(at, zone)
        val months = LinkedHashMap(snapshot.months)
        months[key] = (months[key] ?: 0L) + freed
        return Snapshot(
            months = months.entries
                .sortedByDescending { it.key }
                .take(MONTHS_KEPT)
                .associate { it.key to it.value },
            allTimeBytes = snapshot.allTimeBytes + freed,
            lastCleanAt = maxOf(snapshot.lastCleanAt, at),
            cleanCount = snapshot.cleanCount + 1,
        )
    }

    fun serialize(snapshot: Snapshot): String {
        val months = snapshot.months.entries
            .sortedByDescending { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
        return listOf(
            months,
            snapshot.allTimeBytes.toString(),
            snapshot.lastCleanAt.toString(),
            snapshot.cleanCount.toString(),
        ).joinToString("|")
    }

    /** Tolérant : toute donnée illisible retombe sur un historique vide plutôt que de planter. */
    fun parse(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) return Snapshot.EMPTY
        val parts = raw.split("|")
        if (parts.size != 4) return Snapshot.EMPTY
        val months = parts[0].split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val kv = entry.split("=")
                if (kv.size != 2) return@mapNotNull null
                val value = kv[1].toLongOrNull() ?: return@mapNotNull null
                kv[0] to value
            }
            .toMap()
        return Snapshot(
            months = months,
            allTimeBytes = parts[1].toLongOrNull() ?: 0L,
            lastCleanAt = parts[2].toLongOrNull() ?: 0L,
            cleanCount = parts[3].toIntOrNull() ?: 0,
        )
    }
}
