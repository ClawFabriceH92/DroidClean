package com.fabrice.droidclean.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.fabrice.droidclean.R
import com.fabrice.droidclean.ui.Notifications

/** Notifications propres aux mises à jour, posées sur le canal dédié. */
internal object UpdateNotifier {

    /** MAJ disponible, mais l'installation d'apps inconnues n'est pas autorisée. */
    fun notifyPermissionNeeded(context: Context, info: UpdateInfo) {
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Notifications.show(
            context = context,
            id = Notifications.ID_UPDATE_PERMISSION,
            channelId = Notifications.CHANNEL_UPDATES,
            title = context.getString(R.string.update_notif_permission_title, info.versionName),
            text = context.getString(R.string.update_notif_permission_text),
            contentIntent = settings,
        )
    }

    /**
     * APK téléchargé : impossible de lancer l'installeur depuis un receiver en
     * arrière-plan (Android 10+), donc l'utilisateur le déclenche depuis la notification.
     */
    fun notifyReadyToInstall(context: Context, installIntent: Intent) {
        Notifications.show(
            context = context,
            id = Notifications.ID_UPDATE_READY,
            channelId = Notifications.CHANNEL_UPDATES,
            title = context.getString(R.string.update_notif_ready_title),
            text = context.getString(R.string.update_notif_ready_text),
            contentIntent = installIntent,
        )
    }
}
