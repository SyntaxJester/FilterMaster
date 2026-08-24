package com.filtermaster.app

import android.content.Context
import org.json.JSONArray
import java.io.File

object FilterStore {

    private const val PREF = "filter_master"
    private const val KEY_DATA = "filter_app_data_v3"

    fun load(context: Context): MutableList<FilterItem> {
        return try {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return mutableListOf()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { FilterItem.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, items: List<FilterItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, arr.toString()).apply()
    }

    /** 图片存储目录：filesDir/images */
    fun imageDir(context: Context): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newImageFile(context: Context): File =
        File(imageDir(context), "img_${System.currentTimeMillis()}.jpg")
}
