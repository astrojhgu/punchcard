package com.gujunhua.attendance.core

/**
 * 考勤状态的唯一真源。
 *
 * 符号、标签、分组只在这里定义一处：UI、docx 生成、导入导出全部从这里取，
 * 不允许任何地方再抄一份映射表。
 *
 * 符号与原 run.py 的 symbols 表逐条对齐，改这里就等于改整张考勤表的图例。
 */
enum class AttendanceStatus(
    /** 持久化用的稳定标识。存档里只写 code，改标签不会弄坏历史数据。 */
    val code: String,
    val label: String,
    /** docx 单元格里实际写入的符号。空串表示留空（周末/放假）。 */
    val symbol: String,
    val group: Group,
) {
    ATTEND("ATTEND", "出勤", "√", Group.PRIMARY),
    TRAVEL("TRAVEL", "出差", "○", Group.PRIMARY),

    COMP_OFF("COMP_OFF", "补休", "□", Group.DETAIL),
    ANNUAL_LEAVE("ANNUAL_LEAVE", "年休假", "△", Group.DETAIL),
    PERSONAL_LEAVE("PERSONAL_LEAVE", "事假", "S", Group.DETAIL),
    SICK_LEAVE("SICK_LEAVE", "病假", "B", Group.DETAIL),
    MARRIAGE_LEAVE("MARRIAGE_LEAVE", "婚假", "H", Group.DETAIL),
    BEREAVEMENT_LEAVE("BEREAVEMENT_LEAVE", "丧假", "SJ", Group.DETAIL),
    FAMILY_VISIT_LEAVE("FAMILY_VISIT_LEAVE", "探亲", "T", Group.DETAIL),
    MATERNITY_LEAVE("MATERNITY_LEAVE", "产假", "CJ", Group.DETAIL),
    PARENTAL_LEAVE("PARENTAL_LEAVE", "育儿假", "Y", Group.DETAIL),
    NURSING_LEAVE("NURSING_LEAVE", "护理假", "HL", Group.DETAIL),
    WORK_INJURY("WORK_INJURY", "工伤", "G", Group.DETAIL),
    LATE("LATE", "迟到", "C", Group.DETAIL),
    EARLY_LEAVE("EARLY_LEAVE", "早退", "Z", Group.DETAIL),
    ABSENT("ABSENT", "旷工", "K", Group.DETAIL),

    WEEKEND("WEEKEND", "周末", "", Group.OFF),
    HOLIDAY("HOLIDAY", "放假", "", Group.OFF),
    ;

    /**
     * UI 分组。
     * PRIMARY: 主界面直接点的两个按钮。
     * DETAIL: 点「请假」后展开的细类网格。
     * OFF: 不出现在填报流程里，由周末自动判定或日历长按标记。
     */
    enum class Group { PRIMARY, DETAIL, OFF }

    companion object {
        private val byCode = entries.associateBy { it.code }
        private val byLabel = entries.associateBy { it.label }

        /** 单位里习惯的另一种叫法，导入旧数据/旧 md 文件时用。 */
        private val aliases = mapOf(
            "到岗" to ATTEND,
            "公差" to TRAVEL,
            "年假" to ANNUAL_LEAVE,
        )

        fun fromCode(code: String): AttendanceStatus? = byCode[code]

        /** 按中文标签解析，含别名。用于导入旧 run.py 产生的数据。 */
        fun fromLabel(label: String): AttendanceStatus? =
            byLabel[label] ?: aliases[label]

        val primary: List<AttendanceStatus> get() = entries.filter { it.group == Group.PRIMARY }
        val details: List<AttendanceStatus> get() = entries.filter { it.group == Group.DETAIL }

        /** 主界面「请假」入口：所有需要展开选择的细类。 */
        val leaveOptions: List<AttendanceStatus> get() = details
    }
}
