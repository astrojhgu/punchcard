package com.gujunhua.attendance.data

import com.gujunhua.attendance.core.AttendanceStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class AttendanceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String = "records.json") = AttendanceStore(File(tmp.root, name))

    @Test
    fun savesAndReloadsWithoutLosingAnything() {
        val file = File(tmp.root, "records.json")
        val date = LocalDate.of(2026, 7, 1)

        AttendanceStore(file).apply {
            put(date, DayRecord.of(AttendanceStatus.ANNUAL_LEAVE, AttendanceStatus.TRAVEL))
            put(LocalDate.of(2026, 7, 9), DayRecord(AttendanceStatus.WEEKEND, AttendanceStatus.WEEKEND))
            save()
        }

        val reloaded = AttendanceStore(file).apply { load() }
        assertEquals(AttendanceStatus.ANNUAL_LEAVE, reloaded[date]?.am)
        assertEquals(AttendanceStatus.TRAVEL, reloaded[date]?.pm)
        // 空符号的状态（周末/放假）也是有效记录，不能因为 symbol 为空就丢掉
        assertEquals(AttendanceStatus.WEEKEND, reloaded[LocalDate.of(2026, 7, 9)]?.am)
        assertEquals(2, reloaded.allDates().size)
    }

    @Test
    fun halfFilledDayCountsAsFilled() {
        val date = LocalDate.of(2026, 7, 1)
        assertTrue(DayRecord(AttendanceStatus.ATTEND, null).isFilled)
        assertTrue(DayRecord(null, AttendanceStatus.ATTEND).isFilled)
        assertFalse(DayRecord(null, null).isFilled)
    }

    @Test
    fun missingFileLoadsAsEmpty() {
        val s = store("nope.json")
        s.load()
        assertTrue(s.allDates().isEmpty())
    }

    @Test
    fun corruptFileIsQuarantinedInsteadOfSilentlyWiped() {
        val file = File(tmp.root, "records.json")
        file.writeText("{ this is not json", Charsets.UTF_8)

        val s = AttendanceStore(file)
        assertThrows(IllegalStateException::class.java) { s.load() }
        assertTrue("坏文件必须留档", File(tmp.root, "records.json.corrupt").exists())
        assertFalse(file.exists())
    }

    @Test
    fun recordsInFiltersByMonth() {
        val s = store()
        s.put(LocalDate.of(2026, 6, 30), DayRecord.of(AttendanceStatus.ATTEND, AttendanceStatus.ATTEND))
        s.put(LocalDate.of(2026, 7, 1), DayRecord.of(AttendanceStatus.ATTEND, AttendanceStatus.ATTEND))
        s.put(LocalDate.of(2026, 8, 1), DayRecord.of(AttendanceStatus.ATTEND, AttendanceStatus.ATTEND))
        assertEquals(1, s.recordsIn(YearMonth.of(2026, 7)).size)
        assertEquals(LocalDate.of(2026, 7, 1), s.recordsIn(YearMonth.of(2026, 7)).keys.first())
    }

    @Test
    fun exportImportRoundTripsThroughJson() {
        val source = store("a.json")
        source.put(LocalDate.of(2026, 7, 1), DayRecord.of(AttendanceStatus.SICK_LEAVE, AttendanceStatus.LATE))
        source.put(LocalDate.of(2026, 7, 2), DayRecord(AttendanceStatus.ATTEND, null))

        val exported = source.toJson()

        val target = store("b.json")
        target.replaceAllFromJson(exported)

        assertEquals(2, target.allDates().size)
        assertEquals(AttendanceStatus.SICK_LEAVE, target[LocalDate.of(2026, 7, 1)]?.am)
        assertEquals(AttendanceStatus.LATE, target[LocalDate.of(2026, 7, 1)]?.pm)
        assertNull(target[LocalDate.of(2026, 7, 2)]?.pm)
    }

    @Test
    fun importRejectsNewerFormatVersion() {
        val s = store()
        val future = JSONObject().put("version", AttendanceStore.FORMAT_VERSION + 1)
            .put("records", JSONObject())
        assertThrows(IllegalArgumentException::class.java) { s.replaceAllFromJson(future) }
    }

    @Test
    fun unknownStatusCodesAreSkippedNotCrashed() {
        val s = store()
        val json = JSONObject().put("version", 1).put(
            "records",
            JSONObject().put(
                "2026-07-01",
                JSONObject().put("am", "SOMETHING_FROM_THE_FUTURE").put("pm", "ATTEND"),
            ),
        )
        s.replaceAllFromJson(json)
        assertNull(s[LocalDate.of(2026, 7, 1)]?.am)
        assertEquals(AttendanceStatus.ATTEND, s[LocalDate.of(2026, 7, 1)]?.pm)
    }
}
