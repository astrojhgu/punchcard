package com.gujunhua.attendance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DateUtilsTest {

    @Test
    fun weekendIsSaturdayAndSunday() {
        assertTrue(DateUtils.isWeekend(LocalDate.of(2026, 7, 4)))  // 周六
        assertTrue(DateUtils.isWeekend(LocalDate.of(2026, 7, 5)))  // 周日
        assertFalse(DateUtils.isWeekend(LocalDate.of(2026, 7, 3))) // 周五
        assertFalse(DateUtils.isWeekend(LocalDate.of(2026, 7, 6))) // 周一
    }

    @Test
    fun monthEndHandlesShortAndLeapMonths() {
        assertTrue(DateUtils.isMonthEnd(LocalDate.of(2026, 1, 31)))
        assertFalse(DateUtils.isMonthEnd(LocalDate.of(2026, 1, 30)))
        assertTrue(DateUtils.isMonthEnd(LocalDate.of(2026, 2, 28)))   // 平年
        assertFalse(DateUtils.isMonthEnd(LocalDate.of(2026, 2, 27)))
        assertTrue(DateUtils.isMonthEnd(LocalDate.of(2028, 2, 29)))   // 闰年
        assertTrue(DateUtils.isMonthEnd(LocalDate.of(2026, 4, 30)))
        assertFalse(DateUtils.isMonthEnd(LocalDate.of(2026, 4, 29)))
    }

    @Test
    fun missingDaysSkipsWeekendsAndFutureDates() {
        val month = YearMonth.of(2026, 7)
        val today = LocalDate.of(2026, 7, 10) // 周五
        // 什么都没填
        val missing = DateUtils.missingDays(month, today) { false }
        // 7/1~7/10 里的工作日：1,2,3,6,7,8,9,10 共 8 天
        assertEquals(8, missing.size)
        assertEquals(LocalDate.of(2026, 7, 1), missing.first())
        assertEquals(LocalDate.of(2026, 7, 10), missing.last())
        assertTrue(missing.none { DateUtils.isWeekend(it) })
        assertTrue(missing.all { !it.isAfter(today) })
    }

    @Test
    fun missingDaysDropsFilledOnes() {
        val month = YearMonth.of(2026, 7)
        val today = LocalDate.of(2026, 7, 10)
        val filled = setOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2))
        val missing = DateUtils.missingDays(month, today) { it in filled }
        assertEquals(6, missing.size)
    }

    @Test
    fun pastMonthCountsWholeMonth() {
        val month = YearMonth.of(2026, 6)
        val today = LocalDate.of(2026, 7, 10)
        val missing = DateUtils.missingDays(month, today) { false }
        // 2026 年 6 月 30 天，其中工作日 22 天
        assertEquals(22, missing.size)
        assertEquals(LocalDate.of(2026, 6, 30), missing.last())
    }

    @Test
    fun futureMonthIsNeverMissing() {
        val month = YearMonth.of(2026, 8)
        val today = LocalDate.of(2026, 7, 10)
        assertTrue(DateUtils.missingDays(month, today) { false }.isEmpty())
    }
}
