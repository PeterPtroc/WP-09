#include "capture_preview.h"
#include "thermal_dump.h"

#include <algorithm>
#include <cassert>
#include <cstring>
#include <deque>
#include <iostream>
#include <numeric>
#include <vector>

using Bytes = std::vector<uint8_t>;

struct UsbHandle {
    std::deque<Bytes> reports;
    const char* expectedPath = "/usrdata/low.raw";
};

static void put(Bytes& bytes, size_t at, uint32_t value) {
    for (int i = 0; i < 4; i++) bytes[at + i] = value >> (8 * i);
}

static void report(UsbHandle& device, const Bytes& payload, uint32_t index) {
    Bytes packet(36 + payload.size());
    const uint8_t magic[] = {0xff, 0x55, 0xff, 0xaa, 'A', 'R', 'T', 'O'};
    std::copy(std::begin(magic), std::end(magic), packet.begin());
    put(packet, 8, 36);
    put(packet, 16, payload.size());
    put(packet, 24, index);
    put(packet, 32, std::accumulate(payload.begin(), payload.end(), uint32_t{0}));
    std::copy(payload.begin(), payload.end(), packet.begin() + 36);
    device.reports.push_back(std::move(packet));
}

static void file(UsbHandle& device, const Bytes& data) {
    Bytes header(76);
    header[0] = 2;
    header[2] = 12;
    std::memcpy(header.data() + 12, "fget", 4);
    put(header, 16, data.size());
    report(device, header, 0);
    size_t pos = 0;
    for (uint32_t i = 0; pos < data.size(); i++) {
        Bytes payload;
        if (i == 0) {
            payload.assign(header.begin(), header.begin() + 12);
            put(payload, 4, data.size());
        }
        size_t count = std::min(476 - payload.size(), data.size() - pos);
        payload.insert(payload.end(), data.begin() + pos, data.begin() + pos + count);
        report(device, payload, i);
        pos += count;
    }
}

static int transfer(UsbHandle* device, unsigned char endpoint, unsigned char* data, int length,
                    int* transferred, unsigned int) {
    if (endpoint == 0x02) {
        assert(length == 308 && std::memcmp(data + 48, "fget", 4) == 0);
        assert(std::strcmp(reinterpret_cast<const char*>(data + 52), device->expectedPath) == 0);
        *transferred = length;
        return 0;
    }
    assert(endpoint == 0x84);
    if (device->reports.empty()) return -1;
    auto& packet = device->reports.front();
    assert(length >= static_cast<int>(packet.size()));
    *transferred = static_cast<int>(packet.size());
    std::copy(packet.begin(), packet.end(), data);
    device->reports.pop_front();
    return 0;
}

int main() {
    UsbHandle device;
    Bytes raw(wp09::dumpBytes), received;
    for (size_t i = 0; i < raw.size(); i++) raw[i] = (i / 512 + i) % 251;
    file(device, Bytes(753, 12));
    assert(readThermalDump(&device, transfer, received) == DumpResult::incomplete && device.reports.empty());
    file(device, Bytes(2000, 31));
    device.reports[2].back() ^= 1;
    assert(readThermalDump(&device, transfer, received) == DumpResult::failed && !device.reports.empty());
    file(device, raw);
    assert(readThermalDump(&device, transfer, received) == DumpResult::received);
    assert(received == raw && device.reports.empty());
    auto grouped = [](UsbHandle* usb, HidReport* out, int count, unsigned timeout) {
        assert(count > 0 && count <= hidBatchSize);
        for (int i = 0; i < count; i++)
            if (transfer(usb, 0x84, out[i].bytes.data(), out[i].bytes.size(), &out[i].length, timeout) != 0)
                return false;
        return true;
    };
    file(device, raw);
    assert(readThermalDump(&device, transfer, received, grouped) == DumpResult::received);
    assert(received == raw && device.reports.empty());
    file(device, Bytes(471, 42));
    assert(readThermalDump(&device, transfer, received, grouped) == DumpResult::incomplete && device.reports.empty());
    Bytes kb(wp09::width * wp09::height * 4, 93);
    device.expectedPath = "/factory/rtd2121w_kb_calib.bin";
    file(device, kb);
    assert(readThermalDump(&device, transfer, received, grouped, nullptr, CameraFile::factoryKb) == DumpResult::received);
    assert(received == kb && device.reports.empty());
    std::cout << "Drained short capture, recovered interrupted transfer, verified all 1,247,232 bytes.\n";
}
