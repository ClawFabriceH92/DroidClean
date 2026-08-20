package com.fabrice.droidclean.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatsTest {

    private val fr = Locale.FRANCE

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
    fun `bytes - valeur négative ramenée à zéro`() {
        assertEquals("0 o", Formats.bytes(-42, fr))
    }

    @Test
    fun `megabytes - bascule en Go`() {
        assertEquals("512 Mo", Formats.megabytes(512, fr))
        assertEquals("2,0 Go", Formats.megabytes(2048, fr))
    }

    @Test
    fun `percent - borné et robuste`() {
        assertEquals(50, Formats.percent(50, 100))
        assertEquals(0, Formats.percent(10, 0))
        assertEquals(100, Formats.percent(200, 100))
        assertEquals(0, Formats.percent(-5, 100))
    }
}
