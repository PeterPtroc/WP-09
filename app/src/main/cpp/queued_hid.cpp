#include "queued_hid.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {
constexpr unsigned hidInterface = 4;
}

void QueuedHid::attach(int descriptor) {
    if (active() || descriptor < 0) return;
    int candidate = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    if (candidate < 0) return;
    if (ioctl(candidate, USBDEVFS_CLAIMINTERFACE, &hidInterface) < 0) {
        const int claimError = errno;
        usbdevfs_disconnect_claim claim{};
        claim.interface = hidInterface;
        claim.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
        std::strcpy(claim.driver, "usbhid");
        if (claimError != EBUSY || ioctl(candidate, USBDEVFS_DISCONNECT_CLAIM, &claim) < 0) {
            ::close(candidate);
            return;
        }
        detached = true;
    }
    fd = candidate;
}

void QueuedHid::close() {
    if (!active()) return;
    // Releasing this interface cancels outstanding URBs before their buffers go out of scope.
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &hidInterface);
    if (detached) {
        usbdevfs_ioctl reconnect{};
        reconnect.ifno = hidInterface;
        reconnect.ioctl_code = USBDEVFS_CONNECT;
        ioctl(fd, USBDEVFS_IOCTL, &reconnect);
        detached = false;
    }
    ::close(fd);
    fd = -1;
}

bool QueuedHid::exchange(usbdevfs_urb* requests, int count, unsigned timeout) {
    using namespace std::chrono;
    for (int i = 0; i < count; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, &requests[i]) < 0) { close(); return false; }
    }
    const auto deadline = steady_clock::now() + milliseconds(timeout);
    for (int completed = 0; completed < count;) {
        void* ready = nullptr;
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &ready) == 0) {
            auto* entry = static_cast<usbdevfs_urb*>(ready);
            // Only requests submitted on this connection can be reaped here.
            if (!entry || entry->status || entry->actual_length < 0 ||
                    entry->actual_length > entry->buffer_length) { close(); return false; }
            completed++;
            continue;
        }
        if (errno != EAGAIN && errno != EINTR) { close(); return false; }
        int remaining = static_cast<int>(ceil<milliseconds>(deadline - steady_clock::now()).count());
        pollfd endpoint{fd, POLLOUT, 0};
        if (remaining <= 0 || (poll(&endpoint, 1, remaining) < 0 && errno != EINTR) ||
                (endpoint.revents & (POLLERR | POLLHUP | POLLNVAL))) { close(); return false; }
    }
    return true;
}

int QueuedHid::transfer(UsbHandle* handle, unsigned char ep, unsigned char* buffer,
                        int length, int* transferred, unsigned timeout) {
    usbdevfs_urb request{};
    request.type = USBDEVFS_URB_TYPE_INTERRUPT;
    request.endpoint = ep;
    request.buffer = buffer;
    request.buffer_length = ep == 0x84 ? std::min(length, 512) : length;
    if (!reinterpret_cast<QueuedHid*>(handle)->exchange(&request, 1, timeout)) return -1;
    *transferred = request.actual_length;
    return 0;
}

bool QueuedHid::receive(UsbHandle* handle, HidReport* reports, int count, unsigned timeout) {
    usbdevfs_urb requests[hidBatchSize]{};
    for (int i = 0; i < count; i++) {
        requests[i].type = USBDEVFS_URB_TYPE_INTERRUPT;
        requests[i].endpoint = 0x84;
        requests[i].buffer = reports[i].bytes.data();
        requests[i].buffer_length = reports[i].bytes.size();
    }
    if (!reinterpret_cast<QueuedHid*>(handle)->exchange(requests, count, timeout)) return false;
    // File fragments are parsed in submission order even if completions arrive together.
    for (int i = 0; i < count; i++) reports[i].length = requests[i].actual_length;
    return true;
}

DumpResult QueuedHid::read(std::vector<uint8_t>& dump, int64_t* announcedNs, CameraFile file) {
    return readThermalDump(reinterpret_cast<UsbHandle*>(this), transfer, dump, receive, announcedNs, file);
}
