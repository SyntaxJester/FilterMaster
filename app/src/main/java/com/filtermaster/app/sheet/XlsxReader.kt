package com.filtermaster.app.sheet

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * XLSX（Excel 2007+）解析器。
 *
 * xlsx 本质是 ZIP：
 *   xl/workbook.xml           工作表清单
 *   xl/sharedStrings.xml      共享字符串
 *   xl/worksheets/sheetN.xml  单元格数据
 *
 * 只取文本值，不处理样式与公式求值（公式取缓存的 <v>）。
 */
internal object XlsxReader {

    fun isXlsx(file: File): Boolean {
        if (file.length() < 4) return false
        FileInputStream(file).use { f ->
            val h = ByteArray(4)
            if (f.read(h) < 4) return false
            // PK\x03\x04
            return h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() &&
                    h[2] == 0x03.toByte() && h[3] == 0x04.toByte()
        }
    }

    fun parse(file: File): List<BiffParser.Sheet> {
        var sharedXml: ByteArray? = null
        var workbookXml: ByteArray? = null
        val sheetXmls = sortedMapOf<String, ByteArray>()

        ZipInputStream(FileInputStream(file)).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedXml = zis.readBytes()
                    name == "xl/workbook.xml" -> workbookXml = zis.readBytes()
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") ->
                        sheetXmls[name] = zis.readBytes()
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        if (sheetXmls.isEmpty()) throw IllegalArgumentException("xlsx 中没有找到工作表")

        val shared = sharedXml?.let { readSharedStrings(it) } ?: emptyList()
        val names = workbookXml?.let { readSheetNames(it) } ?: emptyList()

        return sheetXmls.entries.mapIndexed { index, entry ->
            val name = names.getOrNull(index) ?: "Sheet${index + 1}"
            BiffParser.Sheet(name, readSheet(entry.value, shared))
        }
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            .newPullParser()
            .apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }

    private fun readSheetNames(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val p = newParser(bytes)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                out.add(p.getAttributeValue(null, "name") ?: "Sheet${out.size + 1}")
            }
            p.next()
        }
        return out
    }

    /** sharedStrings：每个 <si> 可能含多个 <t>（富文本分段） */
    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val p = newParser(bytes)
        var sb: StringBuilder? = null
        var inT = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { out.add(sb?.toString().orEmpty()); sb = null }
                }
            }
            p.next()
        }
        return out
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val cells = HashMap<Long, String>()
        var maxRow = -1
        var maxCol = -1

        val p = newParser(bytes)
        var row = -1
        var col = -1
        var type: String? = null
        var buf: StringBuilder? = null
        var inValue = false
        var inInlineStr = false

        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val r = p.getAttributeValue(null, "r")?.toIntOrNull()
                        row = if (r != null) r - 1 else row + 1
                        col = -1
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colOfRef(ref) else col + 1
                        if (ref != null) rowOfRef(ref)?.let { row = it }
                        type = p.getAttributeValue(null, "t")
                        buf = StringBuilder()
                    }
                    "v" -> inValue = true
                    "t" -> if (type == "inlineStr" || type == "str") inInlineStr = true
                }

                XmlPullParser.TEXT -> if (inValue || inInlineStr) buf?.append(p.text)

                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inInlineStr = false
                    "c" -> {
                        val rawVal = buf?.toString().orEmpty()
                        val text = when (type) {
                            "s" -> rawVal.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (rawVal == "1") "TRUE" else "FALSE"
                            "e" -> ""
                            null, "n" -> formatNumeric(rawVal)
                            else -> rawVal   // inlineStr / str
                        }
                        if (text.isNotEmpty() && row >= 0 && col >= 0) {
                            cells[(row.toLong() shl 20) or col.toLong()] = text
                            if (row > maxRow) maxRow = row
                            if (col > maxCol) maxCol = col
                        }
                        buf = null; type = null
                    }
                }
            }
            p.next()
        }

        if (maxRow < 0) return emptyList()
        return (0..maxRow).map { r ->
            (0..maxCol).map { c -> cells[(r.toLong() shl 20) or c.toLong()].orEmpty() }
        }
    }

    private fun formatNumeric(s: String): String {
        val d = s.toDoubleOrNull() ?: return s
        return if (kotlin.math.abs(d - Math.round(d)) < 1e-9) Math.round(d).toString()
        else s.trimEnd('0').trimEnd('.')
    }

    /** "BC12" → 列号 54（0 基） */
    private fun colOfRef(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return maxOf(0, n - 1)
    }

    private fun rowOfRef(ref: String): Int? {
        val digits = ref.dropWhile { !it.isDigit() }
        return digits.toIntOrNull()?.minus(1)
    }
}
