package com.filtermaster.app.sheet

import com.filtermaster.app.Brands
import com.filtermaster.app.FilterItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 表格导入：识别 .xls / .xlsx / .csv，自动把列对应到滤芯字段。
 *
 * 列识别按表头关键词匹配（支持常见供应商表格的多种叫法），
 * 识别不到时调用方可让用户手动指定。
 */
object SheetImporter {

    /** 一张待导入的表 */
    data class Table(
        val sheetName: String,
        val header: List<String>,
        val rows: List<List<String>>   // 不含表头
    ) {
        val columnLabels: List<String>
            get() = header.mapIndexed { i, h ->
                val name = h.trim().ifBlank { "第${i + 1}列" }
                "$name（${colLetter(i)}）"
            }
    }

    /** 字段 → 列号映射，-1 表示不导入 */
    data class Mapping(
        var brand: Int = -1,
        var goodsCode: Int = -1,
        var alias: Int = -1,
        var oeCode: Int = -1,
        var carModel: Int = -1,
        var specification: Int = -1,
        var location: Int = -1,
        var rubberRing: Int = -1,
        var boxInfo: Int = -1,
        var notes: Int = -1,
        var quantity: Int = -1
    ) {
        val hasAnyKeyField: Boolean
            get() = goodsCode >= 0 || oeCode >= 0 || carModel >= 0 || alias >= 0
    }

    /** 字段展示名 → 取值/赋值器，供手动调整 UI 使用 */
    val FIELD_LABELS = listOf(
        "品牌", "货品编码", "别称", "OE码", "车型", "规格", "位置", "胶圈", "盒子", "备注", "数量"
    )

    fun getField(m: Mapping, index: Int): Int = when (index) {
        0 -> m.brand; 1 -> m.goodsCode; 2 -> m.alias; 3 -> m.oeCode
        4 -> m.carModel; 5 -> m.specification; 6 -> m.location; 7 -> m.rubberRing
        8 -> m.boxInfo; 9 -> m.notes; else -> m.quantity
    }

    fun setField(m: Mapping, index: Int, col: Int) {
        when (index) {
            0 -> m.brand = col; 1 -> m.goodsCode = col; 2 -> m.alias = col; 3 -> m.oeCode = col
            4 -> m.carModel = col; 5 -> m.specification = col; 6 -> m.location = col
            7 -> m.rubberRing = col; 8 -> m.boxInfo = col; 9 -> m.notes = col
            else -> m.quantity = col
        }
    }

    // ==================== 读取文件 ====================
    fun readTables(file: File, fileName: String): List<Table> {
        val lower = fileName.lowercase()
        val sheets: List<BiffParser.Sheet> = when {
            XlsxReader.isXlsx(file) -> XlsxReader.parse(file)

            Ole2Reader.isOle2(file) -> {
                val ole = Ole2Reader.open(file.readBytes())
                val wb = ole.stream("Workbook") ?: ole.stream("Book")
                    ?: throw IllegalArgumentException(
                        "这个 .xls 里没有找到工作表数据（流：${ole.streamNames().joinToString()}）"
                    )
                BiffParser.parse(wb)
            }

            lower.endsWith(".csv") || lower.endsWith(".txt") ->
                listOf(BiffParser.Sheet("CSV", parseCsv(readTextGuess(file))))

            else -> throw IllegalArgumentException(
                "不支持的文件格式，请选择 Excel(.xls/.xlsx) 或 CSV 文件"
            )
        }

        return sheets.mapNotNull { sheet ->
            val rows = sheet.rows.filter { row -> row.any { it.isNotBlank() } }
            if (rows.isEmpty()) return@mapNotNull null
            val headerIdx = detectHeaderRow(rows)
            val header = rows[headerIdx]
            val body = rows.drop(headerIdx + 1).filter { row -> row.any { it.isNotBlank() } }
            if (body.isEmpty()) return@mapNotNull null
            Table(sheet.name, header, body)
        }
    }

    /** GBK / UTF-8 自动判断（国内表格常见 GBK） */
    private fun readTextGuess(file: File): String {
        val bytes = file.readBytes()
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }

    /** 表头行：前 12 行里命中已知列名最多的一行 */
    private fun detectHeaderRow(rows: List<List<String>>): Int {
        var best = 0
        var bestScore = -1
        for (i in 0 until minOf(rows.size, 12)) {
            val score = rows[i].count { cell -> matchField(cell) != null }
            if (score > bestScore) { bestScore = score; best = i }
        }
        return if (bestScore <= 0) 0 else best
    }

    // ==================== 列名识别 ====================
    private val SYNONYMS: List<Pair<Int, List<String>>> = listOf(
        // 顺序即优先级：先匹配更具体的词
        1 to listOf("货品编码", "商品编码", "产品编码", "货号", "型号", "编码", "编号", "自编码", "料号", "品号"),
        3 to listOf("oem", "oe码", "oe", "原厂编码", "原厂码", "原厂号", "配套号", "对照码", "适配码"),
        2 to listOf("别称", "别名", "简称", "俗称", "习惯叫法"),
        4 to listOf("车型", "适用车型", "适配车型", "适用车系", "车系", "适用"),
        5 to listOf("规格", "尺寸", "外径", "参数"),
        6 to listOf("位置", "库位", "货位", "存放", "架位", "仓位"),
        7 to listOf("胶圈", "密封圈", "o型圈", "胶垫"),
        8 to listOf("盒子", "包装", "彩盒", "外箱", "箱规"),
        0 to listOf("品牌", "厂牌", "商标", "供应商", "厂家", "供货商", "供应厂"),
        10 to listOf("数量", "库存", "件数", "存量", "数"),
        9 to listOf("备注", "说明", "备注信息", "注")
    )

