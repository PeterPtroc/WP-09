#include "thermal_dump.h"
#include "capture_preview.h"

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
constexpr size_t dumpSize = wp09::dumpBytes;
constexpr unsigned timeoutMs = 1500;
constexpr uint8_t magic[] = {0xff, 0x55, 0xff, 0xaa, 'A', 'R', 'T', 'O'};
constexpr char dumpPath[] = "/usrdata/low.raw";
constexpr char kbPath[] = "/factory/rtd2121w_kb_calib.bin";
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

int validatedSize(const uint8_t* report, int bytes) {
    if (bytes < static_cast<int>(headerSize) || std::memcmp(report, magic, sizeof(magic)) != 0
            || dword(report + 8) != headerSize) return -1;
    const uint32_t payloadSize = dword(report + 16);
    if (payloadSize > static_cast<uint32_t>(bytes) - headerSize ||
            checksum(report + headerSize, payloadSize) != dword(report + 32)) return -1;
    return static_cast<int>(payloadSize);
}

int receive(UsbHandle* device, InterruptTransfer transfer, std::array<uint8_t, 2048>& report) {
    int bytes = 0;
    if (transfer(device, 0x84, report.data(), report.size(), &bytes, timeoutMs) != 0) return -1;
    return validatedSize(report.data(), bytes);
}

bool fileReply(const uint8_t* data) {
    return word(data) == fileReadPort && word(data + 2) == payloadHeaderSize;
}
}

DumpResult readThermalDump(UsbHandle* device, InterruptTransfer transfer,
                           std::vector<uint8_t>& dump, ReceiveReports receiveBatch, int64_t* announcedNs, CameraFile file) {
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
    const char* path = file == CameraFile::factoryKb ? kbPath : dumpPath;
    const size_t expectedFileSize = file == CameraFile::factoryKb ? wp09::width * wp09::height * 4 : dumpSize;
    std::memcpy(body + payloadHeaderSize + 4, path, std::strlen(path) + 1);
    putDword(request.data() + 32, checksum(body, request.size() - headerSize));

    int sent = 0;
    if (transfer(device, 0x02, request.data(), request.size(), &sent, timeoutMs) != 0
            || sent != static_cast<int>(request.size())) return DumpResult::failed;

    std::array<uint8_t, 2048> report{};
    const auto* payload = report.data() + headerSize;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
    // A failed earlier transfer may still have data reports queued on the IN endpoint.
    // Resume at the next file response, bounded by the size of one complete transfer.
    constexpr size_t maxFragments = (dumpSize + payloadHeaderSize + fragmentSize - 1) / fragmentSize;
    int size;
    for (size_t remaining = maxFragments;; remaining--) {
        size = receive(device, transfer, report);
        if (size < 0) return DumpResult::failed;
        if (size == 64 + static_cast<int>(payloadHeaderSize) && dword(report.data() + 24) == 0
                && fileReply(payload) && (std::memcmp(payload + payloadHeaderSize, "fget", 4) == 0 ||
                    std::memcmp(payload + payloadHeaderSize, "open file failed", 16) == 0)) break;
        if (!remaining || std::chrono::steady_clock::now() >= deadline) return DumpResult::failed;
    }
    if (std::memcmp(payload + payloadHeaderSize, "open file failed", 16) == 0) {
        return DumpResult::missing;
    }
    const size_t fileBytes = dword(payload + payloadHeaderSize + 4);
    if (announcedNs && fileBytes == expectedFileSize) {
        *announcedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    if (fileBytes > expectedFileSize) return DumpResult::failed;
    dump.resize(fileBytes);
    size_t copied = 0;
    std::array<HidReport, hidBatchSize> batch;
    int cursor = 0, buffered = 0;
    for (uint32_t index = 0; copied < fileBytes; index++) {
        if (std::chrono::steady_clock::now() >= deadline) return DumpResult::failed;
        const size_t skip = index == 0 ? payloadHeaderSize : 0;
        const uint8_t* packet = report.data();
        if (receiveBatch) {
            if (cursor == buffered) {
                size_t remaining = (fileBytes - copied + skip + fragmentSize - 1) / fragmentSize;
                buffered = static_cast<int>(std::min(remaining, batch.size()));
                cursor = 0;
                if (!receiveBatch(device, batch.data(), buffered, timeoutMs)) return DumpResult::failed;
            }
            const auto& item = batch[cursor++];
            packet = item.bytes.data();
            size = validatedSize(packet, item.length);
        } else size = receive(device, transfer, report);
        payload = packet + headerSize;
        const size_t expected = std::min(fragmentSize - skip, fileBytes - copied);
        if (size != static_cast<int>(expected + skip) || dword(packet + 24) != index
                || (index == 0 && (!fileReply(payload) || dword(payload + 4) != fileBytes))) {
            return DumpResult::failed;
        }
        std::memcpy(dump.data() + copied, payload + skip, expected);
        copied += expected;
    }
    return fileBytes == expectedFileSize ? DumpResult::received : DumpResult::incomplete;
}
