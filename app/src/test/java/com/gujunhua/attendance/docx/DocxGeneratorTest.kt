package com.gujunhua.attendance.docx

import com.gujunhua.attendance.core.AttendanceStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * DocxGenerator 的正确性验证。
 *
 * 这里做两件事：
 *  1. 全量断言：fixture 里 31 天 x 上午下午，逐个格子检查写进去的符号。
 *  2. 落盘：把生成的 docx 写到 build/dut/，交给 tools/compare_docx.py
 *     和原 run.py 的产物（build/ref/）做逐单元格对比。
 *
 * 单测的工作目录是 app/ 模块目录，所以路径都相对它。
 */
class DocxGeneratorTest {

    private val fixtureFile = File("src/test/resources/fixture.json")

    /**
     * 模板里已经印好的姓名。
     * 基准对比必须用它 —— 原 run.py 不写姓名格，它依赖模板上那个字，
     * 所以拿别的名字去对比会（正确地）失败。
     */
    private val templateName = "张三"

    private fun loadFixture(): Pair<String, Map<Int, DocxGenerator.DaySymbols>> {
        assertTrue(
            "fixture 不存在，先跑 tools/make_fixture.py: ${fixtureFile.absolutePath}",
            fixtureFile.exists(),
        )
        val root = JSONObject(fixtureFile.readText(Charsets.UTF_8))
        val month = root.getString("month")
        val records = root.getJSONObject("records")
        val cells = LinkedHashMap<Int, DocxGenerator.DaySymbols>()
        // org.json 的 keys() 返回 Iterator<String>，先转序列再排序。
        for (date in records.keys().asSequence().sorted()) {
            val day = date.substring(8).toInt()
            val pair = records.getJSONArray(date)
            val am = AttendanceStatus.fromLabel(pair.getString(0))
                ?: error("fixture 里有无法识别的状态: ${pair.getString(0)}")
            val pm = AttendanceStatus.fromLabel(pair.getString(1))
                ?: error("fixture 里有无法识别的状态: ${pair.getString(1)}")
            cells[day] = DocxGenerator.DaySymbols(am.symbol, pm.symbol)
        }
        return month to cells
    }

    private fun sheetOf(month: String, cells: Map<Int, DocxGenerator.DaySymbols>) =
        DocxGenerator.Sheet(
            name = templateName,
            department = "某部门",
            year = month.substring(0, 4).toInt(),
            month = month.substring(4, 6).toInt(),
            cells = cells,
        )

    @Test
    fun headerTextMatchesOriginalScript() {
        // 原 run.py 的构造是 f'部门： {部门}{100 个空格}{yyyy}年 {mm} 月'。
        // 空格数是硬编码的、与部门名长短无关，这一点必须和原脚本一致，
        // 否则生成的表在 Word 里的标题横向位置会和历史文件对不齐。
        val expected = "部门： 某部门" + " ".repeat(100) + "2026年 07 月"
        assertEquals(expected, DocxGenerator.headerText("某部门", 2026, 7))
    }

    @Test
    fun writesEveryDayIntoTemplate() {
        val (month, cells) = loadFixture()
        val xml = DocxTestSupport.readDocumentXml(
            DocxGenerator.render(DocxTestSupport.templateBytes(), sheetOf(month, cells))
        )

        assertEquals(31, cells.size)
        for ((day, symbols) in cells) {
            assertEquals("第 $day 日上午", symbols.am, DocxTestSupport.cellText(xml, DocxGenerator.AM_ROW, day + 1))
            assertEquals("第 $day 日下午", symbols.pm, DocxTestSupport.cellText(xml, DocxGenerator.PM_ROW, day + 1))
        }
    }

    @Test
    fun headerIsWrittenIntoTemplate() {
        val sheet = DocxGenerator.Sheet(templateName, "某部门", 2026, 7, emptyMap())
        val xml = DocxTestSupport.readDocumentXml(DocxGenerator.render(DocxTestSupport.templateBytes(), sheet))
        assertTrue(
            "标题没有写进 document.xml",
            xml.contains("部门： 某部门${" ".repeat(100)}2026年 07 月"),
        )
    }

    @Test
    fun writesPersonNameIntoTemplate() {
        // 设置页改了姓名，表里必须跟着改。
        // 以前这一格是模板写死的，会出现「文件名是李四、表里却印着张三」的自相矛盾。
        val sheet = DocxGenerator.Sheet("李四", "某部门", 2026, 7, emptyMap())
        val xml = DocxTestSupport.readDocumentXml(DocxGenerator.render(DocxTestSupport.templateBytes(), sheet))
        assertEquals("李四", DocxTestSupport.cellText(xml, DocxGenerator.NAME_ROW, 0))
    }

