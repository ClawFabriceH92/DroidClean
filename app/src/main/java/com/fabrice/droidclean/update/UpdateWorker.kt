package com.fabrice.droidclean.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Vérification quotidienne des mises à jour, planifiée par [UpdateManager]. */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!UpdateManager.autoUpdateEnabled(applicationContext)) return Result.success()
        return when (UpdateManager.check(applicationContext)) {
            // Réseau ou API indisponible : WorkManager réessaiera avec backoff.
            is UpdateManager.CheckResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
