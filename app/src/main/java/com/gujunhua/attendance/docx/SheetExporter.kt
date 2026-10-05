package com.gujunhua.attendance.docx

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.gujunhua.attendance.core.AttendanceStatus
import com.gujunhua.attendance.data.AppSettings
import com.gujunhua.attendance.data.AttendanceStore
import java.io.File
import java.time.YearMonth

/**
 * 把内存里的记录变成一张考勤表文件。
 *
 * 文件名与产物格式沿用原来的 run.py：考勤表_YYYYMM_姓名.docx，
 * 这样历史文件和现在生成的能放在一起不打架。
 */
object SheetExporter {

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun fileName(month: YearMonth, name: String) =
        "考勤表_%04d%02d_%s.docx".format(month.year, month.monthValue, name)

    /**
     * 组装要填的内容。只写真正有记录的日期：
     * 没填的日子保持模板原样，和「周末留空」在视觉上没有区别。
     * 只填了半天时，另一半按空符号写入（与原 run.py 只给一个 token 的行为一致）。
     */
    fun buildSheet(
        month: YearMonth,
        settings: AppSettings,
        store: AttendanceStore,
    ): DocxGenerator.Sheet {
        val cells = LinkedHashMap<Int, DocxGenerator.DaySymbols>()
        for ((date, record) in store.recordsIn(month)) {
            cells[date.dayOfMonth] = DocxGenerator.DaySymbols(
                am = record.am?.symbol ?: "",
                pm = record.pm?.symbol ?: "",
            )
        }
        return DocxGenerator.Sheet(
            name = settings.name,
            department = settings.department,
            year = month.year,
            month = month.monthValue,
            cells = cells,
        )
    }

    fun render(context: Context, month: YearMonth, settings: AppSettings, store: AttendanceStore): ByteArray {
        val template = context.assets.open("template.docx").use { it.readBytes() }
        return DocxGenerator.render(template, buildSheet(month, settings, store))
    }

    /** 生成到 app 私有目录，返回文件。分享走 FileProvider。 */
    fun generate(context: Context, month: YearMonth, settings: AppSettings, store: AttendanceStore): File {
        val dir = File(context.filesDir, "sheets").apply { mkdirs() }
        val file = File(dir, fileName(month, settings.name))
        file.writeBytes(render(context, month, settings, store))
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_DOCX
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享 ${file.name}")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 另存一份到系统「下载」目录，方便不进 app 也能找到。
     * Android 10 以下没有 MediaStore.Downloads，那种情况直接返回 null（分享路径仍然可用）。
     */
    fun saveToDownloads(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_DOCX)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** 本月实际用到的状态去重列表，用于生成后给一句人话摘要。 */
    fun summarize(month: YearMonth, store: AttendanceStore): Map<AttendanceStatus, Int> {
        val counter = LinkedHashMap<AttendanceStatus, Int>()
        for ((_, record) in store.recordsIn(month)) {
            for (status in listOfNotNull(record.am, record.pm)) {
                counter[status] = (counter[status] ?: 0) + 1
            }
        }
        return counter
    }
}
