package com.fabrice.droidclean.ui

import android.content.Context
import com.fabrice.droidclean.R
import com.fabrice.droidclean.util.Formats

/** Rendu d'une durée avec les libellés de la langue courante. */
object Durations {

    /** « 1 j 4 h », « 2 h 30 min », « 45 min ». */
    fun format(context: Context, millis: Long): String {
        val parts = Formats.splitDuration(millis)
        return when {
            parts.days > 0 -> context.getString(R.string.duration_days_hours, parts.days, parts.hours)
            parts.hours > 0 ->
                context.getString(R.string.duration_hours_minutes, parts.hours, parts.minutes)
            else -> context.getString(R.string.duration_minutes, parts.minutes)
        }
    }
}
