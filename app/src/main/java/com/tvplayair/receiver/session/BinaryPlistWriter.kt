package com.tvplayair.receiver.session

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Compact Apple binary property list (bplist00) encoder for RTSP GET /info and SETUP responses.
 */
object BinaryPlistWriter {
    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    fun encode(root: Any): ByteArray {
        val objects = ArrayList<Any>()
        flatten(root, objects)

        val refSize = when {
            objects.size < 256 -> 1
            objects.size < 65536 -> 2
            else -> 4
        }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)

        val offsets = IntArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = out.size()
            writeObject(out, objects[i], objects, refSize)
        }

        val offsetTableOffset = out.size().toLong()
        val offsetIntSize = when {
            offsetTableOffset < 256L -> 1
            offsetTableOffset < 65536L -> 2
            else -> 4
        }
        for (off in offsets) {
            writeUnsignedInt(out, off.toLong(), offsetIntSize)
        }

        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put(ByteArray(6))
            put(offsetIntSize.toByte())
            put(refSize.toByte())
            putLong(objects.size.toLong())
            putLong(0L) // topObject index = 0
            putLong(offsetTableOffset)
        }.array()
        out.write(trailer)
        return out.toByteArray()
    }

    private fun flatten(obj: Any, list: MutableList<Any>): Int {
        val idx = list.size
        list.add(obj)
        when (obj) {
            is Map<*, *> -> {
                for (k in obj.keys) if (k != null) flatten(k.toString(), list)
                for (v in obj.values) if (v != null) flatten(v, list)
            }
            is List<*> -> {
                for (item in obj) if (item != null) flatten(item, list)
            }
        }
        return idx
    }

    private fun writeObject(
        out: ByteArrayOutputStream,
        obj: Any,
        allObjects: List<Any>,
        refSize: Int
    ) {
        when (obj) {
            is Boolean -> out.write(if (obj) 0x09 else 0x08)
            is Int -> writeIntObject(out, obj.toLong())
            is Long -> writeIntObject(out, obj)
            is Double -> {
                out.write(0x23) // 8-byte IEEE 754 double
                val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(obj)
                out.write(bb.array())
            }
            is ByteArray -> {
                writeLengthMarker(out, 0x4, obj.size)
                out.write(obj)
            }
            is String -> {
                if (obj.all { it.code <= 0x7F }) {
                    val ascii = obj.toByteArray(StandardCharsets.US_ASCII)
                    writeLengthMarker(out, 0x5, ascii.size)
                    out.write(ascii)
                } else {
                    val utf16 = obj.toByteArray(StandardCharsets.UTF_16BE)
                    writeLengthMarker(out, 0x6, utf16.size / 2)
                    out.write(utf16)
                }
            }
            is List<*> -> {
                val items = obj.filterNotNull()
                writeLengthMarker(out, 0xA, items.size)
                for (item in items) {
                    writeUnsignedInt(out, indexOfIdentity(allObjects, item).toLong(), refSize)
                }
            }
            is Map<*, *> -> {
                val entries = obj.entries.filter { it.key != null && it.value != null }
                writeLengthMarker(out, 0xD, entries.size)
                for (e in entries) {
                    val keyStr = e.key.toString()
                    writeUnsignedInt(out, indexOfEqualString(allObjects, keyStr).toLong(), refSize)
                }
                for (e in entries) {
                    writeUnsignedInt(out, indexOfIdentity(allObjects, e.value!!).toLong(), refSize)
                }
            }
            else -> out.write(0x00)
        }
    }

    private fun indexOfIdentity(list: List<Any>, target: Any): Int {
        for (i in list.indices) {
            if (list[i] === target) return i
        }
        return list.indexOf(target).coerceAtLeast(0)
    }

    private fun indexOfEqualString(list: List<Any>, target: String): Int {
        for (i in list.indices) {
            val item = list[i]
            if (item is String && item == target) return i
        }
        return 0
    }

    private fun writeIntObject(out: ByteArrayOutputStream, value: Long) {
        when {
            value in 0..0xFFL -> {
                out.write(0x10)
                out.write(value.toInt())
            }
            value in 0..0xFFFFL -> {
                out.write(0x11)
                writeUnsignedInt(out, value, 2)
            }
            value in 0..0xFFFFFFFFL -> {
                out.write(0x12)
                writeUnsignedInt(out, value, 4)
            }
            else -> {
                out.write(0x13)
                writeUnsignedInt(out, value, 8)
            }
        }
    }

    private fun writeLengthMarker(out: ByteArrayOutputStream, typeNibble: Int, len: Int) {
        if (len < 15) {
            out.write((typeNibble shl 4) or len)
        } else {
            out.write((typeNibble shl 4) or 0x0F)
            writeIntObject(out, len.toLong())
        }
    }

    private fun writeUnsignedInt(out: ByteArrayOutputStream, value: Long, byteCount: Int) {
        for (shift in (byteCount - 1) downTo 0) {
            out.write(((value ushr (shift * 8)) and 0xFFL).toInt())
        }
    }
}
