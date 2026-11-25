#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

namespace {
constexpr int width = 256, height = 192, pixels = width * height;

// These short, nested critical regions only access memory. No I/O or Java callbacks.
template<class T>
class Elements {
public:
    Elements(JNIEnv* env, jarray array, bool output = false)
        : env(env), array(array), mode(output ? 0 : JNI_ABORT),
          ptr(static_cast<T*>(env->GetPrimitiveArrayCritical(array, nullptr))) {}
    ~Elements() { if (ptr) env->ReleasePrimitiveArrayCritical(array, ptr, mode); }
    JNIEnv* env;
    jarray array;
    jint mode;
    T* ptr;
};

struct Plane {
    uint8_t* base;
    int stride;
    int step;
};

struct Statistics {
    float low = std::numeric_limits<float>::infinity();
    float high = -std::numeric_limits<float>::infinity();
    int cold = -1, hot = -1, count = 0;
    double sum = 0;

    void add(int index, float value) {
        if (value < low) { cold = index; low = value; }
        if (value > high) { hot = index; high = value; }
        sum += value;
        count++;
    }

    int write(JNIEnv* env, jfloatArray result) const {
        float values[] = {static_cast<float>(cold), static_cast<float>(hot),
                          count ? static_cast<float>(sum / count) : NAN};
        env->SetFloatArrayRegion(result, 0, 3, values);
        return count;
    }
};

Plane plane(JNIEnv* env, jobject buffer, jint offset, jint stride, jint step, int w, int h) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong end = offset + static_cast<jlong>(stride) * (h - 1) + static_cast<jlong>(step) * (w - 1);
    bool valid = base && offset >= 0 && stride > 0 && step > 0 && end < env->GetDirectBufferCapacity(buffer);
    return {valid ? base + offset : nullptr, stride, step};
}

