package com.uyatame.ucrt.airplay

import java.io.ByteArrayOutputStream

/**
 * 最小限のバイナリ plist (bplist00) 読み書き。
 * AirPlay の /info 応答と SETUP のやり取りに必要な型だけを扱う:
 * Map<String, Any?> / List<Any?> / ByteArray / String / Boolean / Long / Double / null。
 * 書き出しは GoAirPlay で使った howett.net/plist の出力と等価な構造にする。
 */
internal object Bplist {

    // ---------- 書き出し ----------

    fun write(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val ids = HashMap<Any, Int>()
        fun intern(o: Any?): Int {
            val key = keyOf(o)
            ids[key]?.let { return it }
            val id = objects.size
            objects.add(o)
            ids[key] = id
            when (o) {
                is Map<*, *> -> {
                    for ((k, v) in o) {
                        intern(k as String)
                        intern(v)
                    }
                }
                is List<*> -> for (v in o) intern(v)
            }
            return id
        }
        val topId = intern(root)

        val refSize = bytesNeeded(objects.size - 1)
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        for ((i, o) in objects.withIndex()) {
            offsets[i] = body.size()
            writeObject(body, o, refSize, ids)
        }
        val offsetTablePos = body.size()
        val offsetSize = bytesNeeded(offsetTablePos)
        for (off in offsets) writeSizedInt(body, off.toLong(), offsetSize)

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        putBe64(trailer, 8, objects.size.toLong())
        putBe64(trailer, 16, topId.toLong())
        putBe64(trailer, 24, offsetTablePos.toLong())
        body.write(trailer)
        return body.toByteArray()
    }

    private class ByteKey(val b: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && b.contentEquals(other.b)
        override fun hashCode(): Int = b.contentHashCode()
    }

    private fun bytesNeeded(v: Int): Int = when {
        v < 0x100 -> 1
        v < 0x10000 -> 2
        v < 0x1000000 -> 3
        else -> 4
    }

    private fun intWidth(v: Long): Int = when {
        v in 0..0xFF -> 1
        v in 0..0xFFFF -> 2
        v in 0..0xFFFF_FFFFL -> 4
        else -> 8
    }

