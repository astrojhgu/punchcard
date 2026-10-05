package com.gujunhua.attendance.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gujunhua.attendance.data.AppSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日提醒的排程。
 *
 * 核心约定：**任何时刻系统里只有一个待触发的闹钟**。
 * 每次触发时由 ReminderReceiver 决定下一个是「今天的重复提醒」还是「明天的第一次」，
 * 并用同一个 PendingIntent 覆盖。这样不存在"多个闹钟互相踩"或者
 * "改设置后旧闹钟还在响"的问题。
 */
object ReminderScheduler {

    const val ACTION_REMIND = "com.gujunhua.attendance.action.REMIND"
    const val EXTRA_DATE = "date"
    const val EXTRA_SEQ = "seq"

    /**
     * 「立刻试一次提醒」用。带这个标记时无视「今天已经填过了」，
     * 强制弹一条 —— 否则用户在填过记录之后点测试按钮会毫无反应，
     * 完全没法验证提醒链路到底通不通。
     */
    const val EXTRA_FORCE = "force"

    const val TAG = "AttendanceReminder"

    /** 只有一个闹钟，所以 requestCode 固定。 */
    private const val REQUEST_CODE = 100

    /**
     * 保证「下一个该响的闹钟」已经排上。
     * 在 app 启动、设置变更、记录保存、开机之后都应该调用一次。
     */
    fun ensureScheduled(context: Context, settings: AppSettings) {
        if (!settings.remindersEnabled) {
            cancel(context)
            return
        }
        val now = LocalDateTime.now()
        val todayAt = now.toLocalDate().atTime(settings.reminderHour, settings.reminderMinute)
        if (now.isBefore(todayAt)) {
            schedule(context, now.toLocalDate(), 1, todayAt)
        } else {
            val tomorrow = now.toLocalDate().plusDays(1)
            schedule(
                context, tomorrow, 1,
                tomorrow.atTime(settings.reminderHour, settings.reminderMinute),
            )
        }
    }

    /** 立即发一次提醒（测试用）。强制弹出，不受「今天已填」影响。 */
    fun remindNow(context: Context) {
        val now = LocalDateTime.now().plusSeconds(3)
        schedule(context, now.toLocalDate(), 1, now, force = true)
    }

    fun schedule(
        context: Context,
        date: LocalDate,
        seq: Int,
        at: LocalDateTime,
        force: Boolean = false,
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, date, seq, force)

        // Android 12+ 精确闹钟要单独授权；没授权时退回不精确闹钟，
        // 可能被系统推迟几十分钟，但总比完全不响强。UI 上会提示去开启。
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "排定提醒 date=$date seq=$seq at=$at force=$force exact=$exact")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, remindIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /** 下一次提醒的时刻，仅用于在界面上显示「下次提醒：明天 20:00」。 */
    fun nextTrigger(settings: AppSettings, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val todayAt = now.toLocalDate().atTime(settings.reminderHour, settings.reminderMinute)
        return if (now.isBefore(todayAt)) todayAt
        else now.toLocalDate().plusDays(1).atTime(settings.reminderHour, settings.reminderMinute)
    }

    private fun pendingIntent(
        context: Context,
        date: LocalDate,
        seq: Int,
        force: Boolean,
    ): PendingIntent {
        val intent = remindIntent(context).apply {
            putExtra(EXTRA_DATE, date.toString())
            putExtra(EXTRA_SEQ, seq)
            putExtra(EXTRA_FORCE, force)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun remindIntent(context: Context) =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
}
