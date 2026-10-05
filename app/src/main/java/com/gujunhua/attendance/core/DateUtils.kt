package com.gujunhua.attendance.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日期规则。用 java.time（minSdk 26 起可用），不自己算闰年和月份天数。
 */
object DateUtils {

    fun daysInMonth(month: YearMonth): Int = month.lengthOfMonth()

    /** 周六周日。法定节假日不在这里判——那是需要国务院发文的动态数据，由用户在日历上手动标记。 */
    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /**
     * 是否是本月最后一天（月底出表的触发条件）。
     * 不用「== 31」这种写法：2 月和大小月会直接把逻辑写错。
     */
    fun isMonthEnd(date: LocalDate): Boolean =
        date.dayOfMonth == YearMonth.from(date).lengthOfMonth()

    /**
     * 到 [today] 为止、本月里「该填但没填」的日子。
     *
     * 只认工作日：周末自动留空，不打扰也不催。今天算在内，因为当天本来就要填。
     * 未来日期不算缺填。
     */
    fun missingDays(month: YearMonth, today: LocalDate, isFilled: (LocalDate) -> Boolean): List<LocalDate> {
        if (month.isAfter(YearMonth.from(today))) return emptyList()
        val last = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
        return (1..last)
            .map { month.atDay(it) }
            .filterNot { isWeekend(it) }
            .filterNot { isFilled(it) }
    }
}
