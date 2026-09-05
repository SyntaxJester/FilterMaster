package com.filtermaster.app.sheet

/**
 * BIFF8（Excel 97-2003 .xls）工作表解析器。
 *
 * 只解析取值需要的记录：BOUNDSHEET / SST / LABELSST / LABEL / RK / MULRK /
 * NUMBER / FORMULA+STRING / BOOLERR / BLANK。公式一律取缓存结果，不做求值。
 * 参考 [MS-XLS] 规范。
 */
internal object BiffParser {

    // 记录类型
    private const val R_BOF = 0x0809
    private const val R_EOF = 0x000A
    private const val R_BOUNDSHEET = 0x0085
    private const val R_SST = 0x00FC
    private const val R_CONTINUE = 0x003C
    private const val R_LABELSST = 0x00FD
    private const val R_LABEL = 0x0204
    private const val R_RK = 0x027E
    private const val R_MULRK = 0x00BD
    private const val R_NUMBER = 0x0203
    private const val R_FORMULA = 0x0006
    private const val R_STRING = 0x0207
    private const val R_BOOLERR = 0x0205
    private const val R_MULBLANK = 0x00BE
    private const val R_BLANK = 0x0201

    private class Record(val type: Int, val data: ByteArray, val offset: Int)

    class Sheet(val name: String, val rows: List<List<String>>)

    fun parse(workbook: ByteArray): List<Sheet> {
        val recs = splitRecords(workbook)

        // 工作表目录
        val boundSheets = mutableListOf<Pair<String, Int>>()
        for (r in recs) {
            if (r.type != R_BOUNDSHEET) continue
            val pos = le32(r.data, 0)
            val cch = r.data[6].toInt() and 0xFF
            val grbit = r.data[7].toInt() and 0xFF
            val name = if (grbit and 1 != 0) {
                String(r.data, 8, minOf(cch * 2, r.data.size - 8), Charsets.UTF_16LE)
            } else {
                String(r.data, 8, minOf(cch, r.data.size - 8), Charsets.ISO_8859_1)
            }
            boundSheets.add(name to pos)
        }

        val sst = readSst(recs)
        val out = mutableListOf<Sheet>()

        boundSheets.forEach { (name, pos) ->
            val startIdx = recs.indexOfFirst { it.offset == pos && it.type == R_BOF }
            if (startIdx < 0) return@forEach
            out.add(Sheet(name, readCells(recs, startIdx + 1, sst)))
        }
        // 没有 BOUNDSHEET（极少见）时，整体当作一张表
        if (out.isEmpty()) out.add(Sheet("Sheet1", readCells(recs, 0, sst)))
        return out
    }

    private fun splitRecords(b: ByteArray): List<Record> {
        val out = ArrayList<Record>(1024)
        var p = 0
        while (p + 4 <= b.size) {
            val type = le16(b, p)
            val len = le16(b, p + 2)
            if (p + 4 + len > b.size) break
            out.add(Record(type, b.copyOfRange(p + 4, p + 4 + len), p))
            p += 4 + len
        }
        return out
    }

    // ---------- SST（共享字符串表，可跨 CONTINUE 记录切断） ----------
    private fun readSst(recs: List<Record>): List<String> {
        val idx = recs.indexOfFirst { it.type == R_SST }
        if (idx < 0) return emptyList()
        val head = recs[idx].data
        if (head.size < 8) return emptyList()
        val unique = le32(head, 4)

        val blocks = ArrayList<ByteArray>()
        blocks.add(head.copyOfRange(8, head.size))
        var j = idx + 1
        while (j < recs.size && recs[j].type == R_CONTINUE) {
            blocks.add(recs[j].data); j++
        }

        val cur = BlockCursor(blocks)
        val out = ArrayList<String>(maxOf(unique, 0))
        try {
            repeat(unique) {
                val cch = cur.u16()
                var grbit = cur.byte()
                var wide = grbit and 1 != 0
                val rich = grbit and 8 != 0
                val ext = grbit and 4 != 0
                val cRuns = if (rich) cur.u16() else 0
                val cbExt = if (ext) cur.i32() else 0

                val sb = StringBuilder(cch)
                var left = cch
                while (left > 0) {
                    if (cur.atBlockEnd()) {
                        if (!cur.nextBlock()) throw IndexOutOfBoundsException()
                        // 跨记录续传时前置一个新的宽窄标记字节
                        grbit = cur.byte()
                        wide = grbit and 1 != 0
                    }
                    val step = if (wide) 2 else 1
                    val avail = cur.availInBlock() / step
                    if (avail <= 0) { cur.skipBlockTail(); continue }
                    val n = minOf(left, avail)
                    val seg = cur.take(n * step)
                    sb.append(
                        if (wide) String(seg, Charsets.UTF_16LE)
                        else String(seg, Charsets.ISO_8859_1)
                    )
                    left -= n
                }
                if (cRuns > 0) cur.skip(4 * cRuns)
                if (cbExt > 0) cur.skip(cbExt)
                out.add(sb.toString())
            }
        } catch (e: Exception) {
            // SST 损坏时保留已解析部分，后续单元格取不到就留空
        }
        return out
    }

    private class BlockCursor(val blocks: List<ByteArray>) {
        var bi = 0
        var p = 0

        fun atBlockEnd(): Boolean = bi >= blocks.size || p >= blocks[bi].size

        fun nextBlock(): Boolean {
            bi++; p = 0
            return bi < blocks.size
        }

        private fun ensure() {
            while (atBlockEnd()) if (!nextBlock()) throw IndexOutOfBoundsException("SST 数据提前结束")
        }

