#include "session_producer.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <linux/memfd.h>
#include <unistd.h>
#include <vector>

using tvplayair::AudioPcmPipeline;
using tvplayair::RingResult;
using tvplayair::SessionProducer;
using tvplayair::SharedRingBuffer;
using tvplayair::VideoFramePacketHeader;
using tvplayair::kAudioBytesPerFrame;
using tvplayair::kAudioChannels;
using tvplayair::kVideoFlagCodecConfig;
using tvplayair::kVideoFlagKeyFrame;

static int makeSharedFd(size_t bytes) {
    const int fd = ::memfd_create("tvplayair-producer-test", MFD_CLOEXEC);
    assert(fd >= 0);
    assert(::ftruncate(fd, static_cast<off_t>(bytes)) == 0);
    return fd;
}

static void testHevcAndAvcClassification() {
    // HEVC: VPS (32 << 1 = 0x40), SPS (33 << 1 = 0x42), PPS (34 << 1 = 0x44), IDR_W_RADL (19 << 1 = 0x26)
    assert(tvplayair::classifyNalByte(0x40, true) == kVideoFlagCodecConfig);
    assert(tvplayair::classifyNalByte(0x42, true) == kVideoFlagCodecConfig);
    assert(tvplayair::classifyNalByte(0x44, true) == kVideoFlagCodecConfig);
    assert(tvplayair::classifyNalByte(0x26, true) == kVideoFlagKeyFrame);
    assert(tvplayair::classifyNalByte(0x02, true) == 0u); // TRAIL_R (type 1)

    // H.264: SPS (0x67), PPS (0x68), IDR (0x65), non-IDR (0x41)
    assert(tvplayair::classifyNalByte(0x67, false) == kVideoFlagCodecConfig);
    assert(tvplayair::classifyNalByte(0x68, false) == kVideoFlagCodecConfig);
    assert(tvplayair::classifyNalByte(0x65, false) == kVideoFlagKeyFrame);
    assert(tvplayair::classifyNalByte(0x41, false) == 0u);
}

static void testHvccLengthPrefixedToAnnexBRoundTrip() {
    constexpr uint32_t capacity = 8;
    constexpr uint32_t payload = 64 * 1024;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    SharedRingBuffer videoRing;
    assert(videoRing.mapFd(makeSharedFd(bytes), bytes, true, capacity, payload));

    // Build HVCC 4-byte length-prefixed packet containing VPS (3 bytes) + IDR_W_RADL (4 bytes).
    const std::array<uint8_t, 15> hvcc = {
        0x00, 0x00, 0x00, 0x03, 0x40, 0x01, 0x0C,             // VPS (type 32)
        0x00, 0x00, 0x00, 0x04, 0x26, 0x01, 0xAF, 0x99        // IDR_W_RADL (type 19)
    };

    SessionProducer producer;
    assert(producer.writeVideoFrame(
        videoRing, 12345678ULL, hvcc.data(), hvcc.size(), true, true, 7) == RingResult::Ok);

    std::array<uint8_t, 256> raw{};
    uint32_t outSize = 0;
    assert(videoRing.readPacket(raw.data(), raw.size(), &outSize, 7) == RingResult::Ok);
    assert(outSize == sizeof(VideoFramePacketHeader) + hvcc.size());

    VideoFramePacketHeader hdr{};
    std::memcpy(&hdr, raw.data(), sizeof(hdr));
    assert(hdr.ptsUs == 12345678ULL);
    assert(hdr.flags == (kVideoFlagCodecConfig | kVideoFlagKeyFrame));
    assert(hdr.payloadSize == hvcc.size());

    // Verify Annex-B 4-byte start codes replaced the 4-byte HVCC lengths.
    const uint8_t* annexB = raw.data() + sizeof(VideoFramePacketHeader);
    assert(annexB[0] == 0x00 && annexB[1] == 0x00 && annexB[2] == 0x00 && annexB[3] == 0x01);
    assert(annexB[4] == 0x40);
    assert(annexB[7] == 0x00 && annexB[8] == 0x00 && annexB[9] == 0x00 && annexB[10] == 0x01);
    assert(annexB[11] == 0x26);
}

static void testAudioPcmProducerToConsumerEndToEnd() {
    constexpr uint32_t capacity = 64;
    constexpr uint32_t payload = 4096;
    const size_t bytes = SharedRingBuffer::requiredBytes(capacity, payload);
    SharedRingBuffer audioRing;
    assert(audioRing.mapFd(makeSharedFd(bytes), bytes, true, capacity, payload));

    // 1500 stereo frames = 6000 bytes (spans 2 ring packets: 4096 + 1904 bytes).
    constexpr int32_t totalFrames = 1500;
    std::vector<int16_t> src(static_cast<size_t>(totalFrames) * kAudioChannels);
    for (size_t i = 0; i < src.size(); ++i) {
        src[i] = static_cast<int16_t>((i % 1000) - 500);
    }

    SessionProducer producer;
    const size_t written = producer.writeAudioPcm(
        audioRing,
        reinterpret_cast<const uint8_t*>(src.data()),
        src.size() * sizeof(int16_t),
        3);
    assert(written == static_cast<size_t>(totalFrames) * kAudioBytesPerFrame);

    AudioPcmPipeline consumer;
    consumer.setGenerationId(3);
    std::vector<int16_t> dst(src.size(), 0);
    consumer.render(audioRing, dst.data(), totalFrames);
    assert(dst == src);
    assert(consumer.underrunCount() == 0);
}

static void testAvccDecoderConfigToAnnexB() {
    // avcC: version=1, profile=0x64, compat=0x00, level=0x28, lenMinus1=0xFF, numSps=0xE1 (1)
    // SPS len=3 {0x67, 0x64, 0x00}, numPps=1, PPS len=2 {0x68, 0xEE}
    const std::array<uint8_t, 14> avcc = {
        0x01, 0x64, 0x00, 0x28, 0xFF, 0xE1,
        0x00, 0x03, 0x67, 0x64, 0x00,
        0x01, 0x00, 0x02
    };
    std::array<uint8_t, 16> avccFull{};
    std::memcpy(avccFull.data(), avcc.data(), avcc.size());
    avccFull[14] = 0x68;
    avccFull[15] = 0xEE;

    std::array<uint8_t, 64> out{};
    const size_t written = tvplayair::convertDecoderConfigToAnnexB(
        avccFull.data(), avccFull.size(), out.data(), out.size(), false);
    assert(written == 13); // 4 + 3 + 4 + 2
    assert(out[0] == 0x00 && out[1] == 0x00 && out[2] == 0x00 && out[3] == 0x01);
    assert(out[4] == 0x67 && out[5] == 0x64 && out[6] == 0x00);
    assert(out[7] == 0x00 && out[8] == 0x00 && out[9] == 0x00 && out[10] == 0x01);
    assert(out[11] == 0x68 && out[12] == 0xEE);
}

int main() {
    testHevcAndAvcClassification();
    testHvccLengthPrefixedToAnnexBRoundTrip();
    testAudioPcmProducerToConsumerEndToEnd();
    testAvccDecoderConfigToAnnexB();
    std::puts("NAL parser and SessionProducer host tests passed: HEVC/AVC, HVCC/avcC->Annex-B, and audio PCM end-to-end.");
}
