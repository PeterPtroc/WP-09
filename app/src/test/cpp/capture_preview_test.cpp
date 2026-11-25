#include "capture_preview.h"

#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>

using namespace wp09;

int main(int argc, char** argv) {
    std::vector<uint8_t> dump(dumpBytes);
    std::vector<PreviewSample> samples;
    std::array<uint8_t, wireBytes> preview{};
    for (int i = 0; i < 11; i++) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            // A changing scene, with three unrelated previews before the captured burst.
            int value = ((x / 16 + 13 * (y / 16) + i * 7) * (x / 16 + i + 1)) % 241;
            preview[2 * (y * width + x)] = value;
            preview[2 * (y * width + x) + 1] = 128;
            if (i >= 3) {
                size_t pos = (i - 3) * rawFrameBytes + rawImageOffset + y * rawStride + 2 * x;
                int raw = 10000 + 5 * value + ((x + y) % 7);
                dump[pos] = raw & 255;
                dump[pos + 1] = raw >> 8;
            }
        }
        samples.emplace_back(preview.data(), (i + 1) * 20000000LL);
    }
    int64_t ready = samples.back().timeNs;
    auto paired = matchPreviews(dump, samples, ready);
    for (int i = 0; i < burstFrames; i++) assert(paired[i] == i + 3);
    std::vector<PreviewSample> one{samples.back()};
    auto partial = matchPreviews(dump, one, ready);
    assert(partial.back() == 0);
    for (int i = 0; i < 7; i++) assert(partial[i] < 0);
    assert(matchPreviews(dump, one, ready - 22000000).back() == -1);
    one.back().timeNs = 0;
    assert(matchPreviews(dump, one, ready).back() == -1);
    // A similar stationary background with the motion played backwards precedes the real event.
    std::vector<PreviewSample> candidates;
    for (int j = 0; j < 16; j++) {
        int phase = j < 8 ? 7 - j : j - 8;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int background = 25 + 9 * (x / 16) + 4 * (y / 16);
            int value = background + ((x / 16 == phase + 3 && y / 16 >= 3) ? 23 : 0);
            preview[2 * (y * width + x)] = value;
            preview[2 * (y * width + x) + 1] = 128;
        }
        candidates.emplace_back(preview.data(), (j + 1) * 20000000LL);
    }
    auto movingDump = dump;
    for (int j = 0; j < burstFrames; j++) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int value = 10000 + 4 * candidates[j + 8].bytes[2 * (y * width + x)];
            size_t byte = j * rawFrameBytes + rawImageOffset + y * rawStride + 2 * x;
            movingDump[byte] = value & 255;
            movingDump[byte + 1] = value >> 8;
        }
    }
    auto motion = matchPreviews(movingDump, candidates, candidates.back().timeNs);
    for (int j = 0; j < burstFrames; j++) assert(motion[j] == j + 8);
    for (int i = 7; i < 11; i++) samples[i].timeNs += 80000000;
    auto interrupted = matchPreviews(dump, samples, samples.back().timeNs);
    for (int i = 0; i < 7; i++) assert(interrupted[i] == -1);
    assert(interrupted.back() == 10);
    for (auto& sample : samples) sample.bytes.fill(128);
    for (int index : matchPreviews(dump, samples, ready)) assert(index == -1);
    if (argc >= 3) {
        std::ifstream raw(argv[1], std::ios::binary);
        raw.read(reinterpret_cast<char*>(dump.data()), dumpBytes);
        assert(raw.gcount() == dumpBytes);
        samples.clear();
        for (int i = 2; i < argc; i++) {
            std::ifstream source(argv[i], std::ios::binary);
            source.read(reinterpret_cast<char*>(preview.data()), wireBytes);
            assert(source.gcount() == wireBytes);
            samples.emplace_back(preview.data(), i * 20000000LL);
        }
        auto real = matchPreviews(dump, samples, samples.back().timeNs);
        assert(real.back() >= 0);
        std::cout << "Recorded final plane matched " << argv[real.back() + 2] << '\n';
    }
    std::cout << "Ordered burst, single frame and unmatchable scene checks passed.\n";
}
