package com.gujunhua.attendance.ui

import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.R
import com.gujunhua.attendance.data.AppSettings
import com.gujunhua.attendance.notify.ReminderScheduler
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * 设置页。除了改参数，还承担「告诉用户小米需要手动开哪三项」的职责——
 * 国产 ROM 上漏提醒十有八九不是 app 的 bug，而是自启动/省电策略没放行。
 */
class SettingsActivity : AppCompatActivity() {

    private var hour = AppSettings.DEFAULT_REMINDER_HOUR
    private var minute = AppSettings.DEFAULT_REMINDER_MINUTE

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching { writeBackup(uri) }
                .onSuccess { toast("备份已导出") }
                .onFailure { toast("导出失败：${it.message}") }
        }

    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            confirmImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "设置"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val settings = AppGraph.settings(this)
        hour = settings.reminderHour
        minute = settings.reminderMinute

        findViewById<EditText>(R.id.nameInput).setText(settings.name)
        findViewById<EditText>(R.id.departmentInput).setText(settings.department)
        findViewById<Switch>(R.id.reminderSwitch).isChecked = settings.remindersEnabled
        findViewById<EditText>(R.id.repeatIntervalInput).setText(settings.repeatIntervalMinutes.toString())
        findViewById<EditText>(R.id.maxRemindersInput).setText(settings.maxRemindersPerDay.toString())
        updateTimeButton()
        updateNextTrigger(settings)

        findViewById<Button>(R.id.timeButton).setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h
                minute = m
                updateTimeButton()
            }, hour, minute, true).show()
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }

        findViewById<Button>(R.id.testReminderButton).setOnClickListener {
            ReminderScheduler.remindNow(this)
            toast("3 秒后会弹一条提醒")
        }

        findViewById<Button>(R.id.exactAlarmButton).setOnClickListener { openExactAlarmSettings() }
        findViewById<Button>(R.id.autoStartButton).setOnClickListener { openAutoStartSettings() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { openBatterySettings() }

        findViewById<Button>(R.id.exportButton).setOnClickListener {
            createBackup.launch("考勤备份_${LocalDateTime.now().toLocalDate()}.json")
        }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            openBackup.launch(arrayOf("application/json", "*/*"))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateTimeButton() {
        findViewById<Button>(R.id.timeButton).text =
            "提醒时间：%02d:%02d".format(hour, minute)
    }

    private fun updateNextTrigger(settings: AppSettings) {
        val next = ReminderScheduler.nextTrigger(settings.copy(reminderHour = hour, reminderMinute = minute))
        findViewById<TextView>(R.id.nextTriggerText).text =
            "下次提醒：${next.monthValue}月${next.dayOfMonth}日 %02d:%02d".format(next.hour, next.minute)
    }

    private fun currentSettings(): AppSettings {
        val base = AppGraph.settings(this)
        return base.copy(
            name = findViewById<EditText>(R.id.nameInput).text.toString().trim().ifEmpty { AppSettings.DEFAULT_NAME },
            department = findViewById<EditText>(R.id.departmentInput).text.toString().trim()
                .ifEmpty { AppSettings.DEFAULT_DEPARTMENT },
            remindersEnabled = findViewById<Switch>(R.id.reminderSwitch).isChecked,
            reminderHour = hour,
            reminderMinute = minute,
            repeatIntervalMinutes = findViewById<EditText>(R.id.repeatIntervalInput).text.toString()
                .toIntOrNull()?.coerceIn(0, 720) ?: AppSettings.DEFAULT_REPEAT_INTERVAL_MINUTES,
            maxRemindersPerDay = findViewById<EditText>(R.id.maxRemindersInput).text.toString()
                .toIntOrNull()?.coerceIn(1, 10) ?: AppSettings.DEFAULT_MAX_REMINDERS,
        )
    }

    private fun save() {
        val settings = currentSettings()
        AppGraph.saveSettings(this, settings)
        ReminderScheduler.ensureScheduled(this, settings)
        toast("已保存。下次提醒：${ReminderScheduler.nextTrigger(settings).let {
            "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute)
        }}")
        updateNextTrigger(settings)
    }

    // ---------------------------------------------------------------- 备份

    private fun writeBackup(uri: Uri) {
        val settings = currentSettings()
        val store = AppGraph.store(this)
        val json = JSONObject().apply {
            put("version", 1)
            put("exportedAt", LocalDateTime.now().toString())
            put("settings", settings.toJson())
            put("records", store.toJson().getJSONObject("records"))
        }
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选文件")
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("导入备份")
            .setMessage("导入会用备份内容覆盖手机上现在的全部记录，且无法撤销。继续？")
            .setPositiveButton("覆盖导入") { _, _ -> doImport(uri) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doImport(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("读不到文件")
            val json = JSONObject(text)
            val store = AppGraph.store(this)
            store.replaceAllFromJson(JSONObject().put("version", json.optInt("version", 1))
                .put("records", json.optJSONObject("records") ?: JSONObject()))
            store.save()
            json.optJSONObject("settings")?.let {
                val imported = AppSettings.fromJson(it)
                AppGraph.saveSettings(this, imported)
                ReminderScheduler.ensureScheduled(this, imported)
            }
            store.allDates().size
        }.onSuccess { count ->
            toast("已导入 $count 天的记录")
            recreate()
        }.onFailure { toast("导入失败：${it.message}") }
    }

    // ------------------------------------------------------- 系统设置跳转

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("这个 Android 版本不需要单独授权")
            return
        }
        startActivitySafely(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
        )
    }

    private fun openAutoStartSettings() {
        // 小米的自启动管理页没有公开 API，这是社区通用的组件名；打不开就退回应用详情页。
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in candidates) {
            if (startActivitySafely(intent)) return
        }
        toast("请到「设置 → 应用 → 权限管理」里手动打开自启动")
    }

    private fun openBatterySettings() {
        val ok = startActivitySafely(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
        if (!ok) {
            startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startActivitySafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
