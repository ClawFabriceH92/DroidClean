package com.fabrice.droidclean.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fabrice.droidclean.R

/**
 * Notifications de mise à jour.
 *
 * Depuis Android 13, POST_NOTIFICATIONS est une permission d'exécution : sans elle
 * `notify()` est ignoré en silence. On vérifie donc systématiquement avant d'émettre.
 */
internal object UpdateNotifier {

    const val CHANNEL_ID = "com.fabrice.droidclean.updates"

    private const val ID_PERMISSION_NEEDED = 1001
    private const val ID_READY_TO_INSTALL = 1002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.update_channel_description) }
        )
    }

    /** Les notifications sont-elles réellement émettables ? */
    fun canNotify(context: Context): Boolean {
        val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** MAJ disponible mais l'installation d'apps inconnues n'est pas encore autorisée. */
    fun notifyPermissionNeeded(context: Context, info: UpdateInfo) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notify(
            context,
            ID_PERMISSION_NEEDED,
            context.getString(R.string.update_notif_permission_title, info.versionName),
            context.getString(R.string.update_notif_permission_text),
            settingsIntent,
        )
    }

    /**
     * APK téléchargé : on ne peut pas lancer l'installeur depuis un receiver en
     * arrière-plan (Android 10+), donc l'utilisateur le déclenche depuis la notification.
     */
    fun notifyReadyToInstall(context: Context, installIntent: Intent) {
        notify(
            context,
            ID_READY_TO_INSTALL,
            context.getString(R.string.update_notif_ready_title),
            context.getString(R.string.update_notif_ready_text),
            installIntent,
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: Intent,
    ) {
        if (!canNotify(context)) return
        try {
            ensureChannel(context)
            val pi = PendingIntent.getActivity(
                context,
                id,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission retirée entre-temps : rien de mieux à faire.
        }
    }
}
