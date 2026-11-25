#include "capture_preview.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace wp09 {
PreviewSample::PreviewSample(const uint8_t* data, int64_t time) : timeNs(time) {
    std::memcpy(bytes.data(), data, bytes.size());
}

namespace {
// Compare the scene at sensor coordinates. Block means suppress the fixed-pattern noise
// in the unprocessed counts. Centering and normalization remove automatic display gain.
constexpr int block = 16;
using Grid = std::array<float, (width / block) * (height / block)>;

Grid thumbnail(const uint8_t* data, int stride, bool sensor) {
    Grid grid{};
    float sum = 0;
    for (int y = 0; y < height; y++) {
        const auto* row = data + y * stride;
        for (int x = 0; x < width; x += 2) {
            int value = row[2*x];
            if (sensor) value |= row[2*x + 1] << 8;
            grid[(y / block) * (width / block) + x / block] += value;
        }
    }
    for (float value : grid) sum += value;
    const float mean = sum / grid.size();
    float norm = 0;
    for (float& value : grid) { value -= mean; norm += value * value; }
    if (norm > 0) {
        const float inverse = 1 / std::sqrt(norm);
        for (float& value : grid) value *= inverse;
    }
    return grid;
}

float similarity(const Grid& a, const Grid& b) {
    float score = 0;
    for (size_t i = 0; i < a.size(); i++) score += a[i] * b[i];
    return score;
}

// Common scene structure can dominate every candidate in a handheld burst. Comparing
// changes between consecutive images cancels stationary sensor offsets and background.
float motionSimilarity(const Grid& a, const Grid& b, const Grid& c, const Grid& d) {
    float dot = 0, rawEnergy = 0, previewEnergy = 0;
    for (size_t i = 0; i < a.size(); i++) {
        float raw = b[i] - a[i], preview = d[i] - c[i];
        dot += raw * preview;
        rawEnergy += raw * raw;
        previewEnergy += preview * preview;
    }
    if (rawEnergy < 0.0004f || previewEnergy < 0.0004f) return NAN;
    return dot / std::sqrt(rawEnergy * previewEnergy);
}
}

Pairing matchPreviews(const std::vector<uint8_t>& dump, const std::vector<PreviewSample>& samples,
                      int64_t announcedNs) {
    Pairing result;
    result.fill(-1);
    if (dump.size() != dumpBytes || samples.empty()) return result;
    std::array<Grid, burstFrames> raw;
    for (int i = 0; i < burstFrames; i++) {
        raw[i] = thumbnail(dump.data() + i * rawFrameBytes + rawImageOffset, rawStride, true);
    }
    std::vector<Grid> processed;
    processed.reserve(samples.size());
    // Allow one nominal UVC delivery interval after HID advertises the full file.
    // Callbacks accumulated while the 1.25 MB body is transferred belong to later scenes.
    constexpr int64_t frameNs = 20000000;
    for (const auto& sample : samples) {
        if (sample.timeNs > announcedNs + frameNs) break;
        bool monochrome = true;
        for (int i = 1; i < width * height * 2; i += 2) {
            if (sample.bytes[i] != 128) { monochrome = false; break; }
        }
        processed.push_back(monochrome && sample.timeNs > 0 ? thumbnail(sample.bytes.data(), width * 2, false) : Grid{});
    }

    // The two streams expose no common exposure ID. Choose an ordered sequence with
    // evidence for every frame; favor the earlier sequence when the scene is stationary.
    int bestStart = -1;
    float best = -1;
    const int count = static_cast<int>(processed.size());
    for (int start = 0; start + burstFrames <= count; start++) {
        float total = 0, weakest = 1, motionSum = 0;
        int moving = 0;
        for (int j = 0; j < burstFrames; j++) {
            if (j) {
                const auto gap = samples[start + j].timeNs - samples[start + j - 1].timeNs;
                if (gap <= 0 || gap > frameNs * 7 / 4) { weakest = 0; break; }
                float motion = motionSimilarity(raw[j-1], raw[j], processed[start+j-1], processed[start+j]);
                if (std::isfinite(motion)) { motionSum += motion; moving++; }
            }
            const float score = similarity(raw[j], processed[start + j]);
            weakest = std::min(weakest, score);
            total += score;
        }
        const float mean = total / burstFrames;
        const float motion = moving >= 2 ? motionSum / moving : 0.f;
        const float rank = mean + 0.18f * motion;
        if (weakest >= 0.30f && mean >= 0.40f && (moving < 2 || motion >= 0.15f) && rank > best + 0.005f) {
            bestStart = start;
            best = rank;
        }
    }
    if (bestStart >= 0) {
        for (int j = 0; j < burstFrames; j++) result[j] = bestStart + j;
    } else {
        // A slow or interrupted preview may still have a good match for the final exposure.
        best = 0.45f;
        for (int i = 0; i < count; i++) {
            const float score = similarity(raw.back(), processed[i]);
            // A partial sequence can support the final plane only when another raw
            // plane is not a substantially better explanation of this preview.
            bool otherExposure = false;
            for (int j = 0; j < burstFrames - 1; j++) {
                if (similarity(raw[j], processed[i]) > score + 0.015f) otherExposure = true;
            }
            if (!otherExposure && score > best + 0.005f) { best = score; result.back() = i; }
        }
    }
    return result;
}
}
