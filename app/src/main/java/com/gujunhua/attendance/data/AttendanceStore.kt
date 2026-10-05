package com.gujunhua.attendance.data

import com.gujunhua.attendance.core.AttendanceStatus
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** 一天的记录。上午/下午各自可以为 null，表示那天还没填。 */
data class DayRecord(val am: AttendanceStatus?, val pm: AttendanceStatus?) {
    /**
     * 只要有一半填过就算「已填」。
     * 只填了上午的人不该在首页被反复催「这天还没填」——那是他的选择，不是遗漏。
     */
    val isFilled: Boolean get() = am != null || pm != null

    companion object {
        fun of(am: AttendanceStatus, pm: AttendanceStatus) = DayRecord(am, pm)
    }
}

/**
 * 全部考勤记录的唯一存放处：一个 JSON 文件。
 *
 * 数据量小到可笑（一年 365 条），用不上数据库；一个文件还有额外好处——
 * 导出备份就是复制这个文件，不存在「导出的和实际存的不是一份东西」。
 */
class AttendanceStore(private val file: File) {

    private val records = sortedMapOf<LocalDate, DayRecord>()

    fun load() {
        records.clear()
        if (!file.exists()) return
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return
        try {
            fromJson(JSONObject(text))
        } catch (e: Exception) {
            // 宁可把坏文件留档也不静默清空：用户的考勤数据不该因为一次解析失败就没了。
            val quarantine = File(file.parentFile, file.name + ".corrupt")
            runCatching { quarantine.delete() }
            file.renameTo(quarantine)
            throw IllegalStateException("记录文件损坏，已保留为 ${quarantine.name}", e)
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免写到一半断电留下半个 JSON。
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson().toString(2), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    operator fun get(date: LocalDate): DayRecord? = records[date]

    fun put(date: LocalDate, record: DayRecord) {
        records[date] = record
    }

    fun remove(date: LocalDate) {
        records.remove(date)
    }

    fun isFilled(date: LocalDate): Boolean = records[date]?.isFilled == true

    fun recordsIn(month: YearMonth): Map<LocalDate, DayRecord> =
        records.filterKeys { YearMonth.from(it) == month }

    fun allDates(): List<LocalDate> = records.keys.toList()

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put(FIELD_VERSION, FORMAT_VERSION)
        val obj = JSONObject()
        for ((date, record) in records) {
            val day = JSONObject()
            record.am?.let { day.put("am", it.code) }
            record.pm?.let { day.put("pm", it.code) }
            obj.put(date.toString(), day)
        }
        json.put(FIELD_RECORDS, obj)
        return json
    }

    /** 用整份备份覆盖当前数据。导入前调用方应自行确认或先备份。 */
    fun replaceAllFromJson(json: JSONObject) {
        records.clear()
        fromJson(json)
    }

    fun clear() = records.clear()

    private fun fromJson(json: JSONObject) {
        val version = json.optInt(FIELD_VERSION, FORMAT_VERSION)
        require(version <= FORMAT_VERSION) {
            "备份文件来自更新的版本（v$version），当前只认到 v$FORMAT_VERSION"
        }
        val obj = json.optJSONObject(FIELD_RECORDS) ?: return
        for (key in obj.keys()) {
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            val day = obj.optJSONObject(key) ?: continue
            val am = day.optString("am").takeIf { it.isNotEmpty() }?.let { AttendanceStatus.fromCode(it) }
            val pm = day.optString("pm").takeIf { it.isNotEmpty() }?.let { AttendanceStatus.fromCode(it) }
            records[date] = DayRecord(am, pm)
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_RECORDS = "records"

        fun defaultFile(dir: File) = File(dir, "attendance-records.json")
    }
}
