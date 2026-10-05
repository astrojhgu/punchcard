package com.gujunhua.attendance.docx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 用模板生成考勤表 docx。
 *
 * 为什么不重建表格：template.docx 是一张 15 行 x 34 列、带 gridSpan/vMerge 的合并表格，
 * 用代码重新拼一张出来必然在边框、行高、字体上走样。所以这里做的是
 * 「把模板的 word/document.xml 当字符串，精确定位目标单元格，只替换单元格内容」，
 * 其余 zip 条目逐字节透传。
 *
 * 写出的单元格 XML 与原 run.py（python-docx）的输出逐字符对齐：
 *   <w:p><w:pPr><w:jc w:val="center"/></w:pPr>
 *     <w:r><w:rPr><w:rFonts w:ascii="仿宋_GB2312" w:hAnsi="仿宋_GB2312"/><w:sz w:val="24"/></w:rPr>
 *       <w:t>√</w:t></w:r></w:p>
 * 注意原格里的 spacing/textAlignment/宋体 rPr 会被丢弃——这是 python-docx
 * `cell.text = s` 的实际行为，保留它反而与既有考勤表不一致。
 */
object DocxGenerator {

    /** 模板里「上午」行和「下午」行的行号（0 基），与原 run.py 的 irow=(5,6) 对应。 */
    const val AM_ROW = 5
    const val PM_ROW = 6

    /** 1 日在逻辑网格里的列号（模板第 0 列是姓名，第 1 列是「日期/上午下午」）。 */
    private const val FIRST_DAY_COLUMN = 2

    /** 标题里部门与年月之间的填充空格数，取自原 run.py 硬编码的那一串。 */
    private const val HEADER_GAP = 100

    private const val DOCUMENT_XML = "word/document.xml"
    private const val SYMBOL_FONT = "仿宋_GB2312"
    private const val SYMBOL_SIZE_HALF_POINTS = "24" // w:sz 的单位是半磅，24 = 12pt

    /** 一天两个槽位的符号。空串表示「有记录但留空」（周末/放假）。 */
    data class DaySymbols(val am: String, val pm: String)

    data class Sheet(
        val department: String,
        val year: Int,
        val month: Int,
        /** 日 -> 符号。没有记录的日期不出现在这里，对应单元格保持模板原样。 */
        val cells: Map<Int, DaySymbols>,
    )

    fun render(template: ByteArray, sheet: Sheet): ByteArray {
        val entries = readZip(template)
        val out = entries.map { (name, data) ->
            if (name == DOCUMENT_XML) {
                name to rewriteDocument(data.toString(Charsets.UTF_8), sheet).toByteArray(Charsets.UTF_8)
            } else {
                name to data
            }
        }
        return writeZip(out)
    }

    /** 标题文本，与 run.py 生成的完全一致。 */
    fun headerText(department: String, year: Int, month: Int): String =
        "部门： $department" + " ".repeat(HEADER_GAP) + "%d年 %02d 月".format(year, month)

    // ---------------------------------------------------------------- 内部实现

    private fun rewriteDocument(xml: String, sheet: Sheet): String {
        // 不能从 indexOf('<') 开始：那里是 <?xml ...?> 声明，不是元素。
        val docStart = xml.indexOf("<w:document")
        require(docStart >= 0) { "document.xml 里找不到 w:document 根元素" }
        val doc = XmlScan.parseElement(xml, docStart)
            ?: error("document.xml 无法解析")
        val body = XmlScan.children(xml, doc.contentStart, doc.contentEnd)
            .firstOrNull { it.name == "w:body" }
            ?: error("document.xml 里找不到 w:body")

        val edits = ArrayList<Edit>()

        // 1) 标题段落：保留原 pPr，只换掉后面的 run。
        //    与原 run.py 的 document.paragraphs[2] 一致——即 body 的第 3 个顶层 w:p。
        val topChildren = XmlScan.children(xml, body.contentStart, body.contentEnd)
        val paragraphs = topChildren.filter { it.name == "w:p" }
        val header = paragraphs.getOrNull(2)
            ?: error("模板里找不到标题段落（body 的第 3 个 w:p）")
        val headerText = headerText(sheet.department, sheet.year, sheet.month)
        val headerKeepUntil = XmlScan.children(xml, header.contentStart, header.contentEnd)
            .firstOrNull { it.name == "w:pPr" }?.endExclusive
            ?: header.contentStart
        edits.add(Edit(headerKeepUntil, header.contentEnd, runXml(headerText)))

        // 2) 日期格
        val table = topChildren.firstOrNull { it.name == "w:tbl" }
            ?: error("模板里找不到表格")
        val rows = cellLayout(xml, table)

        val amRow = rows.getOrNull(AM_ROW) ?: error("模板行数不足：缺少上午行 $AM_ROW")
        val pmRow = rows.getOrNull(PM_ROW) ?: error("模板行数不足：缺少下午行 $PM_ROW")

        for ((day, symbols) in sheet.cells) {
            val column = FIRST_DAY_COLUMN + (day - 1)
            replaceCell(xml, edits, amRow, column, symbols.am, day, AM_ROW)
            replaceCell(xml, edits, pmRow, column, symbols.pm, day, PM_ROW)
        }

        // 从后往前替换，避免前面的编辑让后面的偏移失效。
        val sb = StringBuilder(xml)
        edits.sortedByDescending { it.start }.forEach { sb.replace(it.start, it.end, it.replacement) }
        return sb.toString()
    }

