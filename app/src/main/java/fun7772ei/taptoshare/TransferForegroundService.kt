package fun7772ei.taptoshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class TransferForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "tap_transfer_progress"
        const val NOTIF_ID = 4101
        const val ACTION_UPDATE = "fun7772ei.taptoshare.action.FGS_UPDATE"
        const val ACTION_STOP = "fun7772ei.taptoshare.action.FGS_STOP"
        const val ACTION_CANCEL_BROADCAST = "fun7772ei.taptoshare.action.CANCEL_TRANSFER"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_MAX = "max"
        private const val PROGRESS_NOTIFY_INTERVAL_MS = 500L

        @Volatile
        private var isRunning = false

        @Volatile
        private var lastProgressNotifyMs = 0L

        fun start(
            context: Context,
            title: String,
            text: String,
            progress: Int = 0,
            max: Int = 0
        ) {
            lastProgressNotifyMs = 0L
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            runCatching {
                context.startForegroundService(intent)
            }
        }

        fun updateProgress(
            context: Context,
            title: String,
            text: String,
            progress: Int,
            max: Int
        ) {
            if (!isRunning) return
            val now = SystemClock.elapsedRealtime()
            val isFinished = max > 0 && progress >= max
            if (!isFinished && now - lastProgressNotifyMs < PROGRESS_NOTIFY_INTERVAL_MS) {
                return
            }
            lastProgressNotifyMs = now
            val appCtx = context.applicationContext
            runCatching {
                ensureChannel(appCtx)
                val notification = buildNotification(appCtx, title, text, progress, max)
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, notification)
            }
        }

        fun startOrUpdate(
            context: Context,
            title: String,
            text: String,
            progress: Int = 0,
            max: Int = 0
        ) {
            if (!isRunning) {
                start(context, title, text, progress, max)
            } else {
                updateProgress(context, title, text, progress, max)
            }
        }

        fun stop(context: Context) {
            isRunning = false
            lastProgressNotifyMs = 0L
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.stopService(intent)
            }
        }

        private fun buildNotification(
            context: Context,
            title: String,
            text: String,
            progress: Int,
            max: Int
        ): Notification {
            val cancelIntent = Intent(ACTION_CANCEL_BROADCAST).setPackage(context.packageName)
            val cancelPending = PendingIntent.getBroadcast(
                context,
                0,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_transfer)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(max, progress, max <= 0)
                .addAction(0, "キャンセル", cancelPending)
                .build()
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Tap to Share 転送状態",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "大容量ファイル転送中のバックグラウンド保護通知"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                lastProgressNotifyMs = 0L
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE, null -> {
                ensureChannel(this)
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Tap to Share"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "転送準備中..."
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val max = intent?.getIntExtra(EXTRA_MAX, 0) ?: 0

                val notification = buildNotification(this, title, text, progress, max)

                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                isRunning = true
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        isRunning = false
        lastProgressNotifyMs = 0L
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        isRunning = false
        lastProgressNotifyMs = 0L
        super.onDestroy()
    }
}