inline int red(uint32_t v) { return (v >> 16) & 255; }
inline int green(uint32_t v) { return (v >> 8) & 255; }
inline int blue(uint32_t v) { return v & 255; }
inline uint8_t luma(uint32_t v) { return 16 + ((66 * red(v) + 129 * green(v) + 25 * blue(v) + 128) >> 8); }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_PixelOps_convert(JNIEnv* env, jclass, jbyteArray source, jint offset,
                                      jint stride, jfloatArray table, jfloatArray output, jfloatArray stats,
                                      jboolean thermalOutput) {
    Statistics valid;
    int lowest = 65535, highest = -1, cold = -1, hot = -1;
    float center = NAN;
    {
        Elements<uint8_t> raw(env, source);
        Elements<float> lut(env, table);
        Elements<float> dest(env, output, true);
        if (!raw.ptr || !lut.ptr || !dest.ptr) return 0;
        for (int y = 0; y < height; y++) {
            const uint8_t* row = raw.ptr + offset + y * stride;
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int count = row[2*x] | (row[2*x + 1] << 8);
                float value = count > 0 && count < 16384 ? lut.ptr[count] : NAN;
                if (value >= -100 && value <= 1000) {
                    dest.ptr[i] = value;
                    valid.add(i, value);
                    if (thermalOutput) {
                        if (count < lowest) { lowest = count; cold = i; }
                        if (count > highest) { highest = count; hot = i; }
                    }
                } else dest.ptr[i] = NAN;
            }
        }
        if (thermalOutput && valid.count == pixels) {
            const uint8_t* mid = raw.ptr + offset + (height / 2) * stride + width;
            int a = mid[-2] | (mid[-1] << 8), b = mid[0] | (mid[1] << 8);
            center = lut.ptr[(a + b) >> 1];
            valid.cold = cold;
            valid.hot = hot;
        }
    }
    env->SetFloatArrayRegion(stats, 3, 1, &center);
    return valid.write(env, stats);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_thermal_wp09_PixelOps_maskAndMeasure(JNIEnv* env, jclass, jfloatArray readings, jfloatArray stats, jint firstColumn) {
    Statistics valid;
    {
        Elements<float> plane(env, readings, true);
        if (!plane.ptr) return 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            std::fill_n(plane.ptr + row, firstColumn, NAN);
            for (int x = firstColumn; x < width; x++) {
                float value = plane.ptr[row + x];
                if (std::isfinite(value)) valid.add(row + x, value);
            }
        }
    }
    return valid.write(env, stats);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_thermal_wp09_PixelOps_unpack(JNIEnv* env, jclass, jbyteArray source, jbyteArray output) {
    Elements<uint8_t> raw(env, source);
    Elements<uint8_t> dest(env, output, true);
    if (!raw.ptr || !dest.ptr) return false;
    unsigned chromaDiff = 0;
    for (int i = 0; i < pixels; i++) {
        dest.ptr[i] = raw.ptr[2 * i];
        chromaDiff |= raw.ptr[2 * i + 1] ^ 128u;
    }
    return chromaDiff == 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_renderTemperatures(JNIEnv* env, jclass, jfloatArray readings,
        jintArray palette, jintArray output, jfloat slope, jfloat offset, jfloat low, jfloat high) {
    Elements<float> src(env, readings);
    Elements<uint32_t> lut(env, palette);
    Elements<uint32_t> dest(env, output, true);
    if (!src.ptr || !lut.ptr || !dest.ptr) return;
    float factor = high > low ? 255.f / (high - low) : 0;
    for (int i = 0; i < pixels; i++) {
        float v = src.ptr[i];
        float shade = ((slope * v + offset) - low) * factor;
        int index = high == low ? 127 : static_cast<int>(std::min(255.f, std::max(0.f, shade)) + 0.5f);
        dest.ptr[i] = std::isfinite(v) ? lut.ptr[index] : 0xff000000;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_renderBrightness(JNIEnv* env, jclass, jbyteArray brightness,
                                               jintArray palette, jintArray output, jint firstColumn, jint sourceShift) {
    Elements<uint8_t> src(env, brightness);
    Elements<uint32_t> lut(env, palette);
    Elements<uint32_t> dest(env, output, true);
    if (!src.ptr || !lut.ptr || !dest.ptr) return;
    for (int y = 0; y < height; y++) {
        int row = y * width;
        std::fill_n(dest.ptr + row, firstColumn, 0xff000000u);
        for (int x = firstColumn; x < width; x++) dest.ptr[row + x] = lut.ptr[src.ptr[row + x - sourceShift]];
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_thermal_wp09_PixelOps_yuv(JNIEnv* env, jclass, jintArray pixelsIn,
        jobject yBuffer, jint yOffset, jint yStride, jint yStep,
        jobject uBuffer, jint uOffset, jint uStride, jint uStep,
        jobject vBuffer, jint vOffset, jint vStride, jint vStep) {
    Plane l = plane(env, yBuffer, yOffset, yStride, yStep, width, height);
    Plane u = plane(env, uBuffer, uOffset, uStride, uStep, width / 2, height / 2);
    Plane v = plane(env, vBuffer, vOffset, vStride, vStep, width / 2, height / 2);
    if (!l.base || !u.base || !v.base) return false;
    Elements<uint32_t> rgb(env, pixelsIn);
    if (!rgb.ptr) return false;
    for (int y = 0; y < height; y += 2) {
        auto* row = l.base + y * l.stride;
        auto* cb = u.base + y/2 * u.stride;
        auto* cr = v.base + y/2 * v.stride;
        for (int x = 0; x < width; x += 2) {
            auto* quad = rgb.ptr + y * width + x;
            auto a = quad[0], b = quad[1], c = quad[width], d = quad[width + 1];
            row[x * l.step] = luma(a);
            row[(x + 1) * l.step] = luma(b);
            row[l.stride + x * l.step] = luma(c);
            row[l.stride + (x + 1) * l.step] = luma(d);
            int r = (red(a) + red(b) + red(c) + red(d)) / 4;
            int g = (green(a) + green(b) + green(c) + green(d)) / 4;
            int blueAvg = (blue(a) + blue(b) + blue(c) + blue(d)) / 4;
            cb[x/2 * u.step] = 128 + ((-38*r - 74*g + 112*blueAvg + 128) >> 8);
            cr[x/2 * v.step] = 128 + ((112*r - 94*g - 18*blueAvg + 128) >> 8);
        }
    }
    return true;
}
