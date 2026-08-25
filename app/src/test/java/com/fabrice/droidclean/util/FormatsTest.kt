package com.fabrice.droidclean.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatsTest {

    private val fr = Locale.FRANCE
    private val en = Locale.UK
    private val english = Formats.Units("B", "KB", "MB", "GB", "TB")

    @Test
    fun `bytes - unités`() {
        assertEquals("0 o", Formats.bytes(0, fr))
        assertEquals("512 o", Formats.bytes(512, fr))
        assertEquals("1,0 Ko", Formats.bytes(1024, fr))
        assertEquals("1,5 Ko", Formats.bytes(1536, fr))
        assertEquals("1,0 Mo", Formats.bytes(1024L * 1024, fr))
        assertEquals("2,0 Go", Formats.bytes(2L * 1024 * 1024 * 1024, fr))
    }

    @Test
    fun `bytes - téraoctets`() {
        assertEquals("1,0 To", Formats.bytes(1024L * 1024 * 1024 * 1024, fr))
        assertEquals("2,5 To", Formats.bytes(2560L * 1024 * 1024 * 1024, fr))
    }

    @Test
    fun `bytes - unités traduisibles et séparateur décimal local`() {
        assertEquals("1.5 KB", Formats.bytes(1536, en, english))
        assertEquals("2.0 GB", Formats.bytes(2L * 1024 * 1024 * 1024, en, english))
        assertEquals("512 B", Formats.bytes(512, en, english))
    }

    @Test
    fun `bytes - valeur négative ramenée à zéro`() {
        assertEquals("0 o", Formats.bytes(-42, fr))
    }

    @Test
    fun `megabytes - bascule en Go`() {
        assertEquals("512 Mo", Formats.megabytes(512, fr))
        assertEquals("2,0 Go", Formats.megabytes(2048, fr))
        assertEquals("512 MB", Formats.megabytes(512, en, english))
    }

    @Test
    fun `percent - borné et robuste`() {
        assertEquals(50, Formats.percent(50, 100))
        assertEquals(0, Formats.percent(10, 0))
        assertEquals(100, Formats.percent(200, 100))
        assertEquals(0, Formats.percent(-5, 100))
    }

    @Test
    fun `daysSince - jours pleins, inconnu marqué -1`() {
        val now = 1_000L * 86_400_000L
        assertEquals(0, Formats.daysSince(now - 3_600_000L, now))
        assertEquals(1, Formats.daysSince(now - 86_400_000L, now))
        assertEquals(30, Formats.daysSince(now - 30L * 86_400_000L, now))
        assertEquals(-1, Formats.daysSince(0L, now))
        assertEquals(-1, Formats.daysSince(now + 86_400_000L, now))
    }
}
