package com.fabrice.droidclean.clean

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Nettoyage automatique hebdomadaire des caches.
 *
 * Volontairement limité à ce qui se régénère ([Cleaner.cleanSafeOnly]) : une tâche
 * de fond ne supprime jamais un document de l'utilisateur.
 */
object CleanScheduler {

    private const val PREFS = "droidclean_clean"
    private const val KEY_AUTO_CLEAN = "autoClean"
    private const val KEY_USE_TRASH = "useTrash"
    private const val WORK_NAME = "droidclean-weekly-auto-clean"

    fun autoCleanEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CLEAN, false)

    fun setAutoClean(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLEAN, enabled).apply()
        sync(context)
    }

    /** Les documents supprimés passent-ils par la corbeille du système ? */
    fun useTrash(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_TRASH, true)

    fun setUseTrash(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_TRASH, enabled).apply()
    }

    /** Aligne la planification sur la préférence. Idempotent. */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        if (!autoCleanEnabled(appContext)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoCleanWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            )
            .build()
        // UPDATE et non KEEP : avec KEEP, toute évolution future des contraintes
        // ne serait jamais appliquée aux installations existantes.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
