#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>

namespace {
constexpr int width = 256;
constexpr int height = 196;
constexpr int frameBytes = width * height * 2;
constexpr int sampleCapacity = 512 * 1024;
constexpr int tableSize = 16384;
constexpr int laserOff = 0x8085;
constexpr int laserOn = 0x8086;

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
    std::mutex mutex;
    std::array<jbyte, sampleCapacity> bytes;
    size_t length = 0;
    bool unread = false;

    ~Camera() {
        if (laserMayBeOn) api.zoom(handle, laserOff);
        if (streaming) api.stop(handle);
        if (handle) api.close(handle);
        if (device) api.unref(device);
        if (context) api.exit(context);
        if (fd >= 0) ::close(fd);
    }
};

void onFrame(unsigned char* data, void* opaque, size_t length) {
    auto& camera = *static_cast<Camera*>(opaque);
    std::lock_guard<std::mutex> lock(camera.mutex);
    if (length <= camera.bytes.size()) std::memcpy(camera.bytes.data(), data, length);
    camera.length = length;
    camera.unread = true;
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
        result = api.configure(camera->handle, config, yuyv, width, height, 1, 50);
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

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_Wp09Usb_read(JNIEnv* env, jclass, jlong pointer, jbyteArray output) {
    auto& camera = *reinterpret_cast<Camera*>(pointer);
    std::lock_guard<std::mutex> lock(camera.mutex);
    if (!camera.unread) return 0;
    camera.unread = false;
    if (camera.length != frameBytes) return -static_cast<int>(camera.length);
    env->SetByteArrayRegion(output, 0, frameBytes, camera.bytes.data());
    return frameBytes;
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
    convert(width, height, table.data(), telemetry.data(), &fpa, &correction,
            &reflection, &air, &humidity, &emissivity, &distance, 130, 120, 0.0f);
    env->SetFloatArrayRegion(output, 0, tableSize, table.data());
    return !env->ExceptionCheck();
}
