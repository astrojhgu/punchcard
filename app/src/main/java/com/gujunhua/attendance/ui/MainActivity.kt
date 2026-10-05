package com.gujunhua.attendance.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gujunhua.attendance.AppGraph
import com.gujunhua.attendance.R
import com.gujunhua.attendance.core.AttendanceStatus
import com.gujunhua.attendance.core.DateUtils
import com.gujunhua.attendance.docx.SheetExporter
import com.gujunhua.attendance.notify.Notifications
import com.gujunhua.attendance.notify.ReminderScheduler
import java.time.LocalDate
import java.time.YearMonth

/**
 * 首页：本月总览 + 未填提示 + 出表入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: com.gujunhua.attendance.data.AttendanceStore
    private val today: LocalDate = LocalDate.now()
    private var month: YearMonth = YearMonth.from(today)
    private lateinit var adapter: DayAdapter

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = AppGraph.store(this)
        Notifications.ensureChannels(this)

        adapter = DayAdapter { date -> openFill(date) }
        findViewById<RecyclerView>(R.id.calendarGrid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.prevMonth).setOnClickListener {
            month = month.minusMonths(1)
            refresh()
        }
        findViewById<Button>(R.id.nextMonth).setOnClickListener {
            month = month.plusMonths(1)
            refresh()
        }
        findViewById<Button>(R.id.generateButton).setOnClickListener { generate() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.warningText).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.legendText).text =
            AttendanceStatus.entries.joinToString("  ") { "${it.label}${it.symbol}" }

        ensureNotificationPermission()
        ReminderScheduler.ensureScheduled(this, AppGraph.settings(this))
        // 打开 app 时顺手检查一次月底出表，多一条路径就少一次「忘了生成」。
        runCatching { com.gujunhua.attendance.docx.MonthlySheetGenerator.maybeGenerate(this) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean = true

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        val settings = AppGraph.settings(this)
        findViewById<TextView>(R.id.monthTitle).text = "%d年%d月".format(month.year, month.monthValue)

        val missing = DateUtils.missingDays(month, today) { store.isFilled(it) }
        val pending = findViewById<TextView>(R.id.pendingText)
        val isCurrentMonth = month == YearMonth.from(today)
        when {
            missing.isNotEmpty() && isCurrentMonth -> {
                pending.text = "本月还有 ${missing.size} 天没填，点日历补上"
                pending.setTextColor(ContextCompat.getColor(this, R.color.day_empty))
            }
            missing.isEmpty() && isCurrentMonth -> {
                pending.text = "本月该填的都填了"
                pending.setTextColor(ContextCompat.getColor(this, R.color.day_filled))
            }
            else -> {
                pending.text = "查看历史月份"
                pending.setTextColor(ContextCompat.getColor(this, R.color.day_weekend))
            }
        }

        val warning = findViewById<TextView>(R.id.warningText)
        val warnings = buildList {
            if (AppGraph.loadError != null) add("记录文件读取异常，原文件已保留为 .corrupt")
            if (!ReminderScheduler.canScheduleExact(this@MainActivity)) add("精确闹钟未授权，提醒可能被推迟")
            if (!settings.remindersEnabled) add("每日提醒已关闭")
        }
        if (warnings.isEmpty()) {
            warning.visibility = View.GONE
        } else {
            warning.visibility = View.VISIBLE
            warning.text = warnings.joinToString("；") + "  → 点这里处理"
            warning.setTextColor(ContextCompat.getColor(this, R.color.day_empty))
        }

        adapter.submit(month, store, today)
    }

    private fun openFill(date: LocalDate) {
        startActivity(
            Intent(this, FillActivity::class.java).putExtra(FillActivity.EXTRA_DATE, date.toString())
        )
    }

    private fun generate() {
        val settings = AppGraph.settings(this)
        val file = SheetExporter.generate(this, month, settings, store)
        val summary = SheetExporter.summarize(month, store)
        val recordDays = store.recordsIn(month).size

        val text = buildString {
            append("文件：${file.name}\n")
            append("本月有记录：$recordDays 天\n\n")
            if (summary.isEmpty()) {
                append("（这个月还没有任何记录）")
            } else {
                append(summary.entries.joinToString("\n") { (status, count) ->
                    "${status.label} ${status.symbol}：$count 个半天"
                })
            }
        }

        AlertDialog.Builder(this)
            .setTitle("考勤表已生成")
            .setMessage(text)
            .setPositiveButton("分享/保存") { _, _ ->
                SheetExporter.saveToDownloads(this, file)
                startActivity(SheetExporter.shareIntent(this, file))
            }
            .setNeutralButton("存到下载目录") { _, _ ->
                val uri = SheetExporter.saveToDownloads(this, file)
                Notifications.showDocxReady(this, file.name, SheetExporter.uriFor(this, file))
                android.widget.Toast.makeText(
                    this,
                    if (uri != null) "已存到「下载」目录" else "系统版本不支持，请用分享保存",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------ 日历格子

    private class DayAdapter(
        private val onClick: (LocalDate) -> Unit,
    ) : RecyclerView.Adapter<DayAdapter.Holder>() {

        private var month: YearMonth = YearMonth.now()
        private var cells: List<LocalDate?> = emptyList()
        private var store: com.gujunhua.attendance.data.AttendanceStore? = null
        private var today: LocalDate = LocalDate.now()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val number: TextView = view.findViewById(R.id.dayNumber)
            val symbol: TextView = view.findViewById(R.id.daySymbol)
        }

        fun submit(month: YearMonth, store: com.gujunhua.attendance.data.AttendanceStore, today: LocalDate) {
            this.month = month
            this.store = store
            this.today = today
            // 周一为一周的第一天，月初前面补空格子。
            val lead = month.atDay(1).dayOfWeek.value - 1
            val list = ArrayList<LocalDate?>()
            repeat(lead) { list.add(null) }
            for (d in 1..month.lengthOfMonth()) list.add(month.atDay(d))
            while (list.size % 7 != 0) list.add(null)
            cells = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_day, parent, false))

        override fun getItemCount(): Int = cells.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val date = cells[position]
            if (date == null) {
                holder.number.text = ""
                holder.symbol.text = ""
                holder.itemView.setOnClickListener(null)
                return
            }
            holder.number.text = date.dayOfMonth.toString()

            val record = store?.get(date)
            val weekend = DateUtils.isWeekend(date)
            when {
                record != null -> {
                    val am = record.am?.symbol ?: ""
                    val pm = record.pm?.symbol ?: ""
                    holder.symbol.text = when {
                        am.isEmpty() && pm.isEmpty() -> "休"
                        am == pm -> am
                        else -> "$am$pm"
                    }
                    holder.symbol.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.day_filled)
                    )
                }
                weekend -> {
                    holder.symbol.text = "—"
                    holder.symbol.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.day_weekend)
                    )
                }
                date.isAfter(today) -> {
                    holder.symbol.text = ""
                    holder.symbol.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.day_weekend)
                    )
                }
                else -> {
                    holder.symbol.text = "?"
                    holder.symbol.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.day_empty)
                    )
                }
            }
            holder.number.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (date == today) R.color.day_filled else R.color.chip_fg,
                )
            )
            holder.itemView.setOnClickListener { onClick(date) }
        }
    }
}
