#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>

#include "thermal_dump.h"
#include "queued_hid.h"
#include "capture_preview.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>
#include <thread>
#include <utility>

namespace {
constexpr int width = 256;
constexpr int height = 196;
constexpr int frameBytes = width * height * 2;
constexpr int sampleCapacity = 512 * 1024;
constexpr int tableSize = 16384;
constexpr int laserOff = 0x8085;
constexpr int laserOn = 0x8086;
constexpr uint16_t captureRawFrames = 0x802f;
constexpr uint16_t outputThermal = 0x8004, outputPreview = 0x8005;
constexpr uint16_t forceDynamicCalibration = 0x8000;
constexpr int hidInterface = 4;

// The bundled XWild libuvc callback receives (bytes, user, length).
struct UvcContext;
struct UvcDevice;
struct UvcHandle;
struct StreamControl;
using FrameCallback = void (*)(unsigned char*, void*, size_t);

template <typename T>
bool bind(void* lib, const char* symbol, T& output) {
    output = reinterpret_cast<T>(dlsym(lib, symbol));
    return output != nullptr;
}

struct UvcApi {
    void* lib = dlopen("libuvc.so", RTLD_NOW | RTLD_LOCAL);
    int (*init)(UvcContext**, void*, const char*) = nullptr;
    int (*device)(UvcContext*, UvcDevice**, int, int, const char*, int, int, int) = nullptr;
    int (*open)(UvcDevice*, UvcHandle**) = nullptr;
    int (*configure)(UvcHandle*, StreamControl*, int, int, int, int, int) = nullptr;
    int (*start)(UvcHandle*, StreamControl*, FrameCallback, void*, int, int, float) = nullptr;
    int (*zoom)(UvcHandle*, uint16_t) = nullptr;
    void (*stop)(UvcHandle*) = nullptr;
    void (*close)(UvcHandle*) = nullptr;
    void (*unref)(UvcDevice*) = nullptr;
    void (*exit)(UvcContext*) = nullptr;

    bool load() {
        return lib && bind(lib, "uvc_init2", init)
            && bind(lib, "uvc_get_device_with_fd", device)
            && bind(lib, "uvc_open", open)
            && bind(lib, "uvc_get_stream_ctrl_format_size_fps", configure)
            && bind(lib, "uvc_start_streaming_bandwidth", start)
            && bind(lib, "uvc_set_zoom_abs", zoom)
            && bind(lib, "uvc_stop_streaming", stop)
            && bind(lib, "uvc_close", close)
            && bind(lib, "uvc_unref_device", unref)
            && bind(lib, "uvc_exit", exit);
    }

    ~UvcApi() { if (lib) dlclose(lib); }
};

struct Camera {
    UvcApi api;
    UvcContext* context = nullptr;
    UvcDevice* device = nullptr;
    UvcHandle* handle = nullptr;
    int fd = -1;
    bool streaming = false;
    bool laserMayBeOn = false;
    bool thermalMayBeOn = false;
    std::mutex mutex;
    std::condition_variable frameReady;
    std::array<jbyte, sampleCapacity> bytes;
    size_t length = 0;
    std::chrono::steady_clock::time_point lastArrival;
    bool unread = false;
    std::vector<uint8_t> previousDump, nextDump;
    std::vector<wp09::PreviewSample> samples;
    bool collecting = false;
    bool knownDump = false;
    QueuedHid captureHid;

    ~Camera() {
        captureHid.close();
        if (thermalMayBeOn) api.zoom(handle, outputPreview);
        if (laserMayBeOn) api.zoom(handle, laserOff);
        if (streaming) api.stop(handle);
        if (handle) api.close(handle);
        if (device) api.unref(device);
        if (context) api.exit(context);
        if (fd >= 0) ::close(fd);
    }
};

class HidInterface {
public:
    HidInterface(void* uvc, UvcHandle* camera) {
        UsbHandle* (*getHandle)(UvcHandle*) = nullptr;
        int (*claim)(UsbHandle*, int) = nullptr;
        if (library && bind(library, "libusb_claim_interface", claim)
                && bind(library, "libusb_release_interface", release)
                && bind(library, "libusb_interrupt_transfer", transfer)
                && bind(uvc, "uvc_get_libusb_handle", getHandle)) {
            auto* device = getHandle(camera);
            if (device && claim(device, hidInterface) == 0) handle = device;
        }
    }

    ~HidInterface() {
        if (handle) release(handle, hidInterface);
        if (library) dlclose(library);
    }

