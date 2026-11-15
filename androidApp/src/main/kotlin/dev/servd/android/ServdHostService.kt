package dev.servd.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that keeps the servd hub running while the app is backgrounded or the screen
 * is off - a server is useless if Android kills it the moment the user leaves the screen. Starting
 * and stopping the actual [ServdHost] happens here; the control screen only sends intents.
 */
class ServdHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHost()
                return START_NOT_STICKY
            }
            else -> startHost()
        }
        return START_STICKY
    }

    private fun startHost() {
        // Must call startForeground promptly after startForegroundService, so post a "starting"
        // notification first, then bring the server up on a background thread (it binds sockets).
        startForegroundCompat(NOTIFICATION_ID, buildNotification("Starting servd..."))
        thread(name = "servd-host-start") {
            runCatching { ServdHost.start(this) }
                .onSuccess {
                    val url = ServdHost.info?.url ?: "https://127.0.0.1:${ServdHost.PORT}"
                    updateNotification("Serving $url")
                    broadcastState()
                }
                .onFailure {
                    Log.e("servd", "host start failed", it)
                    updateNotification("servd failed to start")
                    broadcastState()
                }
        }
    }

    private fun stopHost() {
        ServdHost.stop()
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // If the system tears us down, take the server with us rather than leaking a bound socket.
        ServdHost.stop()
        super.onDestroy()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServdHostService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("servd")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "servd hub", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps the servd hub running" },
            )
        }
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val ACTION_START = "dev.servd.android.action.START"
        const val ACTION_STOP = "dev.servd.android.action.STOP"
        const val ACTION_STATE_CHANGED = "dev.servd.android.action.STATE_CHANGED"
        private const val CHANNEL_ID = "servd-hub"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ServdHostService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServdHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
