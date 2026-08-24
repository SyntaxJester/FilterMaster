package com.filtermaster.app

import org.json.JSONObject

data class FilterItem(
    var id: Long = 0,
    var type: String = "",
    var goodsCode: String = "",
    var oeCode: String = "",
    var carModel: String = "",
    var specification: String = "",
    var rubberRing: String = "",
    var boxInfo: String = "",
    var notes: String = "",
    var imagePath: String? = null,
    var createdAt: String = ""
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("goods_code", goodsCode)
        put("oe_code", oeCode)
        put("car_model", carModel)
        put("specification", specification)
        put("rubber_ring", rubberRing)
        put("box_info", boxInfo)
        put("notes", notes)
        put("image_path", imagePath ?: "")
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): FilterItem = FilterItem(
            id = o.optLong("id", System.currentTimeMillis()),
            type = o.optString("type"),
            goodsCode = o.optString("goods_code"),
            oeCode = o.optString("oe_code"),
            carModel = o.optString("car_model"),
            specification = o.optString("specification"),
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
        item.goodsCode, item.oeCode, item.carModel, item.specification,
        item.rubberRing, item.boxInfo, item.notes, item.type
    ).any { it.lowercase().contains(kwLower) }
