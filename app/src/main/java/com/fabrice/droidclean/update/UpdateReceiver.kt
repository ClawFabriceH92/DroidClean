package com.fabrice.droidclean.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reçoit la fin du téléchargement DownloadManager et propose l'installation
 * si c'est bien l'APK attendu (id mémorisé).
 *
 * Un receiver ne peut pas lancer d'activité en arrière-plan depuis Android 10 :
 * on n'installe directement que si l'app est au premier plan, sinon on poste une
 * notification que l'utilisateur touche pour déclencher l'installeur.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val expected = AutoUpdater.lastDownloadId(context)
        if (received == -1L || received != expected) return

        if (!AutoUpdater.isDownloadComplete(context, received)) {
            Log.w(TAG, "Téléchargement de mise à jour en échec (id=$received)")
            return
        }

        val installIntent = AutoUpdater.installIntent(context)
        if (installIntent == null) {
            Log.w(TAG, "Installation impossible (permission ou fichier manquant)")
            return
        }

        if (UpdateManager.appInForeground && AutoUpdater.installDownloaded(context)) return
        UpdateNotifier.notifyReadyToInstall(context, installIntent)
    }

    private companion object {
        const val TAG = "DroidCleanUpdate"
    }
}