    @Test
    fun renamingPersonKeepsBookmarksIntact() {
        // 姓名格里挂着 _GoBack 书签，所以改名字只能换 <w:t> 的文字，
        // 不能像日期格那样整体重写单元格内容 —— 那会把书签一起清掉。
        val sheet = DocxGenerator.Sheet("王五", "某部门", 2026, 7, emptyMap())
        val xml = DocxTestSupport.readDocumentXml(DocxGenerator.render(DocxTestSupport.templateBytes(), sheet))
        assertTrue("姓名格里的 bookmarkStart 被弄丢了", xml.contains("<w:bookmarkStart"))
        assertTrue("姓名格里的 bookmarkEnd 被弄丢了", xml.contains("<w:bookmarkEnd"))
        assertEquals("王五", DocxTestSupport.cellText(xml, DocxGenerator.NAME_ROW, 0))
    }

    @Test
    fun renamingPersonKeepsTheCellFont() {
        // 姓名格在模板里是宋体（和日期格的仿宋不同）。换名字不该把字体也换掉，
        // 否则表里姓名的观感会和历史文件不一致。
        val sheet = DocxGenerator.Sheet("赵六", "某部门", 2026, 7, emptyMap())
        val xml = DocxTestSupport.readDocumentXml(DocxGenerator.render(DocxTestSupport.templateBytes(), sheet))
        val at = xml.indexOf("赵六")
        assertTrue("生成的 XML 里找不到新姓名", at > 0)
        val runBefore = xml.substring(maxOf(0, at - 500), at)
        assertTrue(
            "姓名 run 的字体被改动了，本该保留宋体",
            runBefore.contains("w:ascii=\"宋体\""),
        )
    }

    @Test
    fun daysWithoutRecordKeepTemplateCellsUntouched() {
        // 只填 1 日时，其余格子必须保持模板原样（空段落），不能被写成别的日期。
        val sheet = DocxGenerator.Sheet(
            templateName, "某部门", 2026, 7,
            mapOf(1 to DocxGenerator.DaySymbols("√", "○")),
        )
        val xml = DocxTestSupport.readDocumentXml(DocxGenerator.render(DocxTestSupport.templateBytes(), sheet))
        assertEquals("√", DocxTestSupport.cellText(xml, DocxGenerator.AM_ROW, 2))
        assertEquals("○", DocxTestSupport.cellText(xml, DocxGenerator.PM_ROW, 2))
        for (day in 2..31) {
            assertEquals("第 $day 日上午不该有内容", "", DocxTestSupport.cellText(xml, DocxGenerator.AM_ROW, day + 1))
            assertEquals("第 $day 日下午不该有内容", "", DocxTestSupport.cellText(xml, DocxGenerator.PM_ROW, day + 1))
        }
    }

    @Test
    fun otherZipEntriesArePassedThroughByteForByte() {
        val template = DocxTestSupport.templateBytes()
        val sheet = DocxGenerator.Sheet(
            templateName, "某部门", 2026, 7,
            mapOf(1 to DocxGenerator.DaySymbols("√", "√")),
        )
        val produced = DocxGenerator.render(template, sheet)

        val before = zipEntries(template)
        val after = zipEntries(produced)
        assertEquals("zip 条目集合必须完全一致", before.keys.sorted(), after.keys.sorted())
        for ((name, bytes) in before) {
            if (name == "word/document.xml") continue
            assertTrue("$name 被意外改写", bytes.contentEquals(after[name]))
        }
    }

    @Test
    fun rendersDeterministically() {
        val (month, cells) = loadFixture()
        val a = DocxGenerator.render(DocxTestSupport.templateBytes(), sheetOf(month, cells))
        val b = DocxGenerator.render(DocxTestSupport.templateBytes(), sheetOf(month, cells))
        assertTrue("同样的输入必须产出同样的字节", a.contentEquals(b))
    }

    @Test
    fun dropsFixtureProductForBaselineComparison() {
        val (month, cells) = loadFixture()
        val outDir = File("../build/dut").apply { mkdirs() }
        val out = File(outDir, "考勤表_${month}_张三.docx")
        out.writeBytes(DocxGenerator.render(DocxTestSupport.templateBytes(), sheetOf(month, cells)))
        assertTrue("没有产出对比文件: ${out.absolutePath}", out.length() > 0)
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                out[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        return out
    }
}
