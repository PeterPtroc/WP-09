#pragma once

#include "thermal_dump.h"

struct usbdevfs_urb;

// A separate USB connection owns only the HID endpoint and its completion queue.
class QueuedHid {
public:
    void attach(int descriptor);
    bool active() const { return fd >= 0; }
    DumpResult read(std::vector<uint8_t>& dump, int64_t* announcedNs, CameraFile file = CameraFile::rawBurst);
    void close();
    ~QueuedHid() { close(); }

private:
    int fd = -1;
    bool detached = false;
    bool exchange(usbdevfs_urb* requests, int count, unsigned timeout);
    static int transfer(UsbHandle*, unsigned char, unsigned char*, int, int*, unsigned);
    static bool receive(UsbHandle*, HidReport*, int, unsigned);
};
