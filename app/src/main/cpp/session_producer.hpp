#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "audio_pcm_pipeline.hpp"
#include "nal_parser.hpp"
#include "shared_ring_buffer.hpp"

namespace tvplayair {

constexpr size_t kMaxVideoPacketBytes = 480 * 1024;
constexpr size_t kMaxVideoPayloadBytes = kMaxVideoPacketBytes - sizeof(VideoFramePacketHeader);

SharedRingBuffer& producerVideoRing();
SharedRingBuffer& producerAudioRing();

// Zero-allocation producer-side packetizer for :session -> SharedRingBuffer.
class SessionProducer final {
public:
    RingResult writeVideoFrame(
        SharedRingBuffer& videoRing,
        uint64_t ptsUs,
        const uint8_t* data,
        size_t size,
        bool isHevc,
        bool isLengthPrefixed,
        uint32_t generationId,
        uint32_t extraFlags = 0u) {
        if (!data || size == 0) return RingResult::Invalid;

        uint32_t detectedFlags = 0u;
        size_t annexBSize = 0;
        uint8_t* payloadDst = videoStaging_.data() + sizeof(VideoFramePacketHeader);

        if (isLengthPrefixed) {
            annexBSize = convertLengthPrefixedToAnnexB(
                data, size, payloadDst, kMaxVideoPayloadBytes, isHevc, &detectedFlags);
            if (annexBSize == 0) {
                return size > kMaxVideoPayloadBytes ? RingResult::Oversized : RingResult::Invalid;
            }
        } else if ((extraFlags & kVideoFlagCodecConfig) != 0 && data[0] == 0x01) {
            annexBSize = convertDecoderConfigToAnnexB(
                data, size, payloadDst, kMaxVideoPayloadBytes, isHevc);
            if (annexBSize == 0) {
                return size > kMaxVideoPayloadBytes ? RingResult::Oversized : RingResult::Invalid;
            }
            detectedFlags = kVideoFlagCodecConfig;
        } else {
            if (size > kMaxVideoPayloadBytes) {
                return RingResult::Oversized;
            }
            std::memcpy(payloadDst, data, size);
            annexBSize = size;
            detectedFlags = classifyAnnexB(payloadDst, annexBSize, isHevc);
        }

        VideoFramePacketHeader header{};
        header.ptsUs = ptsUs;
        header.flags = detectedFlags | extraFlags;
        header.payloadSize = static_cast<uint32_t>(annexBSize);
        std::memcpy(videoStaging_.data(), &header, sizeof(header));

        return videoRing.writePacket(
            videoStaging_.data(),
            static_cast<uint32_t>(sizeof(header) + annexBSize),
            generationId);
    }

    // Writes arbitrary PCM 16-bit/48kHz stereo byte buffers in frame-aligned <=4096-byte packets.
    // Returns number of bytes successfully enqueued (non-blocking: stops on Full).
    size_t writeAudioPcm(
        SharedRingBuffer& audioRing,
        const uint8_t* data,
        size_t byteCount,
        uint32_t generationId,
        int64_t senderTimeUs = 0) {
        if (!data || byteCount < kAudioBytesPerFrame) return 0;

        const size_t alignedTotal = byteCount - (byteCount % kAudioBytesPerFrame);
        size_t offset = 0;
        while (offset < alignedTotal) {
            size_t chunk = alignedTotal - offset;
            if (chunk > kMaxAudioPacketBytes) {
                chunk = kMaxAudioPacketBytes - (kMaxAudioPacketBytes % kAudioBytesPerFrame);
            }
            const RingResult res = audioRing.writePacket(
                data + offset, static_cast<uint32_t>(chunk), generationId,
                senderTimeUs > 0 ? senderTimeUs +
                    static_cast<int64_t>(offset / kAudioBytesPerFrame) * 1000000 / kAudioSampleRate : 0);
            if (res != RingResult::Ok) {
                break;
            }
            offset += chunk;
        }
        return offset;
    }

private:
    std::array<uint8_t, kMaxVideoPacketBytes> videoStaging_{};
};

} // namespace tvplayair
