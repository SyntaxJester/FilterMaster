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
        "品牌", "货品编码", "别称", "OE码", "车型", "规格", "位置", "胶圈", "盒子", "备注", "图片", "创建时间"
    )

    fun exportRows(items: List<FilterItem>): String {
        val sb = StringBuilder("\uFEFF")
        sb.appendLine(HEADERS.joinToString(",") { quote(it) })
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        items.forEach { f ->
            val cells = listOf(
                f.brand, f.goodsCode, f.alias, f.oeCode, f.carModel,
                f.specification, f.location, f.rubberRing, f.boxInfo, f.notes,
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
        if (rows.isEmpty()) return out

        // 表头识别：按列名映射，兼容旧版本（无「别称」「位置」列）的 CSV
        val header = rows[0]
        val hasHeader = header.any { it.contains("货品编码") || it.contains("品牌") }
        val idx = mutableMapOf<String, Int>()
        if (hasHeader) {
            header.forEachIndexed { i, name ->
                val n = name.trim().removePrefix("\uFEFF")
                when {
                    n.contains("品牌") -> idx["brand"] = i
                    n.contains("货品编码") || n == "编码" -> idx["goods"] = i
                    n.contains("别称") || n.contains("别名") -> idx["alias"] = i
                    n.contains("OE") || n.contains("oe") -> idx["oe"] = i
                    n.contains("车型") -> idx["car"] = i
                    n.contains("规格") -> idx["spec"] = i
                    n.contains("位置") || n.contains("库位") -> idx["loc"] = i
                    n.contains("胶圈") -> idx["ring"] = i
                    n.contains("盒") -> idx["box"] = i
                    n.contains("备注") -> idx["note"] = i
                    n.contains("创建") || n.contains("时间") -> idx["time"] = i
                }
            }
        } else {
            // 无表头：按新版列序解析
            listOf("brand", "goods", "alias", "oe", "car", "spec", "loc", "ring", "box", "note", "img", "time")
                .forEachIndexed { i, k -> idx[k] = i }
        }

        val start = if (hasHeader) 1 else 0
        for (i in start until rows.size) {
            val c = rows[i]
            fun g(key: String) = idx[key]?.let { c.getOrNull(it)?.trim() }.orEmpty()
            val rawBrand = g("brand")
            val item = FilterItem(
                id = System.currentTimeMillis() + i,
                // 只接受已知品牌，未知值写入备注避免污染筛选
                brand = if (Brands.ALL.contains(rawBrand)) rawBrand else "",
                goodsCode = g("goods"),
                alias = g("alias"),
                oeCode = g("oe"),
                carModel = g("car"),
                specification = g("spec"),
                location = g("loc"),
                rubberRing = g("ring"),
                boxInfo = g("box"),
                notes = buildString {
                    append(g("note"))
                    if (rawBrand.isNotBlank() && !Brands.ALL.contains(rawBrand)) {
                        if (isNotEmpty()) append(" / ")
                        append("原品牌：$rawBrand")
                    }
                },
                createdAt = g("time").ifBlank {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                }
            )
            if (item.goodsCode.isNotBlank() || item.oeCode.isNotBlank() ||
                item.carModel.isNotBlank() || item.alias.isNotBlank()) {
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
