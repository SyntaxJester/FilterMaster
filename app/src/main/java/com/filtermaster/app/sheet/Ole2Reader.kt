package com.filtermaster.app.sheet

import java.io.File
import java.io.RandomAccessFile

/**
 * OLE2 / CFB 复合文档容器读取器（.xls 的外层格式）。
 *
 * 只实现读取命名流所需的最小功能：头部 → DIFAT → FAT → 目录 → MiniFAT。
 * 参考 [MS-CFB] 规范。
 */
internal class Ole2Reader private constructor(private val raw: ByteArray) {

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat = ArrayList<Int>()
    private val miniFat = ArrayList<Int>()
    private val entries = ArrayList<Entry>()
    private var miniContainer: ByteArray = ByteArray(0)

    data class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    companion object {
        private const val FREE = -1            // 0xFFFFFFFF
        private const val END_OF_CHAIN = -2    // 0xFFFFFFFE
        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        fun isOle2(file: File): Boolean {
            if (file.length() < 8) return false
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(8)
                f.readFully(head)
                return head.contentEquals(SIGNATURE)
            }
        }

        fun open(bytes: ByteArray): Ole2Reader {
            require(bytes.size > 512) { "文件过小，不是有效的 Excel 文件" }
            for (i in SIGNATURE.indices) {
                require(bytes[i] == SIGNATURE[i]) { "不是 OLE2 复合文档（.xls）格式" }
            }
            return Ole2Reader(bytes)
        }
    }

    init {
        sectorSize = 1 shl u16(30)
        miniSectorSize = 1 shl u16(32)
        val numFat = i32(44)
        val dirStart = i32(48)
        miniCutoff = i32(56)
        val miniFatStart = i32(60)
        val difatStart = i32(68)
        val numDifat = i32(72)

        // --- DIFAT：头部 109 项 + 后续扇区链 ---
        val difat = ArrayList<Int>(109 + numDifat * (sectorSize / 4))
        for (i in 0 until 109) difat.add(i32(76 + 4 * i))
        var sid = difatStart
        var guard = 0
        while (sid != END_OF_CHAIN && sid != FREE && guard++ < numDifat + 8) {
            val off = sectorOffset(sid)
            val cnt = sectorSize / 4 - 1
            for (i in 0 until cnt) difat.add(readI32(off + 4 * i))
            sid = readI32(off + sectorSize - 4)
        }

        // --- FAT ---
        for (k in 0 until minOf(numFat, difat.size)) {
            val fsid = difat[k]
            if (fsid == END_OF_CHAIN || fsid == FREE) continue
            val off = sectorOffset(fsid)
            for (i in 0 until sectorSize / 4) fat.add(readI32(off + 4 * i))
        }

        // --- 目录项 ---
        val dirBytes = readNormalStream(dirStart, 0)
        var p = 0
        while (p + 128 <= dirBytes.size) {
            val nameLen = (dirBytes[p + 64].toInt() and 0xFF) or ((dirBytes[p + 65].toInt() and 0xFF) shl 8)
            val chars = maxOf(0, nameLen - 2)
            val name = if (chars > 0) String(dirBytes, p, chars, Charsets.UTF_16LE) else ""
            val type = dirBytes[p + 66].toInt() and 0xFF
            val start = le32(dirBytes, p + 116)
            var size = 0L
            for (i in 0 until 8) size = size or ((dirBytes[p + 120 + i].toLong() and 0xFF) shl (8 * i))
            entries.add(Entry(name, type, start, size))
            p += 128
        }

        // --- MiniFAT + mini 容器（Root Entry 的流） ---
        val root = entries.firstOrNull { it.type == 5 }
        if (root != null) miniContainer = readNormalStream(root.start, 0)
        sid = miniFatStart
        guard = 0
        while (sid != END_OF_CHAIN && sid != FREE && guard++ < 100000) {
            val off = sectorOffset(sid)
            for (i in 0 until sectorSize / 4) miniFat.add(readI32(off + 4 * i))
            sid = if (sid < fat.size) fat[sid] else END_OF_CHAIN
        }
    }

    /** 按名称读取流；找不到返回 null */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        return if (e.size < miniCutoff) readMiniStream(e.start, e.size.toInt())
        else readNormalStream(e.start, e.size.toInt())
    }

    fun streamNames(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    // ---------- 内部 ----------
    private fun sectorOffset(sid: Int) = 512 + sid * sectorSize

    private fun readNormalStream(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(if (size > 0) size else 8192)
        var sid = start
        var guard = 0
        while (sid != END_OF_CHAIN && sid != FREE && guard++ < 1_000_000) {
            val off = sectorOffset(sid)
            if (off < 0 || off >= raw.size) break
            val len = minOf(sectorSize, raw.size - off)
            out.write(raw, off, len)
            sid = if (sid < fat.size) fat[sid] else END_OF_CHAIN
        }
        val all = out.toByteArray()
        return if (size in 1..all.size) all.copyOf(size) else all
    }

    private fun readMiniStream(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(size, 64))
        var sid = start
        var guard = 0
        while (sid != END_OF_CHAIN && sid != FREE && guard++ < 1_000_000) {
            val off = sid * miniSectorSize
            if (off < 0 || off >= miniContainer.size) break
            val len = minOf(miniSectorSize, miniContainer.size - off)
            out.write(miniContainer, off, len)
            sid = if (sid < miniFat.size) miniFat[sid] else END_OF_CHAIN
        }
        val all = out.toByteArray()
        return if (size in 1..all.size) all.copyOf(size) else all
    }

    private fun u16(off: Int) = (raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)
    private fun i32(off: Int) = readI32(off)
    private fun readI32(off: Int) = le32(raw, off)
}

internal fun le32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

internal fun le16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
