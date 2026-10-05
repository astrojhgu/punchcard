package com.gujunhua.attendance.docx

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 测试用的 docx 读取工具。
 *
 * 复用生产代码的 [XmlScan]：如果测试自己再写一套网格展开逻辑，
 * 就会出现「两套定位算法，一起错就一起通过」的情况。
 */
internal object DocxTestSupport {

    /**
     * 单测直接读项目根目录的 template.docx —— 那才是唯一真源。
     * app/src/main/assets/ 下那份是构建时同步过去的副本（见 app/build.gradle.kts
     * 的 syncTemplate 任务），单测不该依赖构建产物。
     * 单测工作目录是 app/ 模块目录，所以路径是 ../。
     */
    private val templateFile = File("../template.docx")

    fun templateBytes(): ByteArray {
        check(templateFile.exists()) { "模板不存在: ${templateFile.absolutePath}" }
        return templateFile.readBytes()
    }

    fun readDocumentXml(docx: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        error("docx 里没有 word/document.xml")
    }

    private fun tableRange(xml: String): XmlScan.Element {
        val docStart = xml.indexOf("<w:document")
        val doc = XmlScan.parseElement(xml, docStart)!!
        val body = XmlScan.children(xml, doc.contentStart, doc.contentEnd)
            .first { it.name == "w:body" }
        return XmlScan.children(xml, body.contentStart, body.contentEnd)
            .first { it.name == "w:tbl" }
    }

    private fun rows(xml: String): List<XmlScan.Element> {
        val table = tableRange(xml)
        return XmlScan.children(xml, table.contentStart, table.contentEnd)
            .filter { it.name == "w:tr" }
    }

    /** 一个逻辑单元格：起始列、跨列数、可见文本。 */
    data class Cell(val column: Int, val span: Int, val text: String)

    /** 一行里的全部单元格，按 gridSpan 展开成逻辑列。 */
    fun rowCells(xml: String, rowIndex: Int): List<Cell> {
        val row = rows(xml)[rowIndex]
        val out = ArrayList<Cell>()
        var column = 0
        for (tc in XmlScan.children(xml, row.contentStart, row.contentEnd).filter { it.name == "w:tc" }) {
            val props = XmlScan.children(xml, tc.contentStart, tc.contentEnd)
                .firstOrNull { it.name == "w:tcPr" }
            val span = props?.let {
                XmlScan.children(xml, it.contentStart, it.contentEnd)
                    .firstOrNull { c -> c.name == "w:gridSpan" }
            }?.let { XmlScan.attribute(xml, it, "w:val")?.toIntOrNull() } ?: 1
            val text = XmlScan.descendants(xml, tc, "w:t")
                .joinToString("") { xml.substring(it.contentStart, it.contentEnd) }
            out.add(Cell(column, span, text))
            column += span
        }
        return out
    }

    /** 取单个格子的可见文本（按逻辑列号，合并单元格取其所覆盖的列）。 */
    fun cellText(xml: String, rowIndex: Int, logicalColumn: Int): String =
        rowCells(xml, rowIndex)
            .firstOrNull { logicalColumn >= it.column && logicalColumn < it.column + it.span }
            ?.text
            ?: error("行 $rowIndex 列 $logicalColumn 找不到单元格")
}
