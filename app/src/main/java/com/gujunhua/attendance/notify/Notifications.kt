package com.gujunhua.attendance.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gujunhua.attendance.R
import com.gujunhua.attendance.core.AttendanceStatus
import java.time.LocalDate

/**
 * 每日提醒通知。
 *
 * 三个快捷按钮解决 90% 的情况（上下午同状态，一键收工），
 * 需要区分上下午或选请假细类时点正文进填报界面。
 */
object Notifications {

    const val CHANNEL_DAILY = "daily-reminder"
    const val CHANNEL_RESULT = "result"

    const val ID_DAILY = 1001
    const val ID_RESULT = 1002
    const val ID_DOCX = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                "每日考勤提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "每天定点问一次今天的出勤情况"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "记录结果",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "记录完成/生成考勤表的回执"
            }
        )
    }

    fun showDailyReminder(
        context: Context,
        date: LocalDate,
        nthTime: Int,
        pendingDays: Int,
    ): Boolean {
        ensureChannels(context)
        val weekday = WEEKDAYS[date.dayOfWeek.value - 1]
        val text = buildString {
            append("${date.monthValue}月${date.dayOfMonth}日 $weekday · 点开可分别填上午/下午")
            if (nthTime > 1) append("（第 $nthTime 次提醒）")
            if (pendingDays > 0) append("\n本月还有 $pendingDays 天没填")
        }

        val quick = listOf(
            AttendanceStatus.ATTEND,
            AttendanceStatus.TRAVEL,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("今天出勤、出差还是请假？")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(fillIntent(context, date))

        for (status in quick) {
            builder.addAction(
                R.drawable.ic_notification,
                status.label,
                actionIntent(context, date, status),
            )
        }
        builder.addAction(
            R.drawable.ic_notification,
            "请假…",
            fillIntent(context, date),
        )

        return notify(context, ID_DAILY, builder.build())
    }

    fun cancelDaily(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_DAILY)
    }

    /** 快捷按钮点完之后的回执，用低优先级通道，不打扰。 */
    fun showQuickResult(context: Context, date: LocalDate, status: AttendanceStatus) {
        ensureChannels(context)
        val label = "${status.label} ${status.symbol}".trim()
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已记录：${date.monthValue}月${date.dayOfMonth}日 全天 $label")
            .setContentText("上下午不同时，点开可以分开改")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setTimeoutAfter(8000)
            .setAutoCancel(true)
            .setContentIntent(fillIntent(context, date))
            .build()
        notify(context, ID_RESULT, n)
    }

    fun showDocxReady(context: Context, fileName: String, uri: android.net.Uri?) {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("考勤表已生成")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (uri != null) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "分享考勤表").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, 0, chooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        notify(context, ID_DOCX, builder.build())
    }

    /**
     * 发通知。返回是否成功。
     *
     * 以前这里是 `runCatching {}` 直接吞掉异常——表现就是「不响，也不报错」，
     * 完全没法判断是没触发、被跳过，还是发了被系统拒了。现在失败会打日志。
     * Android 13+ 未授权时 notify 会被静默丢弃，所以调用点仍要负责先申请权限。
     */
    private fun notify(context: Context, id: Int, notification: android.app.Notification): Boolean =
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: Exception) {
            Log.e("AttendanceReminder", "发通知失败 id=$id", e)
            false
        }

    private fun fillIntent(context: Context, date: LocalDate): PendingIntent {
        val intent = Intent(context, com.gujunhua.attendance.ui.FillActivity::class.java).apply {
            putExtra(com.gujunhua.attendance.ui.FillActivity.EXTRA_DATE, date.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            date.toEpochDay().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, date: LocalDate, status: AttendanceStatus): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_QUICK_FILL
            putExtra(NotificationActionReceiver.EXTRA_DATE, date.toString())
            putExtra(NotificationActionReceiver.EXTRA_CODE, status.code)
        }
        return PendingIntent.getBroadcast(
            context,
            date.toEpochDay().toInt() * 10 + status.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
}