    private fun replaceCell(
        xml: String,
        edits: MutableList<Edit>,
        row: List<RowCell>,
        column: Int,
        symbol: String,
        day: Int,
        rowIndex: Int,
    ) {
        val cell = row.firstOrNull { column >= it.column && column < it.column + it.span }
            ?: error("模板结构变化：第 $day 日（逻辑列 $column）找不到对应单元格")
        if (cell.column != column) {
            error("模板结构变化：第 $day 日（行 $rowIndex）落在起始于列 ${cell.column} 的合并单元格内，无法单独填写")
        }
        if (cell.verticalMerge == "continue") {
            error("模板结构变化：第 $day 日（行 $rowIndex，逻辑列 $column）是垂直合并的延续格，无法单独填写")
        }
        val keepUntil = XmlScan.children(xml, cell.element.contentStart, cell.element.contentEnd)
            .firstOrNull { it.name == "w:tcPr" }?.endExclusive
            ?: cell.element.contentStart
        edits.add(Edit(keepUntil, cell.element.contentEnd, cellContentXml(symbol)))
    }

    private class RowCell(
        val column: Int,
        val span: Int,
        val verticalMerge: String?,
        val element: XmlScan.Element,
    )

    /** 把每行的 w:tc 展开成带逻辑列号的列表。 */
    private fun cellLayout(xml: String, table: XmlScan.Element): List<List<RowCell>> =
        XmlScan.children(xml, table.contentStart, table.contentEnd)
            .filter { it.name == "w:tr" }
            .map { tr ->
                var column = 0
                XmlScan.children(xml, tr.contentStart, tr.contentEnd)
                    .filter { it.name == "w:tc" }
                    .map { tc ->
                        val props = XmlScan.children(xml, tc.contentStart, tc.contentEnd)
                            .firstOrNull { it.name == "w:tcPr" }
                        val span = props?.let {
                            XmlScan.children(xml, it.contentStart, it.contentEnd)
                                .firstOrNull { c -> c.name == "w:gridSpan" }
                        }?.let { XmlScan.attribute(xml, it, "w:val")?.toIntOrNull() } ?: 1
                        val vMerge = props?.let {
                            XmlScan.children(xml, it.contentStart, it.contentEnd)
                                .firstOrNull { c -> c.name == "w:vMerge" }
                        }?.let { XmlScan.attribute(xml, it, "w:val") ?: "continue" }
                        RowCell(column, span, vMerge, tc).also { column += span }
                    }
            }

    /**
     * 空符号（周末/放假）也要写一个空 run，不能省成空段落。
     *
     * 这是 python-docx `cell.text = ''` 的实际产物：`<w:r>` 在、`<w:rPr>` 在，
     * 但连 `<w:t>` 都不生成。渲染上两者毫无差别，但保留这个形状可以让
     * 「app 生成的表」与「原来 run.py 生成的表」在 XML 层面也逐字可比，
     * tools/compare_docx.py 因此不需要为任何差异开口子。
     */
    private fun cellContentXml(symbol: String): String =
        "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>${runXml(symbol)}</w:p>"

    private fun runXml(text: String): String {
        val content = if (text.isEmpty()) "" else "<w:t>${escapeXml(text)}</w:t>"
        return "<w:r><w:rPr><w:rFonts w:ascii=\"$SYMBOL_FONT\" w:hAnsi=\"$SYMBOL_FONT\"/>" +
            "<w:sz w:val=\"$SYMBOL_SIZE_HALF_POINTS\"/></w:rPr>$content</w:r>"
    }

    private fun escapeXml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }

    private class Edit(val start: Int, val end: Int, val replacement: String)

    // ---------------------------------------------------------------- zip 读写

    private fun readZip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out.add(entry.name to zis.readBytes())
                zis.closeEntry()
            }
        }
        return out
    }

    private fun writeZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                // 固定时间戳，让「同样的输入 -> 同样的输出」成立（便于对比与回归）。
                entry.time = FIXED_ZIP_TIME
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 1980-01-01 00:00:00，dos 时间能表示的最小值。 */
    private const val FIXED_ZIP_TIME = 315532800000L
}
