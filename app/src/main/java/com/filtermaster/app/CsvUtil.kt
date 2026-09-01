package com.filtermaster.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 导入导出。
 * 列结构：品牌,货品编码,OE码,车型,规格,胶圈,盒子,备注,图片,创建时间
 */
object CsvUtil {

    val HEADERS = listOf(
        "品牌", "货品编码", "OE码", "车型", "规格", "胶圈", "盒子", "备注", "图片", "创建时间"
    )

    fun exportRows(items: List<FilterItem>): String {
        val sb = StringBuilder("\uFEFF")
        sb.appendLine(HEADERS.joinToString(",") { quote(it) })
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        items.forEach { f ->
            val cells = listOf(
                f.brand, f.goodsCode, f.oeCode, f.carModel,
                f.specification, f.rubberRing, f.boxInfo, f.notes,
                "", // 图片不写入 CSV（体积太大），仅保留列位
                f.createdAt.ifBlank { fmt.format(Date()) }
            )
            sb.appendLine(cells.joinToString(",") { quote(it) })
        }
        return sb.toString()
    }

    /** 解析整个 CSV 文本（支持引号内逗号/换行） */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> cell.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(cell.toString()); cell.setLength(0) }
                c == '\r' -> { /* skip */ }
                c == '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row.toList())
        return rows
    }

    fun toItems(rows: List<List<String>>): List<FilterItem> {
        val out = mutableListOf<FilterItem>()
        // 第 0 行是表头则跳过
        val start = if (rows.isNotEmpty() &&
            rows[0].any { it.contains("货品编码") || it.contains("品牌") }) 1 else 0
        for (i in start until rows.size) {
            val c = rows[i]
            fun g(idx: Int) = c.getOrNull(idx)?.trim().orEmpty()
            val rawBrand = g(0)
            val item = FilterItem(
                id = System.currentTimeMillis() + i,
                // 只接受已知品牌，未知值写入备注避免污染筛选
                brand = if (Brands.ALL.contains(rawBrand)) rawBrand else "",
                goodsCode = g(1),
                oeCode = g(2),
                carModel = g(3),
                specification = g(4),
                rubberRing = g(5),
                boxInfo = g(6),
                notes = buildString {
                    append(g(7))
                    if (rawBrand.isNotBlank() && !Brands.ALL.contains(rawBrand)) {
                        if (isNotEmpty()) append(" / ")
                        append("原品牌：$rawBrand")
                    }
                },
                createdAt = g(9).ifBlank {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                }
            )
            if (item.goodsCode.isNotBlank() || item.oeCode.isNotBlank() || item.carModel.isNotBlank()) {
                out.add(item)
            }
        }
        return out
    }

    private fun quote(s: String) =
        "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""

    fun todayName(): String =
        "滤芯数据_" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) + ".csv"
}
