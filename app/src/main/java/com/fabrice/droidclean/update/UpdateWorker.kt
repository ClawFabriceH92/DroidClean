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
        // Ménage : un APK déjà installé ou périmé n'a plus rien à faire sur le disque.
        AutoUpdater.discardObsoleteApk(applicationContext)

        return when (UpdateManager.check(applicationContext, interactive = false)) {
            // Réseau ou API indisponible : WorkManager réessaiera avec backoff.
            is UpdateManager.CheckResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
