#include "thermal_dump.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <numeric>

namespace {
constexpr size_t headerSize = 36;
constexpr size_t payloadHeaderSize = 12;
constexpr size_t requestSize = 260;
constexpr size_t fragmentSize = 512 - headerSize;
constexpr size_t dumpSize = 8 * 203 * 768;
constexpr unsigned timeoutMs = 1500;
constexpr uint8_t magic[] = {0xff, 0x55, 0xff, 0xaa, 'A', 'R', 'T', 'O'};
constexpr char dumpPath[] = "/usrdata/low.raw";
constexpr uint8_t fileReadPort = 2;

uint32_t word(const uint8_t* data) {
    return data[0] | static_cast<uint32_t>(data[1]) << 8;
}

uint32_t dword(const uint8_t* data) {
    return word(data) | word(data + 2) << 16;
}

void putDword(uint8_t* data, uint32_t value) {
    for (int index = 0; index < 4; index++) data[index] = value >> (index * 8);
}

uint32_t checksum(const uint8_t* data, size_t length) {
    return std::accumulate(data, data + length, uint32_t{0});
}

int receive(UsbHandle* device, InterruptTransfer transfer, std::array<uint8_t, 2048>& report) {
    int bytes = 0;
    if (transfer(device, 0x84, report.data(), report.size(), &bytes, timeoutMs) != 0
            || bytes < static_cast<int>(headerSize)
            || std::memcmp(report.data(), magic, sizeof(magic)) != 0
            || dword(report.data() + 8) != headerSize) return -1;
    const uint32_t payloadSize = dword(report.data() + 16);
    if (payloadSize > static_cast<uint32_t>(bytes) - headerSize ||
            checksum(report.data() + headerSize, payloadSize) != dword(report.data() + 32)) return -1;
    return static_cast<int>(payloadSize);
}

bool fileReply(const uint8_t* data) {
    return word(data) == fileReadPort && word(data + 2) == payloadHeaderSize;
}
}

DumpResult readThermalDump(UsbHandle* device, InterruptTransfer transfer,
                           std::vector<uint8_t>& dump) {
    dump.clear();
    std::array<uint8_t, headerSize + payloadHeaderSize + requestSize> request{};
    auto* body = request.data() + headerSize;
    std::copy(std::begin(magic), std::end(magic), request.begin());
    putDword(request.data() + 8, headerSize);
    putDword(request.data() + 12, request.size() - headerSize);
    putDword(request.data() + 16, request.size() - headerSize);
    request[20] = 1;
    request[28] = 1;
    body[0] = fileReadPort;
    body[2] = payloadHeaderSize;
    putDword(body + 4, requestSize);
    std::memcpy(body + payloadHeaderSize, "fget", 4);
    std::memcpy(body + payloadHeaderSize + 4, dumpPath, sizeof(dumpPath));
    putDword(request.data() + 32, checksum(body, request.size() - headerSize));

    int sent = 0;
    if (transfer(device, 0x02, request.data(), request.size(), &sent, timeoutMs) != 0
            || sent != static_cast<int>(request.size())) return DumpResult::failed;

    std::array<uint8_t, 2048> report{};
    int size = receive(device, transfer, report);
    const auto* payload = report.data() + headerSize;
    if (size != 64 + static_cast<int>(payloadHeaderSize) || dword(report.data() + 24) != 0
            || !fileReply(payload)) return DumpResult::failed;
    if (std::memcmp(payload + payloadHeaderSize, "open file failed", 16) == 0) {
        return DumpResult::missing;
    }
    if (std::memcmp(payload + payloadHeaderSize, "fget", 4) != 0
            || dword(payload + payloadHeaderSize + 4) != dumpSize) return DumpResult::failed;

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
    dump.reserve(dumpSize);
    for (uint32_t index = 0; dump.size() < dumpSize; index++) {
        if (std::chrono::steady_clock::now() >= deadline) return DumpResult::failed;
        size = receive(device, transfer, report);
        const size_t skip = index == 0 ? payloadHeaderSize : 0;
        const size_t expected = std::min(fragmentSize - skip, dumpSize - dump.size());
        if (size != static_cast<int>(expected + skip) || dword(report.data() + 24) != index
                || (index == 0 && (!fileReply(payload) || dword(payload + 4) != dumpSize))) {
            return DumpResult::failed;
        }
        dump.insert(dump.end(), payload + skip, payload + skip + expected);
    }
    return DumpResult::received;
}
