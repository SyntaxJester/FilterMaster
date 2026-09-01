package com.filtermaster.app

/**
 * 滤芯品牌配置。要增删品牌只改这里一处即可。
 */
object Brands {

    /** 品牌列表（顺序即界面显示顺序） */
    val ALL = listOf(
        "AD", "玖壳", "默利森", "金登", "康信",
        "外贸", "海泽飞", "诺富曼", "滤之源", "雷鼎"
    )

    /** 品牌徽章配色（背景色 to 文字色），按索引循环取用 */
    private val PALETTE = listOf(
        R.color.b1_bg to R.color.b1_fg,
        R.color.b2_bg to R.color.b2_fg,
        R.color.b3_bg to R.color.b3_fg,
        R.color.b4_bg to R.color.b4_fg,
        R.color.b5_bg to R.color.b5_fg,
        R.color.b6_bg to R.color.b6_fg,
        R.color.b7_bg to R.color.b7_fg,
        R.color.b8_bg to R.color.b8_fg,
        R.color.b9_bg to R.color.b9_fg,
        R.color.b10_bg to R.color.b10_fg
    )

    fun colorsOf(brand: String): Pair<Int, Int> {
        val idx = ALL.indexOf(brand)
        return if (idx < 0) R.color.none_bg to R.color.none_fg
        else PALETTE[idx % PALETTE.size]
    }
}
