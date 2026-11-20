package com.tvplayair.receiver.session

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Bounded Apple binary property list (bplist00) parser for RTSP SETUP / AirPlay requests.
 * Supports dicts, arrays, ASCII/UTF-16 strings, integers, reals, booleans and raw data blobs.
 */
object BinaryPlistParser {
    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)
    private const val TRAILER_BYTES = 32
    private const val MAX_DEPTH = 16

    fun isBinaryPlist(data: ByteArray): Boolean {
        if (data.size < MAGIC.size + TRAILER_BYTES) return false
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return false
        }
        return true
    }

    fun parse(data: ByteArray): Any? {
        if (!isBinaryPlist(data)) return null
        val trailerOffset = data.size - TRAILER_BYTES
        val offsetIntSize = data[trailerOffset + 6].toInt() and 0xFF
        val objectRefSize = data[trailerOffset + 7].toInt() and 0xFF
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val numObjects = buf.getLong(trailerOffset + 8)
        val topObject = buf.getLong(trailerOffset + 16)
        val offsetTableOffset = buf.getLong(trailerOffset + 24)

        if (numObjects <= 0 || numObjects > 4096 || topObject < 0 || topObject >= numObjects) {
            return null
        }
        val offsetTableBytes = numObjects * offsetIntSize
        if (offsetTableOffset < MAGIC.size || offsetTableOffset + offsetTableBytes > trailerOffset) {
            return null
        }

        val offsets = IntArray(numObjects.toInt())
        for (i in 0 until numObjects.toInt()) {
            val pos = offsetTableOffset.toInt() + i * offsetIntSize
            val objOff = readUnsignedInt(data, pos, offsetIntSize)
            if (objOff < MAGIC.size || objOff >= offsetTableOffset) return null
            offsets[i] = objOff.toInt()
        }

        return parseObject(data, offsets, objectRefSize, topObject.toInt(), 0)
    }

    private fun parseObject(
        data: ByteArray,
        offsets: IntArray,
        refSize: Int,
        objIndex: Int,
        depth: Int
    ): Any? {
        if (depth > MAX_DEPTH || objIndex !in offsets.indices) return null
        val offset = offsets[objIndex]
        if (offset >= data.size) return null

        val marker = data[offset].toInt() and 0xFF
        val type = (marker ushr 4) and 0x0F
        val info = marker and 0x0F

        return when (type) {
            0x0 -> when (info) {
                0x8 -> false
                0x9 -> true
                else -> null
            }
            0x1 -> { // Integer (2^info bytes)
                val byteCount = 1 shl info
                if (byteCount > 8 || offset + 1 + byteCount > data.size) return null
                readUnsignedInt(data, offset + 1, byteCount)
            }
            0x2 -> { // Real (4 or 8 bytes)
                val byteCount = 1 shl info
                if (offset + 1 + byteCount > data.size) return null
                val bb = ByteBuffer.wrap(data, offset + 1, byteCount).order(ByteOrder.BIG_ENDIAN)
                when (byteCount) {
                    4 -> bb.float.toDouble()
                    8 -> bb.double
                    else -> null
                }
            }
            0x4 -> { // Data blob
                val (len, start) = readLength(data, offset, info) ?: return null
                if (start + len > data.size) return null
                data.copyOfRange(start, start + len)
            }
            0x5 -> { // ASCII string
                val (len, start) = readLength(data, offset, info) ?: return null
                if (start + len > data.size) return null
                String(data, start, len, StandardCharsets.US_ASCII)
            }
            0x6 -> { // UTF-16BE string
                val (charCount, start) = readLength(data, offset, info) ?: return null
                val byteLen = charCount * 2
                if (start + byteLen > data.size) return null
                String(data, start, byteLen, StandardCharsets.UTF_16BE)
            }
            0xA -> { // Array
                val (count, start) = readLength(data, offset, info) ?: return null
                if (count > 512 || start + count * refSize > data.size) return null
                val list = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val ref = readUnsignedInt(data, start + i * refSize, refSize).toInt()
                    list.add(parseObject(data, offsets, refSize, ref, depth + 1))
                }
                list
            }
            0xD -> { // Dict
                val (count, start) = readLength(data, offset, info) ?: return null
                if (count > 512 || start + count * 2 * refSize > data.size) return null
                val map = LinkedHashMap<String, Any?>(count)
                val valuesStart = start + count * refSize
                for (i in 0 until count) {
                    val keyRef = readUnsignedInt(data, start + i * refSize, refSize).toInt()
                    val valRef = readUnsignedInt(data, valuesStart + i * refSize, refSize).toInt()
                    val key = parseObject(data, offsets, refSize, keyRef, depth + 1)?.toString()
                        ?: continue
                    map[key] = parseObject(data, offsets, refSize, valRef, depth + 1)
                }
                map
            }
            else -> null
        }
    }

    private fun readLength(data: ByteArray, markerOffset: Int, info: Int): Pair<Int, Int>? {
        if (info < 0x0F) return info to (markerOffset + 1)
        val intOffset = markerOffset + 1
        if (intOffset >= data.size) return null
        val intMarker = data[intOffset].toInt() and 0xFF
        if (((intMarker ushr 4) and 0x0F) != 0x1) return null
        val byteCount = 1 shl (intMarker and 0x0F)
        if (byteCount > 4 || intOffset + 1 + byteCount > data.size) return null
        val len = readUnsignedInt(data, intOffset + 1, byteCount).toInt()
        if (len < 0) return null
        return len to (intOffset + 1 + byteCount)
    }

    private fun readUnsignedInt(data: ByteArray, offset: Int, byteCount: Int): Long {
        var value = 0L
        for (i in 0 until byteCount) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
