#pragma once

#include <cstdint>
#include <array>
#include <vector>

struct UsbHandle;
using InterruptTransfer = int (*)(UsbHandle*, unsigned char, unsigned char*, int, int*,
                                  unsigned int);

enum class DumpResult { received, missing, incomplete, failed };
enum class CameraFile { rawBurst, factoryKb };

struct HidReport {
    std::array<uint8_t, 512> bytes;
    int length;
};
constexpr int hidBatchSize = 16;
using ReceiveReports = bool (*)(UsbHandle*, HidReport*, int, unsigned);

// Read the WP-09 firmware's fixed eight-frame calibration capture file.
DumpResult readThermalDump(UsbHandle* device, InterruptTransfer transfer,
                           std::vector<uint8_t>& dump, ReceiveReports receiveBatch = nullptr,
                           int64_t* announcedNs = nullptr, CameraFile file = CameraFile::rawBurst);
