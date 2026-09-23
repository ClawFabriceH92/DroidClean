package com.fabrice.droidclean.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * Relève le niveau au branchement et au débranchement.
 *
 * Ce sont les deux instants qui délimitent une période de décharge : les capter
 * précisément vaut bien mieux que de les deviner au relevé périodique suivant,
 * qui peut arriver une demi-heure plus tard.
 *
 * `ACTION_BATTERY_CHANGED` ne peut pas être déclaré dans le manifeste (la
 * plateforme l'exclut explicitement) ; `ACTION_POWER_CONNECTED` et
 * `ACTION_POWER_DISCONNECTED`, eux, font partie des diffusions implicites
 * restées autorisées. Si un constructeur ne les délivrait pas, le relevé
 * périodique continue de faire le travail, simplement moins précisément.
 */
class PowerConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED &&
            intent.action != Intent.ACTION_POWER_DISCONNECTED
        ) {
            return
        }
        val appContext = context.applicationContext
        // goAsync : l'écriture du relevé est une entrée/sortie disque, elle n'a
        // rien à faire sur le thread principal.
        val pending = goAsync()
        WORKER.execute {
            try {
                BatteryTracker.sample(appContext)
            } catch (_: Exception) {
                // Un relevé manqué ne justifie pas de faire tomber le processus.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val WORKER = Executors.newSingleThreadExecutor()
    }
}
