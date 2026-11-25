#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace wp09 {
constexpr int width = 256, height = 192;
constexpr int wireBytes = width * (height + 4) * 2;
constexpr int burstFrames = 8;
constexpr int rawStride = 768;
constexpr int rawImageOffset = 8 * rawStride + 2 * sizeof(uint16_t);
constexpr int rawFrameBytes = 203 * rawStride;
constexpr int dumpBytes = burstFrames * rawFrameBytes;

struct PreviewSample {
    int64_t timeNs;
    std::array<uint8_t, wireBytes> bytes;
    PreviewSample(const uint8_t* data, int64_t time);
};

using Pairing = std::array<int, burstFrames>;
// Only fresh UVC samples from this trigger are passed in. -1 means radiometry only.
Pairing matchPreviews(const std::vector<uint8_t>& dump, const std::vector<PreviewSample>& samples,
                      int64_t announcedNs);
}
