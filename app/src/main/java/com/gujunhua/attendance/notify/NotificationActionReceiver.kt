package com.gujunhua.attendance.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.core.AttendanceStatus
import com.gujunhua.attendance.data.DayRecord
import java.time.LocalDate

/**
 * 通知上三个快捷按钮里的「出勤 / 出差」。
 * 语义是「上下午一样」，一次点击把两个槽位都填上。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUICK_FILL) return

        val raw = intent.getStringExtra(EXTRA_DATE) ?: return
        val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return
        val status = AttendanceStatus.fromCode(intent.getStringExtra(EXTRA_CODE) ?: "") ?: return

        val store = AppGraph.store(context)
        store.put(date, DayRecord.of(status, status))
        store.save()

        Notifications.cancelDaily(context)
        Notifications.showQuickResult(context, date, status)
        ReminderScheduler.ensureScheduled(context, AppGraph.settings(context))
    }

    companion object {
        const val ACTION_QUICK_FILL = "com.gujunhua.attendance.action.QUICK_FILL"
        const val EXTRA_DATE = "date"
        const val EXTRA_CODE = "code"
    }
}
