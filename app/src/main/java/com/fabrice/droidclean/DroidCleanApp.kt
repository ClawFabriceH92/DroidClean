package com.fabrice.droidclean

import android.app.Application
import com.fabrice.droidclean.clean.CleanScheduler
import com.fabrice.droidclean.crash.CrashReporter
import com.fabrice.droidclean.ui.Notifications
import com.fabrice.droidclean.update.UpdateManager

/**
 * Point d'entrée du processus.
 *
 * Ce qui doit exister avant toute activité vit ici : le gestionnaire de
 * plantages (sinon un plantage au démarrage passe inaperçu), les canaux de
 * notification et l'alignement des tâches planifiées sur les préférences.
 */
class DroidCleanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Notifications.ensureChannels(this)
        UpdateManager.sync(this)
        CleanScheduler.sync(this)
    }
}
