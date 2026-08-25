package com.fabrice.droidclean.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fabrice.droidclean.R
import com.fabrice.droidclean.util.Sizes

/**
 * Canaux et émission des notifications.
 *
 * Depuis Android 13, `POST_NOTIFICATIONS` est une permission d'exécution : sans
 * elle, `notify()` est ignoré **en silence**. Tout passe donc par [canNotify].
 *
 * Deux canaux séparés, pour que l'utilisateur puisse couper l'un sans l'autre :
 * les mises à jour méritent d'interrompre, un ménage hebdomadaire non.
 */
object Notifications {

    const val CHANNEL_UPDATES = "com.fabrice.droidclean.updates"
    const val CHANNEL_MAINTENANCE = "com.fabrice.droidclean.maintenance"

    const val ID_UPDATE_PERMISSION = 1001
    const val ID_UPDATE_READY = 1002
    const val ID_AUTO_CLEAN = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                context.getString(R.string.channel_updates_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_updates_description) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAINTENANCE,
                context.getString(R.string.channel_maintenance_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_maintenance_description) }
        )
    }

    /** Les notifications sont-elles réellement émettables ? */
    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        text: String,
        contentIntent: Intent,
        ongoing: Boolean = false,
    ) {
        if (!canNotify(context)) return
        try {
            ensureChannels(context)
            val pending = PendingIntent.getActivity(
                context,
                id,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission retirée entre-temps : rien de mieux à faire.
        }
    }

    /** « 340 Mo libérés » après un ménage automatique. */
    fun notifyAutoCleanDone(context: Context, freedBytes: Long) {
        show(
            context = context,
            id = ID_AUTO_CLEAN,
            channelId = CHANNEL_MAINTENANCE,
            title = context.getString(R.string.notif_auto_clean_title),
            text = context.getString(
                R.string.notif_auto_clean_text,
                Sizes.bytes(context, freedBytes),
            ),
            contentIntent = MainIntent.of(context),
        )
    }
}
