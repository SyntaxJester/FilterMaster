package com.filtermaster.app

import android.util.Base64
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（坚果云 / 其它标准 WebDAV 服务通用），基于 OkHttp。
 *
 * 坚果云：网页版 → 账户信息 → 安全选项 → 添加应用 → 生成「应用密码」。
 * 地址 https://dav.jianguoyun.com/dav/，账户填注册邮箱，密码填应用密码（不是登录密码）。
 *
 * 为何用 OkHttp：HttpURLConnection 不支持 PROPFIND/MKCOL，
 * 靠反射改私有 method 字段在 Android 上无效（异常被吞，实际发出 GET），
 * 坚果云对目录 GET 返回 403，于是「已存在的目录」也会被判定为无权访问。
 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val password: String
) {

    class DavException(message: String) : Exception(message)

    data class RemoteFile(
        val name: String,
        val size: Long,
        val lastModified: String,
        val isDir: Boolean = false
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val authHeader: String =
        Credentials.basic(user, password, Charsets.UTF_8)

    /** 目录 URL，保证以 / 结尾 */
    private fun dirUrl(dir: String): String {
        val b = baseUrl.trimEnd('/')
        val d = dir.trim('/')
        val encoded = if (d.isEmpty()) "" else "/" + d.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$b$encoded/"
    }

    private fun fileUrl(dir: String, name: String): String =
        dirUrl(dir) + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun failMessage(code: Int): String = when (code) {
        401 -> "账号或应用密码不正确（坚果云需使用「应用密码」，不是登录密码）"
        403 -> "无权访问该路径"
        404 -> "路径不存在"
        405 -> "服务器不支持该操作"
        409 -> "上级目录不存在"
        423 -> "文件被锁定，请稍后重试"
        507 -> "云端空间不足"
        else -> "服务器返回 $code"
    }

    private val propfindBody =
        """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
</d:prop></d:propfind>"""

    /** 发送 PROPFIND，返回 (响应码, 响应体) */
    private fun propfind(url: String, depth: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth)
            .header("Authorization", authHeader)
            .header("User-Agent", "FilterMaster/1.7 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return resp.code to body
        }
    }

    /** 目录是否存在（Depth:0） */
    fun dirExists(dir: String): Boolean {
        val (code, _) = propfind(dirUrl(dir), "0")
        return code in 200..299 || code == 301 || code == 302
    }

    /** 列出某个目录下的条目（Depth:1），包含子文件夹 */
    fun listDir(dir: String): List<RemoteFile> {
        val (code, xml) = propfind(dirUrl(dir), "1")
        if (code !in 200..299) throw DavException(failMessage(code))
        val entries = parseList(xml)
        // 第一个通常是目录自身，按名称剔除
        val self = dir.trim('/').substringAfterLast('/')
        return entries.filter { it.name.isNotBlank() && !(it.isDir && it.name == self) }
            .sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 列出可用的备份 zip，新的在前 */
    fun listBackups(dir: String): List<RemoteFile> =
        listDir(dir).filter { !it.isDir && it.name.endsWith(".zip", true) }
            .sortedByDescending { it.name }

    /** 建目录（逐级、先探测后创建，已存在不算错） */
    fun ensureDir(dir: String) {
        if (dir.trim('/').isEmpty()) return
        var cur = ""
        dir.trim('/').split('/').filter { it.isNotBlank() }.forEach { seg ->
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            if (dirExists(cur)) return@forEach
            val req = Request.Builder()
                .url(dirUrl(cur))
                .method("MKCOL", null)
                .header("Authorization", authHeader)
                .header("User-Agent", "FilterMaster/1.7 (Android)")
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code in 200..299 -> Unit
                    resp.code == 405 || resp.code == 301 || resp.code == 302 -> Unit // 已存在
                    resp.code == 401 -> throw DavException(failMessage(401))
                    resp.code == 403 -> throw DavException(
                        "无法在云端创建文件夹「$seg」（403 无权访问）。\n\n" +
                            "请确认：\n" +
                            "1. 地址为 https://dav.jianguoyun.com/dav/\n" +
                            "2. 密码是「应用密码」而非登录密码\n" +
                            "3. 或在「浏览云端」里直接选择一个已存在的文件夹"
                    )
                    else -> throw DavException("创建文件夹失败：${failMessage(resp.code)}")
                }
            }
        }
    }

    /** 连通性 + 凭据校验 */
    fun testConnection(dir: String) {
        // 先只验证根目录凭据
        val (rootCode, _) = propfind(dirUrl(""), "0")
        when {
            rootCode in 200..299 -> Unit
            rootCode == 401 -> throw DavException(failMessage(401))
            rootCode == 403 -> throw DavException(
                "凭据被拒绝（403）。请确认使用坚果云「应用密码」，" +
                    "并在 账户信息 → 安全选项 → 添加应用 中生成。"
            )
            else -> throw DavException("连接失败：${failMessage(rootCode)}")
        }

        // 再校验目标目录，不存在则创建
        ensureDir(dir)

        val (code, _) = propfind(dirUrl(dir), "0")
        when {
            code in 200..299 -> return
            code == 404 -> throw DavException("目录「${dir}」不存在，且自动创建失败。")
            else -> throw DavException(failMessage(code))
        }
    }

    fun upload(dir: String, file: File, remoteName: String = file.name) {
        ensureDir(dir)
        val req = Request.Builder()
            .url(fileUrl(dir, remoteName))
            .put(file.asRequestBody("application/zip".toMediaType()))
            .header("Authorization", authHeader)
            .header("User-Agent", "FilterMaster/1.7 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw DavException(failMessage(resp.code))
        }
    }

    fun download(dir: String, name: String, dest: File) {
        val req = Request.Builder()
            .url(fileUrl(dir, name))
            .get()
            .header("Authorization", authHeader)
            .header("User-Agent", "FilterMaster/1.7 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw DavException(failMessage(resp.code))
            val body = resp.body ?: throw DavException("服务器未返回内容")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** 删除远端文件（用于清理旧备份） */
    fun delete(dir: String, name: String) {
        val req = Request.Builder()
            .url(fileUrl(dir, name))
            .delete()
            .header("Authorization", authHeader)
            .header("User-Agent", "FilterMaster/1.7 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299 && resp.code != 404) throw DavException(failMessage(resp.code))
        }
    }

    /** 解析 multistatus */
    private fun parseList(xml: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")

        var href = ""
        var size = 0L
        var modified = ""
        var isDir = false
        var tag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag.equals("response", true)) {
                        href = ""; size = 0L; modified = ""; isDir = false
                    }
                    if (tag.equals("collection", true)) isDir = true
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when {
                            tag.equals("href", true) -> href = text
                            tag.equals("getcontentlength", true) -> size = text.toLongOrNull() ?: 0L
                            tag.equals("getlastmodified", true) -> modified = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("response", true) && href.isNotBlank()) {
                        val raw = href.trimEnd('/').substringAfterLast('/')
                        val name = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                        if (name.isNotBlank()) out.add(RemoteFile(name, size, modified, isDir))
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        return out
    }
}
