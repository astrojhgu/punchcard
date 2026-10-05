package com.gujunhua.attendance.docx

import android.content.Context
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.notify.Notifications
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月底自动出表。
 *
 * 触发点：每天的提醒广播、打开 app、开机重排——都调一次 [maybeGenerate]。
 * 这样即使月底那天用户完全没打开 app，只要提醒照常响（哪怕他没点），表也生成了。
 *
 * 用 SharedPreferences 记住「这个月已经生成过」，避免同一个月反复生成、
 * 反复弹通知。生成是幂等的（同一份数据必然产出同一份文件），但通知不是。
 */
object MonthlySheetGenerator {

    private const val PREFS = "attendance-monthly"
    private const val KEY_LAST_MONTH = "lastGeneratedMonth"

    /** 只在「今天是本月最后一天」时生成。返回生成的文件，没生成则返回 null。 */
    fun maybeGenerate(context: Context, today: LocalDate = LocalDate.now()): File? {
        val month = YearMonth.from(today)
        if (!com.gujunhua.attendance.core.DateUtils.isMonthEnd(today)) return null

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_MONTH, null) == month.toString()) return null

        val settings = AppGraph.settings(context)
        val store = AppGraph.store(context)
        val file = SheetExporter.generate(context, month, settings, store)
        prefs.edit().putString(KEY_LAST_MONTH, month.toString()).apply()
        Notifications.showDocxReady(context, file.name, SheetExporter.uriFor(context, file))
        return file
    }

    /** 供「设置」里重来一次用（比如手动删了文件想再生成一遍通知）。 */
    fun forgetLastMonth(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_MONTH).apply()
    }
}