    private fun writeSizedInt(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    private fun writeLength(out: ByteArrayOutputStream, markerBase: Int, len: Int) {
        if (len < 15) {
            out.write(markerBase or len)
        } else {
            out.write(markerBase or 0x0F)
            val w = intWidth(len.toLong())
            out.write(0x10 or (when (w) {
                1 -> 0
                2 -> 1
                4 -> 2
                else -> 3
            }))
            writeSizedInt(out, len.toLong(), w)
        }
    }

    private fun keyOf(o: Any?): Any = when (o) {
        null -> NullKey
        is ByteArray -> ByteKey(o)
        is Int -> o.toLong()
        else -> o
    }

    private fun writeObject(out: ByteArrayOutputStream, o: Any?, refSize: Int, ids: Map<Any, Int>) {
        when (o) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (o) 0x09 else 0x08)
            is Long -> {
                val w = intWidth(o)
                out.write(0x10 or (when (w) {
                    1 -> 0
                    2 -> 1
                    4 -> 2
                    else -> 3
                }))
                writeSizedInt(out, o, w)
            }
            is Int -> writeObject(out, o.toLong(), refSize, ids)
            is Double -> {
                out.write(0x23)
                val bits = o.toRawBits()
                for (i in 7 downTo 0) out.write((bits ushr (8 * i)).toInt() and 0xFF)
            }
            is ByteArray -> {
                writeLength(out, 0x40, o.size)
                out.write(o)
            }
            is String -> {
                if (o.all { it.code < 0x80 }) {
                    writeLength(out, 0x50, o.length)
                    out.write(o.toByteArray(Charsets.US_ASCII))
                } else {
                    writeLength(out, 0x60, o.length)
                    for (c in o) {
                        out.write((c.code ushr 8) and 0xFF)
                        out.write(c.code and 0xFF)
                    }
                }
            }
            is List<*> -> {
                writeLength(out, 0xA0, o.size)
                for (v in o) writeSizedInt(out, (ids[keyOf(v)] ?: error("not interned: $v")).toLong(), refSize)
            }
            is Map<*, *> -> {
                writeLength(out, 0xD0, o.size)
                for (k in o.keys) writeSizedInt(out, (ids[keyOf(k as String)] ?: error("not interned: $k")).toLong(), refSize)
                for (v in o.values) writeSizedInt(out, (ids[keyOf(v)] ?: error("not interned: $v")).toLong(), refSize)
            }
            else -> throw IllegalArgumentException("unsupported plist type: ${o.javaClass}")
        }
    }

    private object NullKey

    // ---------- 読み込み ----------

    fun read(data: ByteArray): Any? {
        require(data.size > 32 && data.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            "not a bplist00"
        }
        val trailer = data.copyOfRange(data.size - 32, data.size)
        val offsetSize = trailer[6].toInt() and 0xFF
        val refSize = trailer[7].toInt() and 0xFF
        val numObjects = be64(trailer, 8).toInt()
        val topId = be64(trailer, 16).toInt()
        val offsetTablePos = be64(trailer, 24).toInt()
        val offsets = IntArray(numObjects) { i ->
            var v = 0
            for (j in 0 until offsetSize) v = (v shl 8) or (data[offsetTablePos + i * offsetSize + j].toInt() and 0xFF)
            v
        }
        return parseObject(data, offsets, topId, refSize)
    }

    private fun parseObject(data: ByteArray, offsets: IntArray, id: Int, refSize: Int): Any? {
        var pos = offsets[id]
        val marker = data[pos].toInt() and 0xFF
        pos++
        val type = marker and 0xF0
        val info = marker and 0x0F
        fun length(): Int {
            if (info < 15) return info
            val m2 = data[pos].toInt() and 0xFF
            pos++
            val n = 1 shl (m2 and 0x0F)
            var v = 0
            for (j in 0 until n) {
                v = (v shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
            return v
        }
        fun readInt(n: Int): Long {
            var v = 0L
            for (j in 0 until n) {
                v = (v shl 8) or (data[pos].toLong() and 0xFF)
                pos++
            }
            return v
        }
        fun readRef(): Int {
            var v = 0
            for (j in 0 until refSize) {
                v = (v shl 8) or (data[pos].toInt() and 0xFF)
                pos++
            }
            return v
        }
        return when (type) {
            0x00 -> when (info) {
                0x08 -> false
                0x09 -> true
                else -> null
            }
            0x10 -> readInt(1 shl info)
            0x20 -> {
                val n = 1 shl info
                if (n == 8) {
                    var bits = 0L
                    for (j in 0 until 8) {
                        bits = (bits shl 8) or (data[pos].toLong() and 0xFF)
                        pos++
                    }
                    Double.fromBits(bits)
                } else {
                    var bits = 0
                    for (j in 0 until n) {
                        bits = (bits shl 8) or (data[pos].toInt() and 0xFF)
                        pos++
                    }
                    Float.fromBits(bits).toDouble()
                }
            }
            0x40 -> {
                val len = length()
                val out = data.copyOfRange(pos, pos + len)
                out
            }
            0x50 -> {
                val len = length()
                String(data, pos, len, Charsets.US_ASCII)
            }
            0x60 -> {
                val len = length()
                val chars = CharArray(len)
                for (j in 0 until len) {
                    chars[j] = (((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)).toChar()
                    pos += 2
                }
                String(chars)
            }
            0xA0 -> {
                val len = length()
                val refs = IntArray(len) { readRef() }
                refs.map { parseObject(data, offsets, it, refSize) }
            }
            0xD0 -> {
                val len = length()
                val keyRefs = IntArray(len) { readRef() }
                val valRefs = IntArray(len) { readRef() }
                val map = LinkedHashMap<String, Any?>()
                for (j in 0 until len) {
                    val k = parseObject(data, offsets, keyRefs[j], refSize) as String
                    map[k] = parseObject(data, offsets, valRefs[j], refSize)
                }
                map
            }
            else -> throw IllegalArgumentException("unsupported plist object type 0x${type.toString(16)}")
        }
    }

    private fun be64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (j in 0 until 8) v = (v shl 8) or (b[off + j].toLong() and 0xFF)
        return v
    }

    private fun putBe64(b: ByteArray, off: Int, v: Long) {
        for (j in 0 until 8) b[off + j] = (v ushr (8 * (7 - j))).toByte()
    }
}
