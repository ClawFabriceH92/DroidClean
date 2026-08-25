package com.fabrice.droidclean.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formatage des tailles, pourcentages et durées.
 *
 * Volontairement sans dépendance Android : entièrement couvert par les tests
 * unitaires. Les libellés d'unités sont injectés ([Units]) pour rester
 * traduisibles — la couche Android les résout depuis `strings.xml`.
 */
object Formats {

    /** Libellés d'unités binaires. */
    data class Units(
        val bytes: String,
        val kilo: String,
        val mega: String,
        val giga: String,
        val tera: String,
    ) {
        companion object {
            /** Repli : utilisé par les tests et par le code appelé sans Context. */
            val FRENCH = Units("o", "Ko", "Mo", "Go", "To")
        }
    }

    private const val KO = 1024L
    private const val MO = KO * 1024L
    private const val GO = MO * 1024L
    private const val TO = GO * 1024L

    private fun formatter(locale: Locale): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 1
        }

    /** Taille lisible : « 512 o », « 1,5 Ko », « 2,0 Go »… */
    fun bytes(
        value: Long,
        locale: Locale = Locale.getDefault(),
        units: Units = Units.FRENCH,
    ): String {
        val v = if (value < 0) 0L else value
        val nf = formatter(locale)
        return when {
            v < KO -> "$v ${units.bytes}"
            v < MO -> "${nf.format(v.toDouble() / KO)} ${units.kilo}"
            v < GO -> "${nf.format(v.toDouble() / MO)} ${units.mega}"
            v < TO -> "${nf.format(v.toDouble() / GO)} ${units.giga}"
            else -> "${nf.format(v.toDouble() / TO)} ${units.tera}"
        }
    }

    /** Taille exprimée en mégaoctets (RAM). */
    fun megabytes(
        mb: Long,
        locale: Locale = Locale.getDefault(),
        units: Units = Units.FRENCH,
    ): String {
        val v = if (mb < 0) 0L else mb
        return if (v < 1024L) {
            "$v ${units.mega}"
        } else {
            "${formatter(locale).format(v.toDouble() / 1024.0)} ${units.giga}"
        }
    }

    /** Pourcentage entier de [part] sur [total], borné à 0..100. */
    fun percent(part: Long, total: Long): Int {
        if (total <= 0L) return 0
        val pct = (part * 100L / total).toInt()
        return pct.coerceIn(0, 100)
    }

    /**
     * Nombre de jours pleins écoulés depuis [millis].
     * Retourne -1 si l'horodatage est inconnu (<= 0) ou dans le futur.
     */
    fun daysSince(millis: Long, now: Long = System.currentTimeMillis()): Int {
        if (millis <= 0L || millis > now) return -1
        return ((now - millis) / 86_400_000L).toInt()
    }
}
