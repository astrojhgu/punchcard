package com.gujunhua.attendance.data

import android.content.Context
import org.json.JSONObject

/** 设置的持久化。用 SharedPreferences 而不是塞进 records 文件：两者生命周期不同。 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = prefs.getString(KEY_JSON, null) ?: return AppSettings()
        return runCatching { AppSettings.fromJson(JSONObject(raw)) }.getOrElse { AppSettings() }
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY_JSON, settings.toJson().toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "attendance-settings"
        const val KEY_JSON = "settings"
    }
}
