package com.filtermaster.app

import org.json.JSONObject

data class FilterItem(
    var id: Long = 0,
    /** 品牌（AD / 玖壳 / 默利森 …） */
    var brand: String = "",
    var goodsCode: String = "",
    /** 别称：自己习惯的叫法，方便记忆 */
    var alias: String = "",
    var oeCode: String = "",
    var carModel: String = "",
    var specification: String = "",
    /** 位置：库房存放位置（几号架 / 几号箱） */
    var location: String = "",
    var rubberRing: String = "",
    var boxInfo: String = "",
    var notes: String = "",
    var imagePath: String? = null,
    var createdAt: String = ""
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("brand", brand)
        put("goods_code", goodsCode)
        put("alias", alias)
        put("oe_code", oeCode)
        put("car_model", carModel)
        put("specification", specification)
        put("location", location)
        put("rubber_ring", rubberRing)
        put("box_info", boxInfo)
        put("notes", notes)
        put("image_path", imagePath ?: "")
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): FilterItem = FilterItem(
            id = o.optLong("id", System.currentTimeMillis()),
            // 兼容旧版本字段名 type（原滤芯类型），旧值不在品牌列表中则丢弃
            brand = o.optString("brand").ifBlank {
                o.optString("type").takeIf { Brands.ALL.contains(it) } ?: ""
            },
            goodsCode = o.optString("goods_code"),
            alias = o.optString("alias"),
            oeCode = o.optString("oe_code"),
            carModel = o.optString("car_model"),
            specification = o.optString("specification"),
            location = o.optString("location"),
            rubberRing = o.optString("rubber_ring"),
            boxInfo = o.optString("box_info"),
            notes = o.optString("notes"),
            imagePath = o.optString("image_path").ifBlank { null },
            createdAt = o.optString("created_at")
        )
    }
}

/** 用于导入去重的业务键 */
val FilterItem.dedupeKey: String
    get() = "${goodsCode.trim()}|${oeCode.trim()}"

fun matchesKeyword(item: FilterItem, kwLower: String): Boolean =
    listOf(
        item.goodsCode, item.alias, item.oeCode, item.carModel, item.specification,
        item.location, item.rubberRing, item.boxInfo, item.notes, item.brand
    ).any { it.lowercase().contains(kwLower) }
