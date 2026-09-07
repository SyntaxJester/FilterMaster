package com.filtermaster.app

/**
 * 从 OCR 文本里提取滤芯字段。
 *
 * 拍出来的往往是滤芯包装盒／标签，文本零散且顺序不定，
 * 所以采用「先找带标签的行，再按形态兜底猜测」两段式策略。
 */
object OcrExtractor {

    data class Result(
        val goodsCode: String = "",
        val oeCodes: List<String> = emptyList(),
        val carModel: String = "",
        val brand: String = "",
        val specification: String = "",
        val rawLines: List<String> = emptyList()
    ) {
        val oeText: String get() = oeCodes.joinToString(" / ")
    }

    // 标签词 → 字段
    private val LABEL_GOODS = listOf("货品编码", "商品编码", "产品编码", "编码", "编号", "货号", "型号", "品号", "料号")
    private val LABEL_OE = listOf("oem", "oe码", "oe", "原厂编码", "原厂码", "原厂号", "适配", "对照")
    private val LABEL_CAR = listOf("车型", "适用车型", "适配车型", "适用车系", "车系", "适用")
    private val LABEL_SPEC = listOf("规格", "尺寸", "外径")

    /** 明显不是编码的词，避免误抓 */
    private val NOISE = listOf(
        "机油滤清器", "空气滤清器", "空调滤清器", "燃油滤清器", "滤清器",
        "机油滤芯", "空气滤芯", "空调滤芯", "燃油滤芯", "滤芯",
        "made in china", "中国制造", "质量保证", "厂址", "电话", "生产日期",
        "oil filter", "air filter", "fuel filter", "filter"
    )

    fun extract(text: String): Result {
        val lines = text.split('\n')
            .map { normalize(it) }
            .filter { it.isNotBlank() }

        var goods = ""
        val oes = LinkedHashSet<String>()
        var car = ""
        var spec = ""
        var brand = ""

        // ---- 第一遍：带标签的行 ----
        lines.forEachIndexed { idx, line ->
            val low = line.lowercase()

            fun valueAfterLabel(labels: List<String>): String? {
                labels.forEach { lab ->
                    val at = low.indexOf(lab)
                    if (at >= 0) {
                        val tail = line.substring(at + lab.length)
                            .trimStart(':', '：', ' ', '=', '#', '-', '/')
                        // 标签独占一行时取下一行
                        if (tail.isBlank()) return lines.getOrNull(idx + 1)?.takeIf {
                            !hasAnyLabel(it)
                        }
                        return tail
                    }
                }
                return null
            }

            // 「OIL FILTER」这类品名不能当 OE 标签（filter 会命中 LABEL_OE 里的 oe）
            val isProductName = isNoise(line)

            if (goods.isEmpty()) {
                valueAfterLabel(LABEL_GOODS)?.let { v ->
                    firstCode(v)?.let { goods = it }
                }
            }
            if (!isProductName) {
                valueAfterLabel(LABEL_OE)?.let { v -> oes.addAll(allCodes(v)) }
            }
            if (car.isEmpty()) {
                valueAfterLabel(LABEL_CAR)?.let { v ->
                    if (hasChinese(v) && v.length >= 2) car = v.take(60)
                }
            }
            if (spec.isEmpty()) {
                valueAfterLabel(LABEL_SPEC)?.let { v -> if (v.length in 2..30) spec = v }
            }
        }

        // ---- 第二遍：按形态兜底 ----
        val codeCandidates = mutableListOf<String>()
        lines.forEach { line ->
            if (isNoise(line)) return@forEach
            allCodes(line).forEach { c -> if (c !in oes) codeCandidates.add(c) }
        }

        // 货品编码只认「字母前缀+数字」的自编码形态；纯数字长码归 OE，避免把 OE 填错位置
        if (goods.isEmpty()) {
            goods = codeCandidates.firstOrNull { looksLikeInternalCode(it) }.orEmpty()
        }
        if (oes.isEmpty()) {
            codeCandidates.filter { it != goods && looksLikeOe(it) }
                .take(6).forEach { oes.add(it) }
        }
        if (car.isEmpty()) {
            car = lines.filter { !isNoise(it) && hasChinese(it) && it.length in 3..40 }
                .maxByOrNull { chineseCount(it) }
                ?: lines.filter {
                    // 无中文时退一步：字母数字混排且带空格的行（如 "BMW B48 2.0T"）
                    !isNoise(it) && it.length in 6..40 && it.contains(' ') &&
                            it.any { ch -> ch.isLetter() } && !isPlausibleCode(it)
                }.maxByOrNull { it.length }.orEmpty()
        }
        if (spec.isEmpty()) {
            spec = lines.firstOrNull { SPEC_PATTERN.containsMatchIn(it) }
                ?.let { SPEC_PATTERN.find(it)?.value }
                .orEmpty()
        }
        brand = Brands.ALL.firstOrNull { b -> lines.any { it.contains(b, true) } }.orEmpty()

        return Result(
            goodsCode = goods.take(40),
            oeCodes = oes.filter { it != goods }.take(8),
            carModel = car.take(60),
            brand = brand,
            specification = spec.take(30),
            rawLines = lines
        )
    }

