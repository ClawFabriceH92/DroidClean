package com.fabrice.droidclean.battery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Relevé périodique du niveau de batterie, planifié par [BatteryTracker]. */
class BatterySampleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!BatteryTracker.trackingEnabled(applicationContext)) return Result.success()
        withContext(Dispatchers.IO) {
            runCatching { BatteryTracker.sample(applicationContext) }
        }
        // Un relevé manqué n'est jamais une raison de réessayer : le suivant suffit.
        return Result.success()
    }
}
