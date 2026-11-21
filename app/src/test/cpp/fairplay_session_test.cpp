#include "fairplay_session.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>

using tvplayair::FairPlayV3Session;

static uint32_t nextPrng(uint32_t& state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    return state;
}

int main() {
    FairPlayV3Session fp;

    std::array<uint8_t, FairPlayV3Session::kSetupReqBytes> setupReq = {
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x01, 0x00, 0, 0, 0, 0x04, 0x02, 0x00, 0, 0xbb
    };
    std::array<uint8_t, FairPlayV3Session::kSetupResBytes> setupRes{};

    setupReq[14] = 0;
    assert(fp.setup(setupReq.data(), setupReq.size(), setupRes.data(), setupRes.size()));
    setupReq[14] = 3;
    assert(fp.setup(setupReq.data(), setupReq.size(), setupRes.data(), setupRes.size()));
    // Reject out-of-bounds key mode
    setupReq[14] = 200;
    assert(!fp.setup(setupReq.data(), setupReq.size(), setupRes.data(), setupRes.size()));

    std::array<uint8_t, FairPlayV3Session::kHandshakeReqBytes> hsReq = {
        0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x03, 0x00, 0, 0, 0, 0x98
    };
    std::array<uint8_t, FairPlayV3Session::kHandshakeResBytes> hsRes{};
    std::array<uint8_t, FairPlayV3Session::kEncryptedKeyBytes> ekey{};
    std::array<uint8_t, FairPlayV3Session::kAesKeyBytes> key{};

    hsReq[12] = 3;
    assert(fp.handshake(hsReq.data(), hsReq.size(), hsRes.data(), hsRes.size()));
    assert(fp.decryptKey(ekey.data(), ekey.size(), key.data(), key.size()));

    // Reject out-of-bounds handshake mode and refuse subsequent decrypt
    hsReq[12] = 200;
    assert(!fp.handshake(hsReq.data(), hsReq.size(), hsRes.data(), hsRes.size()));
    assert(!fp.decryptKey(ekey.data(), ekey.size(), key.data(), key.size()));

    // Verify deterministic key output across all 4 FairPlay v3 modes
    uint32_t rng = 7;
    uint64_t sum = 1469598103934665603ULL;
    for (int t = 0; t < 2000; ++t) {
        for (auto& b : hsReq) b = static_cast<uint8_t>(nextPrng(rng));
        for (auto& b : ekey) b = static_cast<uint8_t>(nextPrng(rng));
        hsReq[4] = 3;
        hsReq[12] = static_cast<uint8_t>(t % 4);
        assert(fp.handshake(hsReq.data(), hsReq.size(), hsRes.data(), hsRes.size()));
        assert(fp.decryptKey(ekey.data(), ekey.size(), key.data(), key.size()));
        for (uint8_t kb : key) {
            sum = (sum ^ kb) * 1099511628211ULL;
        }
    }
    assert(sum == 0x36dad2d4bf5daea9ULL);

    std::puts("FairPlay v3 host tests passed: bounds, handshake, and 2000-vector key checksum.");
}
