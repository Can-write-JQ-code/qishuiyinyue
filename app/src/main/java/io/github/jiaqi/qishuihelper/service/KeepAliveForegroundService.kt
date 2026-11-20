package io.github.jiaqi.qishuihelper.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.jiaqi.qishuihelper.R
import io.github.jiaqi.qishuihelper.ui.MainActivity

class KeepAliveForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                AdHelperAccessibilityService.requestStopTask("已从通知栏手动停止")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "正在自动执行看视频领畅听任务..."
                val notification = buildNotification(text)
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化任务保活通知",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示自动看广告任务实时进度与一键紧急停止入口"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KeepAliveForegroundService::class.java).apply {
            action = ACTION_STOP_TASK
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "停止并恢复音量", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qishui_task_keepalive"
        private const val NOTIFICATION_ID = 2026
        private const val ACTION_UPDATE = "io.github.jiaqi.qishuihelper.ACTION_UPDATE_FGS"
        private const val ACTION_STOP_TASK = "io.github.jiaqi.qishuihelper.ACTION_STOP_TASK"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun startOrUpdate(context: Context, statusText: String) {
            runCatching {
                val intent = Intent(context, KeepAliveForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_STATUS_TEXT, statusText)
                }
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveForegroundService::class.java))
            }
        }
    }
}
