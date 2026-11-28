package io.eksoda.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Only called after a successful, user-confirmed local import. No amounts on lock screen. */
internal object LightyearNotifications {
    fun imported(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val channel = "lightyear_imports"
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channel,
                    "Ενημερώσεις επενδύσεων",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        manager.notify(
            52117,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("MyEksoda · Lightyear")
                .setContentText("Νέα δεδομένα αποθηκεύτηκαν. Δες την πορεία στις Επενδύσεις.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build(),
        )
    }
}
