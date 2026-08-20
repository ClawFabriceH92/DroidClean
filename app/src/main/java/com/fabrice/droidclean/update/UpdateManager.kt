package com.fabrice.droidclean.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Mise à jour automatique depuis GitHub Releases.
 *
 * La vérification périodique est confiée à WorkManager (une fois par jour, quand
 * le réseau est disponible) : contrairement à une boucle de coroutine, elle
 * survit à la mort du process, respecte le Doze et ne réveille pas l'appareil
 * toutes les 30 secondes.
 */
object UpdateManager {

    private const val PREFS = "droidcleanupdate"
    private const val KEY_AUTO = "autoUpdate"
    private const val WORK_NAME = "droidclean-daily-update-check"

    /**
     * Renseigné par l'activité : un démarrage d'activité en arrière-plan étant
     * bloqué depuis Android 10, on sait ainsi s'il faut installer directement
     * ou passer par une notification.
     */
    @Volatile
    var appInForeground: Boolean = false

    /** Résultat d'une vérification, pour retour à l'utilisateur. */
    sealed interface CheckResult {
        data class UpToDate(val current: String) : CheckResult
        data class Downloading(val version: String) : CheckResult
        data class PermissionNeeded(val version: String) : CheckResult
        data object Failed : CheckResult
    }

    fun autoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, enabled).apply()
        val appContext = context.applicationContext
        if (enabled) schedulePeriodicCheck(appContext) else cancelPeriodicCheck(appContext)
    }

    /** À appeler une fois depuis le onCreate de l'activité principale. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        UpdateNotifier.ensureChannel(appContext)
        if (autoUpdateEnabled(appContext)) {
            schedulePeriodicCheck(appContext)
        } else {
            cancelPeriodicCheck(appContext)
        }
    }

    private fun schedulePeriodicCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelPeriodicCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Vérifie GitHub Releases et déclenche le téléchargement s'il y a mieux
     * que la version installée. Suspend : appelable depuis l'UI comme depuis le worker.
     */
    suspend fun check(context: Context): CheckResult {
        val appContext = context.applicationContext
        val current = currentVersion(appContext)
        val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() }
            ?: return CheckResult.Failed

        if (UpdateChecker.compareVersions(info.versionName, current) <= 0) {
            return CheckResult.UpToDate(current)
        }
        if (!AutoUpdater.canRequestInstalls(appContext)) {
            UpdateNotifier.notifyPermissionNeeded(appContext, info)
            return CheckResult.PermissionNeeded(info.versionName)
        }
        return if (AutoUpdater.download(appContext, info.downloadUrl)) {
            CheckResult.Downloading(info.versionName)
        } else {
            CheckResult.Failed
        }
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
}
