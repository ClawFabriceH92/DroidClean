package com.fabrice.droidclean.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fabrice.droidclean.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Mise à jour automatique depuis GitHub Releases.
 *
 * La vérification périodique est confiée à WorkManager (une fois par jour, quand
 * le réseau est disponible) : contrairement à une boucle de coroutine, elle
 * survit à la mort du processus, respecte le Doze et ne réveille pas l'appareil.
 *
 * Deux chemins distincts, parce qu'ils n'ont pas les mêmes droits :
 * - **interactif** : la vérification retourne [CheckResult.Available] et l'interface
 *   montre les notes de version avant de télécharger quoi que ce soit ;
 * - **arrière-plan** : le worker télécharge directement, sauf si l'utilisateur a
 *   explicitement ignoré cette version.
 */
object UpdateManager {

    private const val PREFS = "droidcleanupdate"
    private const val KEY_AUTO = "autoUpdate"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val WORK_NAME = "droidclean-daily-update-check"

    /**
     * Renseigné par l'activité : un démarrage d'activité en arrière-plan étant
     * bloqué depuis Android 10, on sait ainsi s'il faut installer directement
     * ou passer par une notification.
     */
    @Volatile
    var appInForeground: Boolean = false

    sealed interface CheckResult {
        data class UpToDate(val current: String) : CheckResult
        /** Une version existe ; à l'interface de la proposer. */
        data class Available(val info: UpdateInfo) : CheckResult
        data class Downloading(val info: UpdateInfo) : CheckResult
        data class PermissionNeeded(val info: UpdateInfo) : CheckResult
        data object Failed : CheckResult
    }

    fun autoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
        sync(context)
    }

    /** Version explicitement ignorée par l'utilisateur (« Plus tard »). */
    fun skippedVersion(context: Context): String? =
        prefs(context).getString(KEY_SKIPPED, null)

    fun skipVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }

    /** Aligne la planification sur la préférence. Idempotent, appelé au démarrage. */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        if (!autoUpdateEnabled(appContext)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // UPDATE et non KEEP : avec KEEP, toute évolution ultérieure de l'intervalle
        // ou des contraintes ne serait jamais appliquée aux installations existantes.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Interroge GitHub Releases.
     *
     * En mode [interactive], se contente de rapporter ce qui existe : c'est
     * l'interface qui affichera les notes de version et déclenchera, ou non, le
     * téléchargement.
     */
    suspend fun check(context: Context, interactive: Boolean): CheckResult {
        val appContext = context.applicationContext
        val current = currentVersion(appContext)
        val info = withContext(Dispatchers.IO) { UpdateChecker.latestWithApk() }
            ?: return CheckResult.Failed

        if (UpdateChecker.compareVersions(info.versionName, current) <= 0) {
            return CheckResult.UpToDate(current)
        }
        if (interactive) return CheckResult.Available(info)

        // Arrière-plan : on respecte un « Plus tard » explicite.
        if (skippedVersion(appContext) == info.versionName) return CheckResult.UpToDate(current)
        return startDownload(appContext, info)
    }

    /** Déclenche le téléchargement, ou signale ce qui manque pour le faire. */
    fun startDownload(context: Context, info: UpdateInfo): CheckResult {
        val appContext = context.applicationContext
        if (!AutoUpdater.canRequestInstalls(appContext)) {
            UpdateNotifier.notifyPermissionNeeded(appContext, info)
            return CheckResult.PermissionNeeded(info)
        }
        val started = AutoUpdater.download(
            context = appContext,
            url = info.downloadUrl,
            title = appContext.getString(R.string.app_name),
            description = appContext.getString(R.string.update_downloading_description, info.versionName),
        )
        return if (started) CheckResult.Downloading(info) else CheckResult.Failed
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
