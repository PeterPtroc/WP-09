#pragma once

#include <cstdint>
#include <vector>

struct UsbHandle;
using InterruptTransfer = int (*)(UsbHandle*, unsigned char, unsigned char*, int, int*,
                                  unsigned int);

enum class DumpResult { received, missing, failed };

// Read the WP-09 firmware's fixed eight-frame calibration capture file.
DumpResult readThermalDump(UsbHandle* device, InterruptTransfer transfer,
                           std::vector<uint8_t>& dump);
