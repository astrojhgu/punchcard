package com.gujunhua.attendance.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.core.DateUtils
import com.gujunhua.attendance.docx.MonthlySheetGenerator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 闹钟到点。
 *
 * 只做三件事：判断该不该打扰、该不该再提醒一次、把下一个闹钟排上。
 * 每次只维持一个待触发闹钟（见 ReminderScheduler 的约定）。
 *
 * 这个类里的每个 return 分支都写了日志。以前它是全静默的，
 * 结果「不响」的时候完全无从判断是没触发、被跳过、还是发了被系统丢了。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) {
            Log.w(TAG, "忽略非提醒广播: action=${intent.action}")
            return
        }

        val raw = intent.getStringExtra(ReminderScheduler.EXTRA_DATE)
        if (raw == null) {
            Log.w(TAG, "广播缺少日期字段，忽略")
            return
        }
        val date = runCatching { LocalDate.parse(raw) }.getOrNull()
        if (date == null) {
            Log.w(TAG, "日期无法解析: $raw")
            return
        }

        val seq = intent.getIntExtra(ReminderScheduler.EXTRA_SEQ, 1)
        val force = intent.getBooleanExtra(ReminderScheduler.EXTRA_FORCE, false)
        val settings = AppGraph.settings(context)
        val store = AppGraph.store(context)
        val today = LocalDate.now()

        Log.i(
            TAG,
            "收到提醒 date=$date seq=$seq force=$force today=$today " +
                "已填=${store.isFilled(date)} 提醒开关=${settings.remindersEnabled} " +
                "设定时间=${settings.reminderHour}:%02d".format(settings.reminderMinute),
        )

        // 月底自动出表。放在这里是因为「每天的提醒一定会响」是这个 app 唯一的
        // 定时心跳；只在用户打开 app 时判断，月末不打开就漏了。
        runCatching { MonthlySheetGenerator.maybeGenerate(context) }
            .onFailure { Log.e(TAG, "月底自动出表失败", it) }

        // 手机长时间关机/深度休眠会让闹钟延迟到第二天才送达。
        // 这时候不该补一条「今天填一下」的过期提醒，直接排下一个。
        if (today != date) {
            Log.i(TAG, "闹钟延迟送达（$date != $today），不补发过期提醒")
            ReminderScheduler.ensureScheduled(context, settings)
            return
        }

        // force 来自「立刻试一次提醒」：必须真的弹出来，
        // 否则用户在已经填过记录的时候点这个按钮会毫无反应。
        if (!force && store.isFilled(date)) {
            Log.i(TAG, "今天已填，跳过本次提醒")
            Notifications.cancelDaily(context)
            ReminderScheduler.ensureScheduled(context, settings)
            return
        }

        val pending = DateUtils.missingDays(YearMonth.from(date), date) { store.isFilled(it) }.size
        val shown = Notifications.showDailyReminder(context, date, seq, pending)
        Log.i(TAG, "提醒已发出 seq=$seq 本月未填=$pending 是否成功=$shown")

        // 手动测试只发一次，不要污染正常的重复提醒序列。
        val canRepeat = !force && settings.repeatIntervalMinutes > 0 &&
            seq < settings.maxRemindersPerDay
        if (canRepeat) {
            val next = LocalDateTime.now().plusMinutes(settings.repeatIntervalMinutes.toLong())
            if (next.toLocalDate() == date) {
                ReminderScheduler.schedule(context, date, seq + 1, next)
                return
            }
        }
        ReminderScheduler.ensureScheduled(context, settings)
    }

    private companion object {
        const val TAG = ReminderScheduler.TAG
    }
}