    /** 名称对应的字段序号；识别不到返回 null */
    private fun matchField(rawHeader: String): Int? {
        val h = rawHeader.trim().lowercase()
            .replace(" ", "").replace("　", "")
            .replace("(", "").replace(")", "")
            .replace("（", "").replace("）", "")
        if (h.isEmpty()) return null
        SYNONYMS.forEach { (field, words) ->
            if (words.any { h == it }) return field
        }
        SYNONYMS.forEach { (field, words) ->
            if (words.any { h.contains(it) }) return field
        }
        return null
    }

    fun autoMap(table: Table): Mapping {
        val m = Mapping()
        table.header.forEachIndexed { col, name ->
            val field = matchField(name) ?: return@forEachIndexed
            if (getField(m, field) < 0) setField(m, field, col)   // 同名列只取第一个
        }

        // 表头完全认不出来时，按内容猜：含「货品名称」这类通用名不算关键字段
        if (!m.hasAnyKeyField) {
            val sample = table.rows.take(20)
            table.header.indices.forEach { col ->
                val values = sample.mapNotNull { it.getOrNull(col)?.trim() }.filter { it.isNotEmpty() }
                if (values.isEmpty()) return@forEach
                when {
                    m.goodsCode < 0 && values.count { looksLikeCode(it) } > values.size / 2 ->
                        m.goodsCode = col
                    m.oeCode < 0 && values.count { looksLikeOe(it) } > values.size / 2 ->
                        m.oeCode = col
                    m.carModel < 0 && values.count { it.length > 4 && hasChinese(it) } > values.size / 2 ->
                        m.carModel = col
                }
            }
        }
        return m
    }

    private fun looksLikeCode(s: String) =
        s.length in 3..20 && s.any { it.isDigit() } && !hasChinese(s)

    private fun looksLikeOe(s: String) =
        s.length in 5..60 && s.any { it.isDigit() } && !hasChinese(s)

    private fun hasChinese(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    // ==================== 生成记录 ====================
    /** 通用品名词：这些值出现在「货品名称」列时不作为别称 */
    private val GENERIC_NAMES = listOf(
        "机油滤", "机油滤芯", "机滤", "机油格",
        "空气滤", "空气滤芯", "空滤", "空气格",
        "空调滤", "空调滤芯", "空调格",
        "燃油滤", "燃油滤芯", "柴滤", "柴油滤", "汽油滤", "油滤",
        "滤芯", "滤清器"
    )

    data class BuildResult(val items: List<FilterItem>, val skipped: Int)

    /**
     * @param defaultBrand 表格里没有可识别品牌时统一填入的品牌（空则留空）
     */
    fun buildItems(table: Table, m: Mapping, defaultBrand: String = ""): BuildResult {
        val out = mutableListOf<FilterItem>()
        var skipped = 0
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val base = System.currentTimeMillis()

        table.rows.forEachIndexed { i, row ->
            fun cell(col: Int) = if (col in row.indices) clean(row[col]) else ""

            val goods = cell(m.goodsCode)
            val oe = cell(m.oeCode)
            val car = cell(m.carModel)
            var alias = cell(m.alias)
            if (alias.isNotEmpty() && GENERIC_NAMES.any { alias.replace(" ", "") == it }) alias = ""

            if (goods.isEmpty() && oe.isEmpty() && car.isEmpty() && alias.isEmpty()) {
                skipped++
                return@forEachIndexed
            }

            val rawBrand = cell(m.brand)
            val brand = normalizeBrand(rawBrand).ifEmpty { defaultBrand }

            val extras = mutableListOf<String>()
            cell(m.notes).takeIf { it.isNotEmpty() }?.let { extras.add(it) }
            cell(m.quantity).takeIf { it.isNotEmpty() }?.let { extras.add("数量：$it") }
            // 原始供应商/品牌名无法归入已知品牌时保留到备注，信息不丢
            if (rawBrand.isNotEmpty() && !rawBrand.equals(brand, ignoreCase = true)) {
                extras.add("供应商：$rawBrand")
            }

            out.add(
                FilterItem(
                    id = base + i,
                    brand = brand,
                    goodsCode = goods,
                    alias = alias,
                    oeCode = oe,
                    carModel = car,
                    specification = cell(m.specification),
                    location = cell(m.location),
                    rubberRing = cell(m.rubberRing),
                    boxInfo = cell(m.boxInfo),
                    notes = extras.joinToString(" / "),
                    createdAt = stamp
                )
            )
        }
        return BuildResult(out, skipped)
    }

    /** 已知品牌按包含关系匹配，未知返回空（原值转入备注） */
    private fun normalizeBrand(raw: String): String {
        if (raw.isBlank()) return ""
        val v = raw.trim()
        Brands.ALL.firstOrNull { it.equals(v, ignoreCase = true) }?.let { return it }
        Brands.ALL.firstOrNull { v.contains(it, ignoreCase = true) }?.let { return it }
        return ""
    }

    /**
     * 单元格清洗：去首尾空白、换行转空格。
     * 连续 2 个以上空格通常是多个编码之间的分隔，统一换成 " / " 更易读。
     */
    private fun clean(s: String): String = s
        .replace('\u00A0', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .replace(Regex(" {2,}"), " / ")
        .trim(' ', '/')
        .trim()

    // ==================== CSV ====================
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' ->
                        if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ }
                        else inQuotes = false
                    else -> cell.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' || c == '\t' -> { row.add(cell.toString()); cell.setLength(0) }
                c == '\r' -> {}
                c == '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row.toList()); row.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row.toList())
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun colLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }
}