        fun availInBlock(): Int = if (bi < blocks.size) blocks[bi].size - p else 0
        fun skipBlockTail() { if (bi < blocks.size) p = blocks[bi].size }

        fun byte(): Int {
            ensure()
            return blocks[bi][p++].toInt() and 0xFF
        }

        fun u16(): Int = byte() or (byte() shl 8)
        fun i32(): Int = u16() or (u16() shl 16)

        fun take(n: Int): ByteArray {
            val out = ByteArray(n)
            var filled = 0
            while (filled < n) {
                ensure()
                val chunk = minOf(n - filled, blocks[bi].size - p)
                System.arraycopy(blocks[bi], p, out, filled, chunk)
                p += chunk; filled += chunk
            }
            return out
        }

        fun skip(n: Int) { take(n) }
    }

    // ---------- 单元格 ----------
    private fun readCells(recs: List<Record>, from: Int, sst: List<String>): List<List<String>> {
        val cells = HashMap<Long, String>()
        var maxRow = -1
        var maxCol = -1

        fun put(row: Int, col: Int, value: String) {
            if (row < 0 || col < 0 || value.isEmpty()) return
            cells[key(row, col)] = value
            if (row > maxRow) maxRow = row
            if (col > maxCol) maxCol = col
        }

        var i = from
        while (i < recs.size) {
            val r = recs[i]
            if (r.type == R_EOF) break
            val d = r.data
            try {
                when (r.type) {
                    R_LABELSST -> if (d.size >= 10) {
                        val si = le32(d, 6)
                        if (si in sst.indices) put(le16(d, 0), le16(d, 2), sst[si])
                    }

                    R_LABEL -> if (d.size >= 9) {
                        val cch = le16(d, 6)
                        val grbit = d[8].toInt() and 0xFF
                        val s = if (grbit and 1 != 0)
                            String(d, 9, minOf(cch * 2, d.size - 9), Charsets.UTF_16LE)
                        else
                            String(d, 9, minOf(cch, d.size - 9), Charsets.ISO_8859_1)
                        put(le16(d, 0), le16(d, 2), s)
                    }

                    R_NUMBER -> if (d.size >= 14) {
                        put(le16(d, 0), le16(d, 2), formatDouble(readDouble(d, 6)))
                    }

                    R_RK -> if (d.size >= 10) {
                        put(le16(d, 0), le16(d, 2), formatDouble(rkToDouble(le32(d, 6))))
                    }

                    R_MULRK -> if (d.size >= 6) {
                        val row = le16(d, 0)
                        val col0 = le16(d, 2)
                        val n = (d.size - 6) / 6
                        for (k in 0 until n) {
                            val rk = le32(d, 4 + k * 6 + 2)
                            put(row, col0 + k, formatDouble(rkToDouble(rk)))
                        }
                    }

                    R_FORMULA -> if (d.size >= 14) {
                        val row = le16(d, 0)
                        val col = le16(d, 2)
                        // 结果为字符串时，紧随其后的 STRING 记录带值
                        val isStringResult = (d[6].toInt() and 0xFF) == 0 &&
                                le16(d, 12) == 0xFFFF
                        if (isStringResult) {
                            var j = i + 1
                            while (j < recs.size && recs[j].type == R_CONTINUE) j++
                            if (j < recs.size && recs[j].type == R_STRING) {
                                val sd = recs[j].data
                                if (sd.size >= 3) {
                                    val cch = le16(sd, 0)
                                    val grbit = sd[2].toInt() and 0xFF
                                    val s = if (grbit and 1 != 0)
                                        String(sd, 3, minOf(cch * 2, sd.size - 3), Charsets.UTF_16LE)
                                    else
                                        String(sd, 3, minOf(cch, sd.size - 3), Charsets.ISO_8859_1)
                                    put(row, col, s)
                                }
                            }
                        } else if (le16(d, 12) != 0xFFFF) {
                            put(row, col, formatDouble(readDouble(d, 6)))
                        }
                    }

                    R_BOOLERR -> if (d.size >= 8) {
                        val isError = (d[7].toInt() and 0xFF) == 1
                        if (!isError) {
                            put(le16(d, 0), le16(d, 2),
                                if ((d[6].toInt() and 0xFF) != 0) "TRUE" else "FALSE")
                        }
                    }
                }
            } catch (e: Exception) {
                // 单条记录异常不影响整体解析
            }
            i++
        }

        if (maxRow < 0) return emptyList()
        return (0..maxRow).map { row ->
            (0..maxCol).map { col -> cells[key(row, col)].orEmpty() }
        }
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()

    private fun readDouble(b: ByteArray, off: Int): Double {
        var bits = 0L
        for (i in 0 until 8) bits = bits or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return Double.fromBits(bits)
    }

    /** RK 编码：低 2 位是标记位，其余为 30 位整数或 double 高位 */
    private fun rkToDouble(rk: Int): Double {
        val div100 = rk and 1 != 0
        val isInt = rk and 2 != 0
        val v = if (isInt) {
            (rk shr 2).toDouble()
        } else {
            val bits = (rk.toLong() and 0xFFFFFFFCL) shl 32
            Double.fromBits(bits)
        }
        return if (div100) v / 100.0 else v
    }

    /** 数值转文本：整数不带小数点，浮点去掉多余的 0 */
    private fun formatDouble(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        if (kotlin.math.abs(v - Math.round(v)) < 1e-9) return Math.round(v).toString()
        var s = String.format("%.6f", v).trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return s
    }
}
