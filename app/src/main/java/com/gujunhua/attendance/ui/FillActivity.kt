package com.gujunhua.attendance.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.R
import com.gujunhua.attendance.core.AttendanceStatus
import com.gujunhua.attendance.data.DayRecord
import com.gujunhua.attendance.notify.Notifications
import com.gujunhua.attendance.notify.ReminderScheduler
import java.time.LocalDate

/**
 * 填报一天。上午/下午各一个选择器，一次提交。
 * 从通知正文或首页日历进入。
 */
class FillActivity : AppCompatActivity() {

    private lateinit var date: LocalDate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fill)

        date = intent.getStringExtra(EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()

        val store = AppGraph.store(this)
        val settings = AppGraph.settings(this)

        supportActionBar?.title = "填写考勤"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.dateTitle).text =
            "%d年%d月%d日 %s".format(
                date.year, date.monthValue, date.dayOfMonth, WEEKDAYS[date.dayOfWeek.value - 1],
            )

        val amPicker = findViewById<StatusPickerView>(R.id.amPicker)
        val pmPicker = findViewById<StatusPickerView>(R.id.pmPicker)
        val existing = store[date]
        amPicker.setStatus(existing?.am)
        pmPicker.setStatus(existing?.pm)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val am = amPicker.status()
            val pm = pmPicker.status()
            if (am == null && pm == null) {
                store.remove(date)
            } else {
                store.put(date, DayRecord(am, pm))
            }
            store.save()
            // 已经填了就别再催；同时把下一个闹钟重排（今天已填 -> 明天见）。
            Notifications.cancelDaily(this)
            ReminderScheduler.ensureScheduled(this, settings)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            store.remove(date)
            store.save()
            ReminderScheduler.ensureScheduled(this, settings)
            Toast.makeText(this, "已清除这天的记录", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.holidayButton).setOnClickListener {
            // 放假 = 有记录但符号为空，这样它会从「未填」统计里消失，表里留空。
            store.put(date, DayRecord.of(AttendanceStatus.HOLIDAY, AttendanceStatus.HOLIDAY))
            store.save()
            Notifications.cancelDaily(this)
            ReminderScheduler.ensureScheduled(this, settings)
            Toast.makeText(this, "已标为放假", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_DATE = "date"
        private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
