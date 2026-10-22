package com.filtermaster.app

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 智能同步引擎。
 *
 * 比较本地数据与云端备份的时间戳，决定同步方向：
 * - 本地更新 → 上传
 * - 云端更新 → 下载并提示合并/覆盖
 * - 时间相同 → 无需操作
 */
object SmartSync {

    private const val FORMAT = "yyyy-MM-dd HH:mm:ss"
    private val sdf = SimpleDateFormat(FORMAT, Locale.getDefault())

    /** 云端备份文件的最后修改时间（RFC 1123 格式） */
    fun parseRemoteTime(timeStr: String): Long {
        return try {
            val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            parsed.parse(timeStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 本地导出时间（从 data.json 中读取） */
    fun parseLocalTime(json: String): Long {
        return try {
            val root = org.json.JSONObject(json)
            val exportedAt = root.optString("exported_at", "")
            sdf.parse(exportedAt)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 比较本地与云端，返回同步建议。
     *
     * @return 0:无需同步, 1:需要上传, -1:需要下载
     */
    fun compare(localExportedAt: Long, remoteModifiedAt: Long): Int {
        val threshold = 60_000L // 1分钟容差
        val diff = localExportedAt - remoteModifiedAt
        return when {
            diff > threshold -> 1  // 本地更新
            diff < -threshold -> -1 // 云端更新
            else -> 0               // 相同
        }
    }

    /** 格式化时间用于显示 */
    fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "从未"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(timestamp)
        } catch (e: Exception) {
            "未知"
        }
    }
}