    // ---------- 工具 ----------
    private val SPEC_PATTERN =
        Regex("[φΦø]?\\s*\\d{2,3}\\s*[*×xX]\\s*\\d{2,3}(\\s*[*×xX]\\s*\\d{1,3})?")

    /** 排量描述：1.6L / 2.0T / 3.0TD / 2.5 CRD */
    private val DISPLACEMENT = Regex("\\d\\.\\d\\s*[A-Za-z]{1,3}\\b")

    /** 编码：连续的字母数字（允许中间的 - . / 空格），长度 4 起 */
    private val CODE_PATTERN =
        Regex("[A-Za-z0-9]{1,}(?:[\\-.\\s/][A-Za-z0-9]+){0,5}")

    private fun normalize(s: String): String = s
        .replace('\u00A0', ' ')
        .replace('：', ':')
        .replace(Regex("[\\t\\r]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

    private fun hasAnyLabel(s: String): Boolean {
        val low = s.lowercase()
        return (LABEL_GOODS + LABEL_OE + LABEL_CAR + LABEL_SPEC).any { low.contains(it) }
    }

    private fun isNoise(s: String): Boolean {
        val low = s.lowercase().replace(" ", "")
        return NOISE.any { low == it.replace(" ", "") || (low.length < 14 && low.contains(it.replace(" ", ""))) }
    }

    private fun allCodes(s: String): List<String> =
        CODE_PATTERN.findAll(s)
            .map { it.value.trim().trim('-', '.', '/') }
            .filter { isPlausibleCode(it) }
            .distinct()
            .toList()

    private fun firstCode(s: String): String? = allCodes(s).firstOrNull()

    private fun isPlausibleCode(c: String): Boolean {
        val compact = c.replace(Regex("[\\s\\-./]"), "")
        if (compact.length < 4 || compact.length > 24) return false
        if (!compact.any { it.isDigit() }) return false
        if (hasChinese(c)) return false
        // 纯年份/日期之类的短数字排除
        if (compact.length <= 4 && compact.all { it.isDigit() }) return false
        // 含排量描述（1.6L / 2.0T / 3.0TD）的是车型不是编码
        if (DISPLACEMENT.containsMatchIn(c)) return false
        // 空格分段时任一段是纯字母 → 车型/品牌描述（如 "BMW B48"），不是编码
        if (c.contains(' ')) {
            val segs = c.split(' ').filter { it.isNotBlank() }
            if (segs.any { seg -> seg.all { it.isLetter() } }) return false
        }
        return true
    }

    private fun looksLikeInternalCode(c: String): Boolean {
        val compact = c.replace(Regex("[\\s\\-./]"), "")
        val letters = compact.count { it.isLetter() }
        return letters in 1..3 && compact.length in 5..12 &&
                compact.first().isLetter()
    }

    private fun looksLikeOe(c: String): Boolean {
        val compact = c.replace(Regex("[\\s\\-./]"), "")
        return compact.length in 6..20
    }

    private fun hasChinese(s: String) = s.any { it.code in 0x4E00..0x9FFF }
    private fun chineseCount(s: String) = s.count { it.code in 0x4E00..0x9FFF }
}
