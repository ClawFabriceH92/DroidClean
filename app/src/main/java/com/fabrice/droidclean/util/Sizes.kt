package com.fabrice.droidclean.util

import android.content.Context
import com.fabrice.droidclean.R

/**
 * Pont entre [Formats] (pur, testé) et les ressources : les libellés d'unités
 * viennent de `strings.xml`, donc suivent la langue de l'appareil.
 */
object Sizes {

    fun units(context: Context): Formats.Units = Formats.Units(
        bytes = context.getString(R.string.unit_bytes),
        kilo = context.getString(R.string.unit_kilobytes),
        mega = context.getString(R.string.unit_megabytes),
        giga = context.getString(R.string.unit_gigabytes),
        tera = context.getString(R.string.unit_terabytes),
    )

    fun bytes(context: Context, value: Long): String =
        Formats.bytes(value, units = units(context))

    fun megabytes(context: Context, mb: Long): String =
        Formats.megabytes(mb, units = units(context))
}
