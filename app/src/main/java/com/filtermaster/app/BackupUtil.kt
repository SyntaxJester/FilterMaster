package com.filtermaster.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 恢复。
 *
 * 备份文件是标准 ZIP，内部结构：
 *   data.json      —— 全部记录（含字段 image_name 指向图片文件名）
 *   images/xxx.jpg —— 引用到的照片原文件
 *
 * 可以直接用电脑解压查看，不依赖本 App。
 */
object BackupUtil {

    private const val FORMAT_VERSION = 1
    const val ENTRY_DATA = "data.json"
    const val ENTRY_IMAGE_DIR = "images/"

    fun backupFileName(): String =
        "FilterMaster_备份_" +
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) + ".zip"

    /** 打包成临时 ZIP 文件，返回该文件 */
    fun createBackup(context: Context, items: List<FilterItem>): File {
        val outDir = File(context.cacheDir, "backup")
        if (!outDir.exists()) outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val zipFile = File(outDir, backupFileName())

        val arr = JSONArray()
        val imageFiles = mutableMapOf<String, File>()   // entryName -> 源文件

        items.forEach { item ->
            val obj = item.toJson()
            val path = item.imagePath
            if (!path.isNullOrBlank()) {
                val src = File(path)
                if (src.exists()) {
                    imageFiles[src.name] = src
                    obj.put("image_name", src.name)
                }
            }
            // 绝对路径在其它设备无意义，备份里不保留
            obj.remove("image_path")
            arr.put(obj)
        }

        val root = JSONObject().apply {
            put("app", "FilterMaster")
            put("format_version", FORMAT_VERSION)
            put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("count", items.size)
            put("items", arr)
        }

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_DATA))
            zos.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            imageFiles.forEach { (name, src) ->
                zos.putNextEntry(ZipEntry(ENTRY_IMAGE_DIR + name))
                FileInputStream(src).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    data class RestoreResult(
        val items: List<FilterItem>,
        val exportedAt: String,
        val imageCount: Int
    )

    /** 从 ZIP 解出记录，同时把图片写回 filesDir/images */
    fun readBackup(context: Context, zipFile: File): RestoreResult {
        var dataJson: String? = null
        val restoredImages = mutableMapOf<String, String>()  // 文件名 -> 落地绝对路径
        val imageDir = FilterStore.imageDir(context)

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == ENTRY_DATA -> dataJson = zis.readBytes().toString(Charsets.UTF_8)

                    name.startsWith(ENTRY_IMAGE_DIR) && !entry.isDirectory -> {
                        val pure = File(name).name       // 防目录穿越
                        if (pure.isNotBlank()) {
                            val dest = File(imageDir, pure)
                            FileOutputStream(dest).use { zis.copyTo(it) }
                            restoredImages[pure] = dest.absolutePath
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val json = dataJson ?: throw IllegalArgumentException("备份文件缺少 data.json，可能不是本应用的备份")
        val root = JSONObject(json)
        if (root.optString("app") != "FilterMaster") {
            throw IllegalArgumentException("这不是「汽车滤芯管理」的备份文件")
        }

        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val item = FilterItem.fromJson(o)
                val imgName = o.optString("image_name")
                item.imagePath = restoredImages[imgName]
                item
            }.getOrNull()
        }

        return RestoreResult(list, root.optString("exported_at"), restoredImages.size)
    }

    /** 把输入流落到缓存文件（用于系统选择器 / 网络下载） */
    fun cacheFrom(context: Context, input: InputStream, name: String): File {
        val dir = File(context.cacheDir, "restore")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, name)
        FileOutputStream(f).use { input.copyTo(it) }
        return f
    }
}
