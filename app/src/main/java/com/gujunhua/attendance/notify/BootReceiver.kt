package com.gujunhua.attendance.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gujunhua.attendance.AppGraph

/**
 * 重启或 app 更新之后把闹钟重新排上。
 *
 * 国产 ROM 杀进程很随意，但 AlarmManager 的闹钟由系统持有，重启才会清空，
 * 所以这个接收器是「会不会某天彻底不提醒」的关键一环。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Notifications.ensureChannels(context)
                ReminderScheduler.ensureScheduled(context, AppGraph.settings(context))
                // 如果在关机期间跨过了月末，开机后补一次出表。
                runCatching {
                    com.gujunhua.attendance.docx.MonthlySheetGenerator.maybeGenerate(context)
                }
            }
        }
    }
}