    UsbHandle* handle = nullptr;
    InterruptTransfer transfer = nullptr;

private:
    void* library = dlopen("libusb100.so", RTLD_NOW | RTLD_LOCAL);
    int (*release)(UsbHandle*, int) = nullptr;
};

void onFrame(unsigned char* data, void* opaque, size_t length) {
    auto& camera = *static_cast<Camera*>(opaque);
    std::lock_guard<std::mutex> lock(camera.mutex);
    if (length <= camera.bytes.size()) std::memcpy(camera.bytes.data(), data, length);
    camera.length = length;
    camera.lastArrival = std::chrono::steady_clock::now();
    if (camera.collecting && length == frameBytes && camera.samples.size() < 48) {
        const auto now = camera.lastArrival.time_since_epoch();
        camera.samples.emplace_back(data, std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
    }
    camera.unread = true;
    if (camera.collecting) camera.frameReady.notify_all();
    else camera.frameReady.notify_one();
}

using Thermometry = void (*)(int, int, float*, uint16_t*, float*, float*, float*, float*,
                             float*, float*, uint16_t*, int, int, float);

Thermometry thermometry() {
    static const auto decode = [] {
        void* lib = dlopen("libthermometry.so", RTLD_NOW | RTLD_LOCAL);
        return lib ? reinterpret_cast<Thermometry>(dlsym(lib, "thermometryT4Line_XWild")) : nullptr;
    }();
    return decode;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_Wp09Usb_open(JNIEnv* env, jclass, jint fd, jint vendor, jint product,
                                   jint address, jstring path, jlongArray outputHandle) {
    auto* camera = new (std::nothrow) Camera();
    if (!camera) return -100;
    if (!camera->api.load()) {
        delete camera;
        return -101;
    }
    const char* usbfs = env->GetStringUTFChars(path, nullptr);
    if (!usbfs) {
        delete camera;
        return -100;
    }

    // This libusb fork takes the open USB descriptor as its bus key, as in the original app.
    auto& api = camera->api;
    int result = api.init(&camera->context, nullptr, usbfs);
    if (result == 0) {
        camera->fd = dup(fd);
        result = camera->fd < 0 ? -102 : api.device(camera->context, &camera->device,
            vendor, product, nullptr, camera->fd, fd, address);
    }
    env->ReleaseStringUTFChars(path, usbfs);
    if (result == 0) result = api.open(camera->device, &camera->handle);
    if (result == 0) {
        // Opaque storage for the vendor stream-control structure.
        alignas(8) std::array<unsigned char, 128> control{};
        auto* config = reinterpret_cast<StreamControl*>(control.data());
        constexpr int yuyv = 3;
        result = api.configure(camera->handle, config, yuyv, width, height, 50, 50);
        if (result != 0) result = api.configure(camera->handle, config, yuyv, width, height, 1, 50);
        if (result == 0) {
            // Match the XWild preview. This libuvc fork strips isochronous packet
            // headers and counts only payload bytes when the last integer is 1.
            result = api.start(camera->handle, config, onFrame, camera, 0, 1, 1.0f);
            camera->streaming = result == 0;
        }
    }
    if (result != 0) {
        delete camera;
        return result < 0 ? result : -103;
    }
    // Android may set a heap pointer's top byte, making it negative as a Java long.
    const auto handle = reinterpret_cast<jlong>(camera);
    env->SetLongArrayRegion(outputHandle, 0, 1, &handle);
    if (env->ExceptionCheck()) {
        delete camera;
        return -100;
    }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_thermal_wp09_Wp09Usb_read(JNIEnv* env, jclass, jlong pointer, jbyteArray output) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    std::unique_lock<std::mutex> lock(camera.mutex);
    if (!camera.frameReady.wait_for(lock, std::chrono::milliseconds(40),
            [&camera] { return camera.unread; })) return 0;
    camera.unread = false;
    if (camera.length != frameBytes) return -static_cast<int>(camera.length);
    env->SetByteArrayRegion(output, 0, frameBytes, camera.bytes.data());
    return std::chrono::duration_cast<std::chrono::nanoseconds>(camera.lastArrival.time_since_epoch()).count();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_thermal_wp09_Wp09Usb_sample(JNIEnv* env, jclass, jlong pointer) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    std::lock_guard<std::mutex> lock(camera.mutex);
    if (camera.length == 0 || camera.length > camera.bytes.size()) return nullptr;
    const auto length = static_cast<jsize>(camera.length);
    auto bytes = env->NewByteArray(length);
    if (bytes) env->SetByteArrayRegion(bytes, 0, length, camera.bytes.data());
    return bytes;
}

static DumpResult readDump(Camera& camera, std::vector<uint8_t>& output, int64_t* announcedNs = nullptr,
                           CameraFile file = CameraFile::rawBurst) {
    if (camera.captureHid.active()) {
        auto result = camera.captureHid.read(output, announcedNs, file);
        if (result != DumpResult::failed) return result;
        camera.captureHid.close();
    }
    HidInterface hid(camera.api.lib, camera.handle);
    return hid.handle ? readThermalDump(hid.handle, hid.transfer, output, nullptr, announcedNs, file) : DumpResult::failed;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_thermal_wp09_Wp09Usb_factoryKb(JNIEnv* env, jclass, jlong pointer) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    std::vector<uint8_t> kb;
    if (readDump(camera, kb, nullptr, CameraFile::factoryKb) != DumpResult::received) return nullptr;
    auto out = env->NewByteArray(kb.size());
    if (out) env->SetByteArrayRegion(out, 0, kb.size(), reinterpret_cast<const jbyte*>(kb.data()));
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_thermal_wp09_Wp09Usb_setThermalOutput(JNIEnv*, jclass, jlong pointer, jboolean enabled) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    if (enabled) camera.thermalMayBeOn = true;
    int result = camera.api.zoom(camera.handle, enabled ? outputThermal : outputPreview);
    if (!enabled && result == 0) camera.thermalMayBeOn = false;
    std::lock_guard<std::mutex> lock(camera.mutex);
    camera.unread = false;
    return result == 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_Wp09Usb_attachCaptureFd(JNIEnv*, jclass, jlong pointer, jint fd) {
    reinterpret_cast<Camera*>(pointer)->captureHid.attach(fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_thermal_wp09_Wp09Usb_queuedCapture(JNIEnv*, jclass, jlong pointer) {
    return reinterpret_cast<Camera*>(pointer)->captureHid.active();
}

static bool primeBaseline(Camera& camera) {
    if (!camera.knownDump) {
        auto baseline = readDump(camera, camera.previousDump);
        camera.knownDump = baseline == DumpResult::received || baseline == DumpResult::missing;
    }
    return camera.knownDump;
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_Wp09Usb_prepareCapture(JNIEnv*, jclass, jlong pointer) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    if (camera.knownDump) return;
    primeBaseline(camera);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_thermal_wp09_Wp09Usb_captureRawDump(JNIEnv* env, jclass, jlong pointer,
                                             jobjectArray references, jlongArray times, jboolean burst) {
    using namespace std::chrono;
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    if (!primeBaseline(camera)) return nullptr;
    auto& samples = camera.samples;
    samples.clear();
    samples.reserve(48);
    const auto trigger = steady_clock::now();
    int64_t announcedNs = 0;
    bool fresh = false;
    auto& dump = camera.nextDump;
    if (camera.api.zoom(camera.handle, captureRawFrames) == 0) {
        // The sensor needs eight exposures. Start reading as soon as a burst could
        // be ready, then retry a missing, short or unchanged file without re-triggering.
        const auto deadline = steady_clock::now() + milliseconds(2200);
        std::unique_lock<std::mutex> lock(camera.mutex);
        camera.collecting = true;
        camera.frameReady.wait_for(lock, milliseconds(220), [&] { return samples.size() >= wp09::burstFrames; });
        lock.unlock();
        bool retriedTransfer = false;
        do {
            const auto state = readDump(camera, dump, &announcedNs);
            if (state == DumpResult::failed) {
                if (retriedTransfer) break;
                retriedTransfer = true;
            }
            // A rewrite in progress can already have a full file length. Check the
            // last active sensor row, ignoring changes to headers and padding.
            constexpr size_t lastRow = (wp09::burstFrames - 1) * wp09::rawFrameBytes
                    + wp09::rawImageOffset + (wp09::height - 1) * wp09::rawStride;
            if (state == DumpResult::received && (camera.previousDump.empty() ||
                    std::memcmp(dump.data() + lastRow, camera.previousDump.data() + lastRow,
                                wp09::width * 2) != 0)) {
                fresh = true;
                break;
            }
            std::this_thread::sleep_for(milliseconds(20));
        } while (steady_clock::now() < deadline);
    }
    {
        std::lock_guard<std::mutex> lock(camera.mutex);
        camera.collecting = false;
        // Some firmware pauses UVC during a still capture. A recent trailer can
        // supply calibration, but its earlier display image cannot be paired.
        if (fresh && samples.empty() && camera.length == frameBytes &&
                camera.lastArrival >= trigger - milliseconds(250)) {
            samples.emplace_back(reinterpret_cast<const uint8_t*>(camera.bytes.data()), 0);
        }
    }
    if (!fresh) {
        // A delayed firmware write must become the baseline before the next trigger.
        camera.knownDump = false;
        return nullptr;
    }
    camera.previousDump.swap(dump);
    if (samples.empty()) return nullptr;
    const auto& raw = camera.previousDump;
    const auto pairing = wp09::matchPreviews(raw, samples, announcedNs);
    // When no visual match is possible, prefer calibration from the capture window.
    auto calibration = std::upper_bound(samples.begin(), samples.end(), announcedNs,
            [](int64_t t, const wp09::PreviewSample& sample) { return t < sample.timeNs; });
    if (calibration != samples.begin()) --calibration;
    std::array<jlong, wp09::burstFrames> stamps{};
    const bool rawBurst = burst && std::any_of(pairing.begin(), pairing.end(), [](int index) { return index < 0; });
    jbyteArray sharedCalibration = nullptr;
    for (int i = burst ? 0 : wp09::burstFrames - 1; i < wp09::burstFrames; i++) {
        // A gap in UVC does not erase any of the eight raw exposures. Keep the entire
        // burst on its known file event, without assigning guessed times or preview markers.
        int index = rawBurst ? -1 : pairing[i];
        const auto& sample = index < 0 ? *calibration : samples[index];
        stamps[i] = index >= 0 ? sample.timeNs : -announcedNs;
        jbyteArray ref = sharedCalibration;
        if (!ref) {
            ref = env->NewByteArray(frameBytes);
            if (!ref) return nullptr;
            env->SetByteArrayRegion(ref, 0, frameBytes, reinterpret_cast<const jbyte*>(sample.bytes.data()));
            if (rawBurst) sharedCalibration = ref;
        }
        env->SetObjectArrayElement(references, i, ref);
        if (!rawBurst) env->DeleteLocalRef(ref);
        if (env->ExceptionCheck()) return nullptr;
    }
    if (sharedCalibration) env->DeleteLocalRef(sharedCalibration);
    env->SetLongArrayRegion(times, 0, stamps.size(), stamps.data());
    if (env->ExceptionCheck()) return nullptr;
    auto out = env->NewByteArray(raw.size());
    if (out) env->SetByteArrayRegion(out, 0, raw.size(), reinterpret_cast<const jbyte*>(raw.data()));
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_Wp09Usb_laser(JNIEnv*, jclass, jlong pointer, jboolean enabled) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    if (enabled) camera.laserMayBeOn = true;
    int result = camera.api.zoom(camera.handle, enabled ? laserOn : laserOff);
    if (!enabled && result == 0) camera.laserMayBeOn = false;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_Wp09Usb_close(JNIEnv*, jclass, jlong pointer) {
    delete reinterpret_cast<Camera*>(pointer);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_Wp09Usb_triggerShutter(JNIEnv*, jclass, jlong pointer) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    return camera.api.zoom(camera.handle, forceDynamicCalibration);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_thermal_wp09_Wp09Usb_temperatureTable(JNIEnv* env, jclass, jbyteArray frame,
                                               jfloatArray output, jfloat emissivity) {
    auto convert = thermometry();
    if (!convert || env->GetArrayLength(frame) != frameBytes
        || env->GetArrayLength(output) != tableSize) return false;

    constexpr int telemetryWords = width * 4;
    std::array<uint16_t, telemetryWords> telemetry{};
    env->GetByteArrayRegion(frame, width * (height - 4) * 2, telemetryWords * 2,
                           reinterpret_cast<jbyte*>(telemetry.data()));
    if (env->ExceptionCheck()) return false;

    // Defaults from the XWild preview implementation. The app's field calibration
    // is applied to the resulting Celsius image and never writes to the camera.
    float fpa = 0.0f;
    float correction = 0.0f;
    float reflection = 25.0f;
    float air = 25.0f;
    float humidity = 0.45f;
    uint16_t distance = 1;
    std::array<float, tableSize> table{};
    {
        static std::mutex vendorTableMutex;
        std::lock_guard<std::mutex> lock(vendorTableMutex);
        convert(width, height, table.data(), telemetry.data(), &fpa, &correction,
                &reflection, &air, &humidity, &emissivity, &distance, 130, 120, 0.0f);
    }
    env->SetFloatArrayRegion(output, 0, tableSize, table.data());
    return !env->ExceptionCheck();
}
