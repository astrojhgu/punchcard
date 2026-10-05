package com.gujunhua.attendance.docx

import com.gujunhua.attendance.core.AttendanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号表 vs 模板图例。
 *
 * 模板第 13/14 行是单位给的那张图例（出勤 公差 补休 年休假 … / √ ○ □ △ …）。
 * 这张表才是「考勤表上该写什么符号」的最终依据，不是我们代码里的 enum。
 * 这个测试把两者钉在一起：谁改了 enum 的符号而没同步图例（或反之），这里就红。
 *
 * 这也是唯一一处「出差/公差」这种同义叫法的兜底检查——图例写「公差」，
 * 我们的状态叫「出差」，靠别名表对上，符号必须仍是 ○。
 */
class TemplateLegendTest {

    @Test
    fun legendRowLayoutIsWhatWeThinkItIs() {
        val xml = DocxTestSupport.readDocumentXml(DocxTestSupport.templateBytes())
        val cells = DocxTestSupport.rowCells(xml, LEGEND_LABEL_ROW)
        assertEquals("图例首格应从逻辑列 0 开始", 0, cells.first().column)
        assertEquals("图例首格是标题", "考勤符号", cells.first().text)
        assertEquals("图例每个条目跨 2 列", 2, cells[1].span)
    }

    @Test
    fun everyLegendEntryMatchesOurSymbolTable() {
        val xml = DocxTestSupport.readDocumentXml(DocxTestSupport.templateBytes())
        val labels = legendItems(xml, LEGEND_LABEL_ROW)
        val symbols = legendItems(xml, LEGEND_SYMBOL_ROW)

        assertEquals("模板图例的标签数与符号数必须一致", labels.size, symbols.size)
        assertTrue("图例条目数量看起来不对：${labels.size}", labels.size >= 16)

        for ((label, symbol) in labels.zip(symbols)) {
            val status = AttendanceStatus.fromLabel(label)
                ?: error("模板图例里的「$label」在我们的状态表里找不到（缺枚举或缺别名）")
            assertEquals("「$label」的符号与模板图例不一致", symbol, status.symbol)
        }
    }

    /** 图例里出现的每个符号都必须来自状态表，不能有代码里不认识的符号。 */
    @Test
    fun legendSymbolsAllComeFromStatusTable() {
        val xml = DocxTestSupport.readDocumentXml(DocxTestSupport.templateBytes())
        val known = AttendanceStatus.entries.map { it.symbol }.filter { it.isNotEmpty() }.toSet()
        for (symbol in legendItems(xml, LEGEND_SYMBOL_ROW)) {
            assertTrue("模板图例里的符号「$symbol」在状态表的 symbol 里不存在", symbol in known)
        }
    }

    private fun legendItems(xml: String, row: Int): List<String> =
        DocxTestSupport.rowCells(xml, row)
            .filter { it.column >= FIRST_LEGEND_COLUMN }
            .map { it.text }
            .filter { it.isNotEmpty() }

    private companion object {
        /** 模板里图例的两行：上排是名称，下排是符号。 */
        const val LEGEND_LABEL_ROW = 13
        const val LEGEND_SYMBOL_ROW = 14

        /** 逻辑列 0 是「考勤符号」标题格，条目从第 2 列开始。 */
        const val FIRST_LEGEND_COLUMN = 2
    }
}
