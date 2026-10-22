package com.filtermaster.app

import android.content.Context

/**
 * 云端同步状态存储。
 *
 * 记录上次同步时间、类型，用于智能同步判断。
 */
object SyncStatus {

    private const val PREF = "filter_master_sync"
    private const val K_LAST_UPLOAD = "last_upload_time"
    private const val K_LAST_DOWNLOAD = "last_download_time"
    private const val K_LOG = "sync_log"

    fun getLastUploadTime(context: Context): Long {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(K_LAST_UPLOAD, 0L)
    }

    fun setLastUploadTime(context: Context, time: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(K_LAST_UPLOAD, time)
            .apply()
    }

    fun getLastDownloadTime(context: Context): Long {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(K_LAST_DOWNLOAD, 0L)
    }

    fun setLastDownloadTime(context: Context, time: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(K_LAST_DOWNLOAD, time)
            .apply()
    }

    /** 追加同步日志（最多保留10条） */
    fun appendLog(context: Context, action: String, detail: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = sp.getStringSet(K_LOG, emptySet())?.toList() ?: emptyList()
        val now = System.currentTimeMillis()
        val newEntry = "$now|$action|$detail"
        val updated = (listOf(newEntry) + existing).take(10).toMutableSet()
        sp.edit().putStringSet(K_LOG, updated).apply()
    }

    fun getLog(context: Context): List<Triple<Long, String, String>> {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(K_LOG, emptySet())
            ?.map { entry ->
                val parts = entry.split("|", limit = 3)
                if (parts.size == 3) {
                    Triple(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2])
                } else null
            }
            ?.filterNotNull()
            ?.sortedByDescending { it.first }
            ?: emptyList()
    }

    fun clearLog(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(K_LOG).apply()
    }
}
