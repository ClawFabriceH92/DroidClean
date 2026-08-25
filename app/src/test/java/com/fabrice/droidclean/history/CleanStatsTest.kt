package com.fabrice.droidclean.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CleanStatsTest {

    private val utc = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `clé de mois`() {
        assertEquals("2026-08", CleanStats.monthKey(at(2026, 8, 25), utc))
        assertEquals("2026-01", CleanStats.monthKey(at(2026, 1, 1), utc))
    }

    @Test
    fun `cumul dans le mois et sur toute la durée`() {
        var s = CleanStats.Snapshot.EMPTY
        s = CleanStats.record(s, 1_000, at(2026, 8, 1), utc)
        s = CleanStats.record(s, 2_500, at(2026, 8, 20), utc)
        s = CleanStats.record(s, 700, at(2026, 7, 3), utc)

        assertEquals(3_500L, s.bytesIn("2026-08"))
        assertEquals(700L, s.bytesIn("2026-07"))
        assertEquals(4_200L, s.allTimeBytes)
        assertEquals(3, s.cleanCount)
        assertEquals(at(2026, 8, 20), s.lastCleanAt)
    }

    @Test
    fun `gain négatif ramené à zéro mais le passage est compté`() {
        val s = CleanStats.record(CleanStats.Snapshot.EMPTY, -50, at(2026, 8, 1), utc)
        assertEquals(0L, s.allTimeBytes)
        assertEquals(1, s.cleanCount)
    }

    @Test
    fun `un nettoyage antérieur ne fait pas reculer la date du dernier`() {
        var s = CleanStats.record(CleanStats.Snapshot.EMPTY, 10, at(2026, 8, 20), utc)
        s = CleanStats.record(s, 10, at(2026, 8, 1), utc)
        assertEquals(at(2026, 8, 20), s.lastCleanAt)
    }

    @Test
    fun `seuls les 24 derniers mois sont conservés`() {
        var s = CleanStats.Snapshot.EMPTY
        for (i in 0 until 30) {
            s = CleanStats.record(s, 100, at(2024 + i / 12, i % 12 + 1, 5), utc)
        }
        assertEquals(CleanStats.MONTHS_KEPT, s.months.size)
        assertEquals(3_000L, s.allTimeBytes) // le cumul total, lui, n'est pas tronqué
    }

    @Test
    fun `aller-retour sérialisation`() {
        var s = CleanStats.Snapshot.EMPTY
        s = CleanStats.record(s, 1_234, at(2026, 8, 10), utc)
        s = CleanStats.record(s, 5_678, at(2026, 6, 10), utc)

        val restored = CleanStats.parse(CleanStats.serialize(s))

        assertEquals(s.months, restored.months)
        assertEquals(s.allTimeBytes, restored.allTimeBytes)
        assertEquals(s.lastCleanAt, restored.lastCleanAt)
        assertEquals(s.cleanCount, restored.cleanCount)
    }

    @Test
    fun `données corrompues - historique vide plutôt qu'exception`() {
        assertEquals(CleanStats.Snapshot.EMPTY, CleanStats.parse(null))
        assertEquals(CleanStats.Snapshot.EMPTY, CleanStats.parse(""))
        assertEquals(CleanStats.Snapshot.EMPTY, CleanStats.parse("n'importe quoi"))
        assertEquals(CleanStats.Snapshot.EMPTY, CleanStats.parse("a|b|c"))
        // Une entrée de mois illisible est ignorée, le reste survit.
        assertEquals(42L, CleanStats.parse("2026-08=42;pourri|42|0|1").bytesIn("2026-08"))
    }
}
