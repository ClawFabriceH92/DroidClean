package com.fabrice.droidclean.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formatage des tailles et pourcentages.
 * Volontairement sans dépendance Android : couvert par les tests unitaires.
 */
object Formats {

    private const val KO = 1024L
    private const val MO = 1024L * 1024L
    private const val GO = 1024L * 1024L * 1024L

    private fun formatter(locale: Locale): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 1
        }

    /** Taille lisible : « 512 o », « 1,5 Ko », « 2,0 Go »… */
    fun bytes(value: Long, locale: Locale = Locale.getDefault()): String {
        val v = if (value < 0) 0L else value
        val nf = formatter(locale)
        return when {
            v < KO -> "$v o"
            v < MO -> "${nf.format(v.toDouble() / KO)} Ko"
            v < GO -> "${nf.format(v.toDouble() / MO)} Mo"
            else -> "${nf.format(v.toDouble() / GO)} Go"
        }
    }

    /** Taille exprimée en mégaoctets (RAM). */
    fun megabytes(mb: Long, locale: Locale = Locale.getDefault()): String {
        val v = if (mb < 0) 0L else mb
        return if (v < 1024L) "$v Mo" else "${formatter(locale).format(v.toDouble() / 1024.0)} Go"
    }

    /** Pourcentage entier de [part] sur [total], borné à 0..100. */
    fun percent(part: Long, total: Long): Int {
        if (total <= 0L) return 0
        val pct = (part * 100L / total).toInt()
        return pct.coerceIn(0, 100)
    }
}
