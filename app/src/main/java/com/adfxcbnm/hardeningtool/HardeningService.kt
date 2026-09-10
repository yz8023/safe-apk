package com.adfxcbnm.hardeningtool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台加固 Service：负责在后台（App 切换后台/锁屏）持续执行 APK 加固任务，
 * 避免任务与 Activity 组合生命周期绑定导致的中断。
 *
 * 启动方式：Activity 先从 [HardeningSession.pendingTask] 写入参数快照，
 * 再 startForegroundService(this)。Service 读取快照，把日志/进度/结果
 * 回写到 [HardeningSession]，UI 通过订阅 [HardeningSession.state] 刷新。
 */
class HardeningService : Service() {

    companion object {
        private const val CHANNEL_ID = "hardening_foreground"
        private const val NOTIFY_ID = 0x4818
        private const val ACTION_CANCEL = "com.adfxcbnm.hardeningtool.ACTION_CANCEL_HARDENING"

        fun start(context: Context) {
            val intent = Intent(context, HardeningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            stopHosting()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification("开始加固...", 0f))
        if (runningJob?.isActive == true) {
            return START_STICKY
        }
        val task = HardeningSession.pendingTask
        if (task == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        FloatWindow.show(this)
        HardeningSession.addLog("已进入后台加固模式，返回桌面可查看悬浮进度", LogType.INFO)

        runningJob = scope.launch {
            val result = runTask(task)
            HardeningSession.finish(result)
            updateNotification(
                if (result.success) "加固完成" else "加固失败",
                result.progress
            )
            withContext(Dispatchers.Main) {
                FloatWindow.hide()
            }
            stopSelf()
        }
        return START_STICKY
    }

    private suspend fun runTask(task: HardeningSession.PendingTask): ProcessResult {
        val addLog: (String, LogType) -> Unit = { msg, type ->
            HardeningSession.addLog(msg, type)
        }
        val detailLog: (String) -> Unit = { msg ->
            HardeningSession.addLog(msg, LogType.INFO)
        }
        val onProgress: (Float) -> Unit = { p ->
            val v = (p * 100).toInt().coerceIn(0, 100)
            HardeningSession.setProgress(p)
            updateNotification("加固中 $v%", p)
            FloatWindow.update("$v%")
        }
        return if (task.useFrostEngine) {
            processFrostShellApk(
                applicationContext, task.apkUri, task.apkName,
                task.hardening, task.protection,
                addLog, detailLog, onProgress,
                timestampedOutput = task.timestampedOutput,
                autoVerify = task.autoVerify,
                frostOptions = task.frostOptions
            )
        } else {
            processApk(
                applicationContext, task.apkUri, task.apkName,
                task.hardening, task.protection,
                addLog, detailLog, onProgress,
                timestampedOutput = task.timestampedOutput,
                autoVerify = task.autoVerify,
                signEnabled = task.signEnabled,
                signKeystorePath = task.signKeystorePath,
                signAlias = task.signAlias,
                signStorePass = task.signStorePass,
                signKeyPass = task.signKeyPass,
                frostOptions = task.frostOptions
            )
        }
    }

    override fun onDestroy() {
        runningJob?.cancel()
        FloatWindow.hide()
        super.onDestroy()
    }

    private fun stopHosting() {
        runningJob?.cancel()
        HardeningSession.setProgress(0f)
        HardeningSession.addLog("已取消后台加固", LogType.WARNING)
        FloatWindow.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Notification ----------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "加固进度", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台加固任务状态" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Float): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val appIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("APK加固")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_LOW)
            .setProgress(100, (progress * 100).toInt(), false)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFY_ID, buildNotification(text, progress)) }
    }
}