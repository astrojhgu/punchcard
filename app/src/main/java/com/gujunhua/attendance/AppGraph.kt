package com.gujunhua.attendance

import android.content.Context
import com.gujunhua.attendance.data.AttendanceStore
import com.gujunhua.attendance.data.SettingsStore

/**
 * 极简的依赖持有。app 规模小，不引入 DI 框架。
 *
 * store 必须只存在一个实例：闹钟广播、通知按钮、UI 三处都会写它，
 * 各自 new 一个就会出现「刚填的记录被另一份内存数据覆盖」这种最恶心的 bug。
 */
object AppGraph {

    @Volatile
    private var store: AttendanceStore? = null

    @Volatile
    var loadError: String? = null
        private set

    fun store(context: Context): AttendanceStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val instance = AttendanceStore(AttendanceStore.defaultFile(context.applicationContext.filesDir))
            try {
                instance.load()
                loadError = null
            } catch (e: Exception) {
                // 记录文件损坏时不静默吞掉：UI 上会提示，原文件已改名为 .corrupt 留档。
                loadError = e.message
            }
            store = instance
            return instance
        }
    }

    fun settings(context: Context) = SettingsStore(context).load()

    fun saveSettings(context: Context, settings: com.gujunhua.attendance.data.AppSettings) {
        SettingsStore(context).save(settings)
    }
}
