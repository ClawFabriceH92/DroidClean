package com.fabrice.droidclean.history

import android.content.Context
import com.fabrice.droidclean.clean.Cleaner

/**
 * Persistance de l'historique des nettoyages.
 *
 * Une seule ligne de préférences, sérialisée par [CleanStats] : pas de base de
 * données pour trois compteurs. Toute la logique de calcul est dans [CleanStats],
 * donc testée ; ici il ne reste que la lecture/écriture.
 */
object CleanHistory {

    private const val PREFS = "droidclean_history"
    private const val KEY_STATS = "stats"

    fun snapshot(context: Context): CleanStats.Snapshot =
        CleanStats.parse(prefs(context).getString(KEY_STATS, null))

    /** Enregistre un nettoyage. Seuls les octets réellement libérés comptent. */
    fun record(context: Context, outcome: Cleaner.CleanOutcome, at: Long = System.currentTimeMillis()) {
        val updated = CleanStats.record(snapshot(context), outcome.freedBytes, at)
        prefs(context).edit().putString(KEY_STATS, CleanStats.serialize(updated)).apply()
    }

    fun bytesThisMonth(context: Context, now: Long = System.currentTimeMillis()): Long =
        snapshot(context).bytesIn(CleanStats.monthKey(now))

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_STATS).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
