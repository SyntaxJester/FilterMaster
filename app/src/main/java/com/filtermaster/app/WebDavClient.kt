package com.filtermaster.app

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 极简 WebDAV 客户端（坚果云 / 其它标准 WebDAV 服务通用）。
 *
 * 坚果云配置方式：网页版 → 账户信息 → 安全选项 → 添加应用 → 生成「应用密码」。
 * 服务器地址固定 https://dav.jianguoyun.com/dav/，账户填注册邮箱，密码填应用密码（不是登录密码）。
 *
 * 只用 HttpURLConnection，无第三方依赖。所有方法都是阻塞的，调用方需放到子线程。
 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val password: String
) {

    class DavException(message: String) : Exception(message)

    data class RemoteFile(val name: String, val size: Long, val lastModified: String)

    private val auth: String
        get() = "Basic " + Base64.encodeToString(
            "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

    /** 目录 URL，保证以 / 结尾 */
    private fun dirUrl(dir: String): String {
        val b = baseUrl.trimEnd('/')
        val d = dir.trim('/')
        return if (d.isEmpty()) "$b/" else "$b/$d/"
    }

    private fun fileUrl(dir: String, name: String): String =
        dirUrl(dir) + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // PROPFIND / MKCOL 不是标准枚举方法，需要反射放行
        try {
            conn.requestMethod = method
        } catch (e: java.net.ProtocolException) {
            runCatching {
                val f = HttpURLConnection::class.java.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, method)
            }
        }
        conn.setRequestProperty("Authorization", auth)
        conn.setRequestProperty("User-Agent", "FilterMaster/1.4 (Android)")
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun failMessage(code: Int): String = when (code) {
        401 -> "账号或应用密码不正确（坚果云需使用「应用密码」，不是登录密码）"
        403 -> "无权访问该路径"
        404 -> "路径不存在"
        409 -> "上级目录不存在"
        423 -> "文件被锁定，请稍后重试"
        507 -> "云端空间不足"
        else -> "服务器返回 $code"
    }

    /** 连通性 + 凭据校验 */
    fun testConnection(dir: String) {
        val conn = open(dirUrl(dir), "PROPFIND")
        conn.setRequestProperty("Depth", "0")
        try {
            val code = conn.responseCode
            when {
                code in 200..299 -> return
                code == 404 -> throw DavException("目录不存在，可先创建：${dir.ifBlank { "/" }}")
                else -> throw DavException(failMessage(code))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 创建目录（已存在时静默通过） */
    fun ensureDir(dir: String) {
        if (dir.trim('/').isEmpty()) return
        // 逐级创建，避免 409
        val parts = dir.trim('/').split('/').filter { it.isNotBlank() }
        var cur = ""
        parts.forEach { seg ->
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            val conn = open(dirUrl(cur), "MKCOL")
            try {
                val code = conn.responseCode
                // 201 创建成功；405/301 已存在
                if (code !in 200..299 && code != 405 && code != 301) {
                    if (code == 401 || code == 403) throw DavException(failMessage(code))
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    fun upload(dir: String, file: File, remoteName: String = file.name) {
        ensureDir(dir)
        val conn = open(fileUrl(dir, remoteName), "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/zip")
        conn.setFixedLengthStreamingMode(file.length())
        try {
            conn.outputStream.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw DavException(failMessage(code))
        } finally {
            conn.disconnect()
        }
    }

    /** 列出目录下的 .zip 备份，按名称倒序（新的在前） */
    fun listBackups(dir: String): List<RemoteFile> {
        val conn = open(dirUrl(dir), "PROPFIND")
        conn.setRequestProperty("Depth", "1")
        conn.setRequestProperty("Content-Type", "application/xml")
        conn.doOutput = true
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
            </d:prop></d:propfind>""".trimIndent()
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw DavException(failMessage(code))
            val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseList(xml).filter { it.name.endsWith(".zip", true) }
                .sortedByDescending { it.name }
        } finally {
            conn.disconnect()
        }
    }

    fun download(dir: String, name: String, dest: File) {
        val conn = open(fileUrl(dir, name), "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw DavException(failMessage(code))
            FileOutputStream(dest).use { out -> conn.inputStream.use { it.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 multistatus，取每个 response 的 href 文件名 + 属性 */
    private fun parseList(xml: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser()
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
                    if (parser.name.equals("response", true) && !isDir && href.isNotBlank()) {
                        val name = runCatching {
                            URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                        }.getOrDefault(href.substringAfterLast('/'))
                        if (name.isNotBlank()) out.add(RemoteFile(name, size, modified))
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        return out
    }
}
