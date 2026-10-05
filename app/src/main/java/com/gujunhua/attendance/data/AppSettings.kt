package com.gujunhua.attendance.data

import org.json.JSONObject

/**
 * 用户可配置项。
 *
 * 默认值是占位用的通用信息（张三 / 某部门）——考勤表里不该带着真实姓名出厂。
 * 首次安装后在设置页改成自己的，之后一直存在手机上。
 */
data class AppSettings(
    val name: String = DEFAULT_NAME,
    val department: String = DEFAULT_DEPARTMENT,
    val remindersEnabled: Boolean = true,
    val reminderHour: Int = DEFAULT_REMINDER_HOUR,
    val reminderMinute: Int = DEFAULT_REMINDER_MINUTE,
    /** 当天没填时的重复提醒间隔（分钟）。0 表示不重复。 */
    val repeatIntervalMinutes: Int = DEFAULT_REPEAT_INTERVAL_MINUTES,
    /** 单日最多提醒几次（含第一次）。 */
    val maxRemindersPerDay: Int = DEFAULT_MAX_REMINDERS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("department", department)
        put("remindersEnabled", remindersEnabled)
        put("reminderHour", reminderHour)
        put("reminderMinute", reminderMinute)
        put("repeatIntervalMinutes", repeatIntervalMinutes)
        put("maxRemindersPerDay", maxRemindersPerDay)
    }

    companion object {
        const val DEFAULT_NAME = "张三"
        const val DEFAULT_DEPARTMENT = "某部门"
        const val DEFAULT_REMINDER_HOUR = 20
        const val DEFAULT_REMINDER_MINUTE = 0
        const val DEFAULT_REPEAT_INTERVAL_MINUTES = 120
        const val DEFAULT_MAX_REMINDERS = 3

        fun fromJson(json: JSONObject): AppSettings = AppSettings(
            name = json.optString("name", DEFAULT_NAME),
            department = json.optString("department", DEFAULT_DEPARTMENT),
            remindersEnabled = json.optBoolean("remindersEnabled", true),
            reminderHour = json.optInt("reminderHour", DEFAULT_REMINDER_HOUR),
            reminderMinute = json.optInt("reminderMinute", DEFAULT_REMINDER_MINUTE),
            repeatIntervalMinutes = json.optInt("repeatIntervalMinutes", DEFAULT_REPEAT_INTERVAL_MINUTES),
            maxRemindersPerDay = json.optInt("maxRemindersPerDay", DEFAULT_MAX_REMINDERS),
        )
    }
}
