#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace tvplayair {

constexpr uint32_t kVideoFlagCodecConfig = 1u << 0;
constexpr uint32_t kVideoFlagKeyFrame = 1u << 1;

struct VideoFramePacketHeader {
    uint64_t ptsUs;
    uint32_t flags;
    uint32_t payloadSize;
};

static_assert(sizeof(VideoFramePacketHeader) == 16,
              "VideoFramePacketHeader must be exactly 16 bytes");

inline uint32_t classifyNalByte(uint8_t nalHeader0, bool isHevc) {
    if (isHevc) {
        const uint8_t nalType = static_cast<uint8_t>((nalHeader0 >> 1) & 0x3Fu);
        if (nalType == 32u || nalType == 33u || nalType == 34u) {
            return kVideoFlagCodecConfig; // VPS / SPS / PPS
        }
        if (nalType == 19u || nalType == 20u || nalType == 21u) {
            return kVideoFlagKeyFrame; // IDR_W_RADL / IDR_N_LP / CRA_NUT
        }
        return 0u;
    }

    const uint8_t nalType = static_cast<uint8_t>(nalHeader0 & 0x1Fu);
    if (nalType == 7u || nalType == 8u) {
        return kVideoFlagCodecConfig; // SPS / PPS
    }
    if (nalType == 5u) {
        return kVideoFlagKeyFrame; // IDR slice
    }
    return 0u;
}

inline uint32_t classifyAnnexB(const uint8_t* data, size_t size, bool isHevc) {
    if (!data || size < 4) return 0u;
    uint32_t flags = 0u;
    size_t i = 0;
    while (i + 3 < size) {
        size_t startCodeLen = 0;
        if (data[i] == 0x00 && data[i + 1] == 0x00) {
            if (data[i + 2] == 0x01) {
                startCodeLen = 3;
            } else if (i + 4 < size && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                startCodeLen = 4;
            }
        }
        if (startCodeLen > 0) {
            const size_t nalIdx = i + startCodeLen;
            if (nalIdx < size) {
                flags |= classifyNalByte(data[nalIdx], isHevc);
            }
            i = nalIdx + 1;
        } else {
            ++i;
        }
    }
    return flags;
}

// Converts 4-byte big-endian length-prefixed AVCC/HVCC NAL units (AirPlay Mirroring format)
// into Annex-B (00 00 00 01) directly into caller-provided storage without heap allocations.
// Returns total written bytes, or 0 if input is malformed or exceeds dstCapacity.
inline size_t convertLengthPrefixedToAnnexB(
    const uint8_t* src,
    size_t srcSize,
    uint8_t* dst,
    size_t dstCapacity,
    bool isHevc,
    uint32_t* outFlags) {
    if (!src || !dst || srcSize < 5 || !outFlags) return 0;

    size_t srcPos = 0;
    size_t dstPos = 0;
    uint32_t flags = 0u;

    while (srcPos + 4 <= srcSize) {
        const uint32_t nalLen =
            (static_cast<uint32_t>(src[srcPos]) << 24) |
            (static_cast<uint32_t>(src[srcPos + 1]) << 16) |
            (static_cast<uint32_t>(src[srcPos + 2]) << 8) |
            static_cast<uint32_t>(src[srcPos + 3]);
        srcPos += 4;

        if (nalLen == 0 || nalLen > srcSize - srcPos) {
            return 0;
        }
        if (dstPos + 4 + nalLen > dstCapacity) {
            return 0;
        }

        dst[dstPos] = 0x00;
        dst[dstPos + 1] = 0x00;
        dst[dstPos + 2] = 0x00;
        dst[dstPos + 3] = 0x01;
        dstPos += 4;

        flags |= classifyNalByte(src[srcPos], isHevc);
        std::memcpy(dst + dstPos, src + srcPos, nalLen);
        dstPos += nalLen;
        srcPos += nalLen;
    }

    if (srcPos != srcSize || dstPos == 0) {
        return 0;
    }
    *outFlags = flags;
    return dstPos;
}

// Converts AVCDecoderConfigurationRecord (avcC) or HEVCDecoderConfigurationRecord (hvcC)
// received in AirPlay Mirroring payloadType=1 into Annex-B (00 00 00 01) SPS/PPS/VPS.
inline size_t convertDecoderConfigToAnnexB(
    const uint8_t* src,
    size_t srcSize,
    uint8_t* dst,
    size_t dstCapacity,
    bool isHevc) {
    if (!src || !dst || srcSize < 7) return 0;

    size_t dstPos = 0;
    auto appendNal = [&](const uint8_t* nal, size_t nalLen) -> bool {
        if (nalLen == 0 || dstPos + 4 + nalLen > dstCapacity) return false;
        dst[dstPos] = 0x00;
        dst[dstPos + 1] = 0x00;
        dst[dstPos + 2] = 0x00;
        dst[dstPos + 3] = 0x01;
        std::memcpy(dst + dstPos + 4, nal, nalLen);
        dstPos += 4 + nalLen;
        return true;
    };

    if (!isHevc) {
        // avcC: version(1) profile(1) compat(1) level(1) lenSizeMinus1(1) numSps(1)
        if (src[0] != 0x01) return 0;
        size_t pos = 5;
        const uint8_t numSps = src[pos++] & 0x1Fu;
        for (uint8_t i = 0; i < numSps; ++i) {
            if (pos + 2 > srcSize) return 0;
            const uint16_t len = (static_cast<uint16_t>(src[pos]) << 8) | src[pos + 1];
            pos += 2;
            if (pos + len > srcSize || !appendNal(src + pos, len)) return 0;
            pos += len;
        }
        if (pos >= srcSize) return dstPos;
        const uint8_t numPps = src[pos++];
        for (uint8_t i = 0; i < numPps; ++i) {
            if (pos + 2 > srcSize) return 0;
            const uint16_t len = (static_cast<uint16_t>(src[pos]) << 8) | src[pos + 1];
            pos += 2;
            if (pos + len > srcSize || !appendNal(src + pos, len)) return 0;
            pos += len;
        }
        return dstPos;
    }

    // hvcC: 22-byte header + numOfArrays(1)
    if (srcSize < 23 || src[0] != 0x01) return 0;
    size_t pos = 22;
    const uint8_t numArrays = src[pos++];
    for (uint8_t a = 0; a < numArrays; ++a) {
        if (pos + 3 > srcSize) return 0;
        pos += 1; // array_completeness + NAL_unit_type
        const uint16_t numNalus = (static_cast<uint16_t>(src[pos]) << 8) | src[pos + 1];
        pos += 2;
        for (uint16_t n = 0; n < numNalus; ++n) {
            if (pos + 2 > srcSize) return 0;
            const uint16_t len = (static_cast<uint16_t>(src[pos]) << 8) | src[pos + 1];
            pos += 2;
            if (pos + len > srcSize || !appendNal(src + pos, len)) return 0;
            pos += len;
        }
    }
    return dstPos;
}

} // namespace tvplayair
