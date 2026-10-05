package com.gujunhua.attendance.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.gujunhua.attendance.R
import com.gujunhua.attendance.core.AttendanceStatus

/**
 * 一个槽位（上午或下午）的状态选择器。
 *
 * 三层的落地：第一行三个大按钮【出勤】【出差】【请假…】，
 * 点【请假…】才展开细类网格。上午和下午各用一个实例，不写两遍界面代码。
 *
 * 再点一次已选中的按钮可以取消选择——没填的日子必须是「无记录」而不是某个默认值，
 * 否则会悄悄往考勤表里写√。
 */
class StatusPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val primaryRow = LinearLayout(context)
    private val detailGrid = GridLayout(context)
    private val buttons = LinkedHashMap<AttendanceStatus, MaterialButton>()
    private var selected: AttendanceStatus? = null

    var onChanged: ((AttendanceStatus?) -> Unit)? = null

    init {
        orientation = VERTICAL

        primaryRow.orientation = HORIZONTAL
        addView(primaryRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        detailGrid.columnCount = DETAIL_COLUMNS
        detailGrid.visibility = GONE
        val gridParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        addView(detailGrid, gridParams)

        for (status in AttendanceStatus.primary) {
            primaryRow.addView(makeButton(status, compact = false))
        }
        primaryRow.addView(makeLeaveToggle())

        for (status in AttendanceStatus.leaveOptions) {
            detailGrid.addView(makeButton(status, compact = true))
        }
    }

    fun status(): AttendanceStatus? = selected

    fun setStatus(status: AttendanceStatus?) {
        selected = status
        if (status != null && status.group == AttendanceStatus.Group.DETAIL) {
            detailGrid.visibility = VISIBLE
        }
        refreshAppearance()
    }

    private fun select(status: AttendanceStatus) {
        // 再点一次同一个 = 取消选择
        selected = if (selected == status) null else status
        if (selected != null && selected!!.group == AttendanceStatus.Group.DETAIL) {
            detailGrid.visibility = VISIBLE
        }
        refreshAppearance()
        onChanged?.invoke(selected)
    }

    private fun makeButton(status: AttendanceStatus, compact: Boolean): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "${status.label} ${status.symbol}".trim()
            isAllCaps = false
            textSize = if (compact) 13f else 15f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            val pad = dp(if (compact) 8 else 14)
            setPadding(pad, dp(4), pad, dp(4))
            setOnClickListener { select(status) }
            buttons[status] = this
            if (!compact) {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            } else {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            }
        }

    /** 「请假…」不是状态，是展开开关，所以不放进 buttons 表。 */
    private fun makeLeaveToggle(): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "请假…"
            isAllCaps = false
            textSize = 15f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                detailGrid.visibility = if (detailGrid.visibility == VISIBLE) GONE else VISIBLE
            }
        }

    private fun refreshAppearance() {
        for ((status, button) in buttons) {
            val on = status == selected
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (on) R.color.chip_selected_bg else android.R.color.transparent)
            )
            button.setTextColor(
                ContextCompat.getColor(context, if (on) R.color.chip_selected_fg else R.color.chip_fg)
            )
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (on) R.color.chip_selected_bg else R.color.chip_stroke)
            )
            button.strokeWidth = dp(1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DETAIL_COLUMNS = 4
    }
}
