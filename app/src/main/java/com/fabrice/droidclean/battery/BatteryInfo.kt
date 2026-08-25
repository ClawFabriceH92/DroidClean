package com.fabrice.droidclean.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * Lecture de l'état de la batterie, y compris les informations d'usure que
 * l'ancienne carte n'exposait pas : cycles de charge (Android 14+), capacité
 * réelle en mAh et temps de charge restant estimé par le système.
 *
 * Toute valeur inconnue vaut [UNKNOWN] plutôt que zéro : « 0 cycle » et
 * « information indisponible » ne veulent pas dire la même chose.
 */
object BatteryInfo {

    const val UNKNOWN = -1

    data class Snapshot(
        val available: Boolean,
        val percent: Int,
        val temperatureC: Float,
        val health: Int,
        val status: Int,
        val plugged: Int,
        val voltageMv: Int,
        val technology: String?,
        val cycleCount: Int,
        val chargeCounterUah: Int,
        val chargeTimeRemainingMs: Long,
    ) {
        val isCharging: Boolean
            get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        /** Capacité actuelle en mAh, ou null si le matériel ne la publie pas. */
        val capacityMah: Int?
            get() = chargeCounterUah.takeIf { it > 0 }?.let { it / 1000 }

        companion object {
            val UNAVAILABLE = Snapshot(
                available = false,
                percent = 0,
                temperatureC = 0f,
                health = BatteryManager.BATTERY_HEALTH_UNKNOWN,
                status = BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = 0,
                voltageMv = UNKNOWN,
                technology = null,
                cycleCount = UNKNOWN,
                chargeCounterUah = UNKNOWN,
                chargeTimeRemainingMs = UNKNOWN.toLong(),
            )
        }
    }

    fun read(context: Context): Snapshot {
        val sticky = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return Snapshot.UNAVAILABLE

        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        return Snapshot(
            available = true,
            percent = if (level >= 0 && scale > 0) level * 100 / scale else 0,
            temperatureC = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN) / 10f,
            health = sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            voltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, UNKNOWN),
            technology = sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            cycleCount = cycleCount(sticky),
            chargeCounterUah = manager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?: UNKNOWN,
            chargeTimeRemainingMs = chargeTimeRemaining(manager),
        )
    }

    /** `EXTRA_CYCLE_COUNT` n'existe qu'à partir d'Android 14. */
    private fun cycleCount(sticky: Intent): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sticky.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, UNKNOWN)
        } else {
            UNKNOWN
        }

    /** Estimation système du temps de charge restant (Android 9+), -1 si inconnue. */
    private fun chargeTimeRemaining(manager: BatteryManager?): Long {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return UNKNOWN.toLong()
        return try {
            manager.computeChargeTimeRemaining()
        } catch (_: Exception) {
            UNKNOWN.toLong()
        }
    }
}
