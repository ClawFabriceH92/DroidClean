package com.fabrice.droidclean.battery

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Mesure de l'autonomie réelle.
 *
 * Android n'expose que le niveau instantané : ni l'autonomie d'une charge, ni le
 * temps restant, ni la fréquence des recharges. Ces trois chiffres se déduisent
 * d'un historique que l'application doit constituer elle-même, d'où ce relevé
 * périodique — une tâche toutes les 30 minutes, complétée par un relevé à chaque
 * branchement, débranchement et ouverture de l'app.
 *
 * Tout reste sur l'appareil, et le suivi est désactivable.
 */
object BatteryTracker {

    private const val PREFS = "droidclean_battery"
    private const val KEY_ENABLED = "trackBattery"
    private const val WORK_NAME = "droidclean-battery-sampler"
    private const val INTERVAL_MINUTES = 30L

    fun trackingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setTracking(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) BatteryLog.clear(context)
        sync(context)
    }

    /** Aligne la planification sur la préférence. Idempotent. */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        if (!trackingEnabled(appContext)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BatterySampleWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).build()
        // Aucune contrainte : un relevé doit avoir lieu même hors réseau et hors
        // charge — c'est précisément la décharge qu'on cherche à observer.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Relève le niveau courant et l'enregistre. À appeler hors du thread principal.
     * Retourne null si le suivi est coupé ou la batterie illisible.
     */
    fun sample(context: Context): BatterySample? {
        val appContext = context.applicationContext
        if (!trackingEnabled(appContext)) return null
        val snapshot = BatteryInfo.read(appContext)
        if (!snapshot.available) return null

        val sample = BatterySample(
            at = System.currentTimeMillis(),
            percent = snapshot.percent,
            // « Branché » plutôt que « en charge » : c'est le branchement qui
            // délimite une période de décharge, et un appareil branché peut très
            // bien être à l'arrêt de charge (batterie pleine, protection thermique).
            charging = snapshot.plugged != 0,
            realtimeMs = SystemClock.elapsedRealtime(),
        )
        BatteryLog.record(appContext, sample)
        return sample
    }

    /** Estimations affichables. À appeler hors du thread principal (lecture disque). */
    fun forecast(context: Context, snapshot: BatteryInfo.Snapshot): BatteryStats.Forecast =
        BatteryStats.forecast(
            samples = BatteryLog.samples(context.applicationContext),
            now = System.currentTimeMillis(),
            currentPercent = snapshot.percent,
            charging = snapshot.plugged != 0,
            platformTimeToFullMs = snapshot.chargeTimeRemainingMs,
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
