package com.fabrice.droidclean.clean

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fabrice.droidclean.history.CleanHistory
import com.fabrice.droidclean.ui.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nettoyage hebdomadaire des caches, planifié par [CleanScheduler].
 * Ne touche jamais aux documents de l'utilisateur.
 */
class AutoCleanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!CleanScheduler.autoCleanEnabled(applicationContext)) return Result.success()

        val outcome = withContext(Dispatchers.IO) {
            runCatching { Cleaner.cleanSafeOnly(applicationContext) }.getOrNull()
        } ?: return Result.retry()

        if (outcome.freedBytes > 0) {
            CleanHistory.record(applicationContext, outcome)
            Notifications.notifyAutoCleanDone(applicationContext, outcome.freedBytes)
        }
        return Result.success()
    }
}
