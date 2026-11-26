#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
constexpr int width = 256, height = 192, pixels = width * height;
constexpr int pitch = width + 1, prefixSize = pitch * (height + 1);
constexpr int tilesX = 16, tilesY = 12;
using Plane = std::array<float, pixels>;
using Signature = std::array<float, tilesX * tilesY>;

// Prefix sums over finite, active pixels keep all window sizes linear in image size.
// Thread-local scratch is shared between successive frames, never between viewer and encoder.
struct Workspace {
    Plane field{}, smooth{}, history{};
    std::array<uint8_t, pixels> gray{}, previous{};
    std::vector<uint8_t> enlarged;
    std::array<double, prefixSize> sums{};
    std::array<int, prefixSize> counts{};
    std::array<jint, pixels> colors{};
    std::vector<float> sample;

    void index(const Plane& plane, int left) {
        for (int y = 0; y < height; ++y) {
            double row = 0;
            int valid = 0, base = (y + 1) * pitch;
            for (int x = 0; x < width; ++x) {
                float v = plane[y * width + x];
                if (x >= left && std::isfinite(v)) { row += v; ++valid; }
                int i = base + x + 1;
                sums[i] = row + sums[i - pitch];
                counts[i] = valid + counts[i - pitch];
            }
        }
    }

    float mean(int x0, int y0, int x1, int y1) const {
        int a = y0 * pitch + x0, b = y0 * pitch + x1;
        int c = y1 * pitch + x0, d = y1 * pitch + x1;
        int n = counts[d] - counts[c] - counts[b] + counts[a];
        return n ? static_cast<float>((sums[d] - sums[c] - sums[b] + sums[a]) / n) : NAN;
    }

    float around(int x, int y, int radius) const {
        return mean(std::max(0, x - radius), std::max(0, y - radius),
                    std::min(width, x + radius + 1), std::min(height, y + radius + 1));
    }

    float quantile(int index) {
        std::nth_element(sample.begin(), sample.begin() + index, sample.end());
        return sample[index];
    }

    // Robust estimate from the diagonal high-pass coefficient of 2x2 cells.
    float noise(int left) {
        sample.clear();
        for (int y = 0; y < height - 1; y += 5) {
            for (int x = left + y % 3; x < width - 1; x += 5) {
                int i = y * width + x;
                float delta = field[i] - field[i + 1] - field[i + width] + field[i + width + 1];
                if (std::isfinite(delta)) sample.push_back(std::abs(delta));
            }
        }
        return sample.empty() ? 0 : quantile(static_cast<int>(sample.size() / 2)) * 0.7413f;
    }
};
thread_local Workspace ws;

float signatureDistance(const Signature& a, const Signature& b) {
    float sum = 0, squared = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        float diff = a[i] - b[i];
        sum += diff;
        squared += diff * diff;
    }
    // Ignore a uniform temperature shift when testing whether the scene moved.
    float avg = sum / a.size();
    return squared / a.size() - avg * avg;
}

bool addReference(int left, std::vector<Signature>& scenes, std::vector<Plane>& residuals) {
    ws.index(ws.field, left);
    Signature scene{};
    for (int y = 0; y < tilesY; ++y) for (int x = 0; x < tilesX; ++x) {
        float mean = ws.mean(x * 16, y * 16, (x + 1) * 16, (y + 1) * 16);
        if (!std::isfinite(mean)) return false;
        scene[y * tilesX + x] = mean;
    }
    for (const auto& previous : scenes)
        if (signatureDistance(previous, scene) < .20f * .20f) return false;
    float sigma = ws.noise(left);
    if (sigma < .05f) return false;
    scenes.push_back(scene);
    Plane& residual = residuals.emplace_back();
    residual.fill(NAN);
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x)
        ws.smooth[y * width + x] = ws.around(x, y, 2);
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float dx = ws.smooth[y * width + std::min(x + 2, width - 1)] -
                   ws.smooth[y * width + std::max(x - 2, left)];
        float dy = ws.smooth[std::min(y + 2, height - 1) * width + x] -
                   ws.smooth[std::max(y - 2, 0) * width + x];
        // Strong scene edges and isolated extremes cannot teach a detector offset.
        float detail = ws.field[i] - ws.around(x, y, 4);
        if (std::abs(dx) + std::abs(dy) < sigma * 1.5f && std::abs(detail) < sigma * 4)
            residual[i] = detail;
    }
    return true;
}


// The firmware gain changes between exposures. Fit its two monotone brightness
// segments and a slowly varying detector gradient to 16x16 tile averages.
// This model is used only to estimate a fixed visual correction for this clip.
constexpr int terms = 5;
using Features = std::array<double, terms>;

bool solve(double (&matrix)[terms][terms + 1], Features& fit) {
    for (int k = 0; k < terms; ++k) {
        int pivot = k;
        for (int i = k + 1; i < terms; ++i)
            if (std::abs(matrix[i][k]) > std::abs(matrix[pivot][k])) pivot = i;
        if (std::abs(matrix[pivot][k]) < 1e-5) return false;
        if (pivot != k) std::swap(matrix[pivot], matrix[k]);
        double divisor = matrix[k][k];
        for (int j = k; j <= terms; ++j) matrix[k][j] /= divisor;
        for (int i = 0; i < terms; ++i) {
            if (i == k) continue;
            double multiplier = matrix[i][k];
            for (int j = k; j <= terms; ++j) matrix[i][j] -= multiplier * matrix[k][j];
        }
    }
    for (int k = 0; k < terms; ++k) fit[k] = matrix[k][terms];
    return true;
}

float coordinate(float v, int extent) { return 2 * v / (extent - 1) - 1; }

void addPairedResidual(int left, const std::array<jbyte, pixels>& luma, const Signature& scene,
                       std::vector<Plane>& guided) {
    std::array<Features, tilesX * tilesY> tiles;
    double normal[terms][terms + 1]{};
    for (int i = 0; i < tilesX * tilesY; ++i) {
        int x0 = std::max(left, (i % tilesX) * 16), x1 = (i % tilesX + 1) * 16;
        int y0 = (i / tilesX) * 16;
        double first = 0, second = 0;
        for (int y = y0; y < y0 + 16; ++y) for (int x = x0; x < x1; ++x) {
            float v = static_cast<uint8_t>(luma[y * width + x - left]) / 255.f;
            first += v;
            second += std::max(v - .30f, 0.f);
        }
        int n = (x1 - x0) * 16;
        auto& row = tiles[i];
        row = {1, coordinate((x0 + x1 - 1) * .5f, width), coordinate(y0 + 7.5f, height),
               first / n, second / n};
        for (int r = 0; r < terms; ++r) {
            for (int c = 0; c < terms; ++c) normal[r][c] += row[r] * row[c];
            normal[r][terms] += row[r] * scene[i];
        }
    }
    Features fit;
    if (!solve(normal, fit) || fit[3] <= 0 || fit[3] + fit[4] <= 0) return;
    double error = 0, signal = 0, mean = 0;
    for (size_t i = 0; i < tiles.size(); ++i) {
        const auto& row = tiles[i];
        double observed = scene[i] - fit[0] - fit[1] * row[1] - fit[2] * row[2];
        double delta = observed - fit[3] * row[3] - fit[4] * row[4];
        error += delta * delta;
        signal += observed * observed;
        mean += observed;
    }
    signal -= mean * mean / tiles.size();
    float sigma = ws.noise(left);
    if (signal < .01 * tiles.size() || error > .22 * signal ||
            std::abs(fit[1]) + std::abs(fit[2]) > 4 * sigma) return;

    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float v = static_cast<uint8_t>(luma[i - left]) / 255.f;
        ws.smooth[i] = ws.field[i] - static_cast<float>(fit[3] * v + fit[4] * std::max(v - .30f, 0.f));
    }
    ws.index(ws.smooth, left);
    Plane& residual = guided.emplace_back();
    residual.fill(NAN);
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float detail = ws.smooth[i] - ws.around(x, y, 16);
        if (std::abs(detail) <= 4 * sigma)
            residual[i] = detail + static_cast<float>(fit[1] * coordinate(x, width) +
                                                      fit[2] * coordinate(y, height));
    }
}

// Estimate the common offset between adjacent rows across the field. A large step
// starts a new segment, so the filter never averages across a horizontal object edge.
void correctRows(int left, float sigma) {
    if (sigma < .002f) return;
    const float stepLimit = std::min(.15f, 2.f * sigma);
    std::array<float, height> level{};
    std::array<int, height> segment{};
    std::array<float, width / 4> differences;
    for (int y = 1; y < height; ++y) {
        int n = 0;
        for (int x = left; x < width; x += 4) {
            int i = y * width + x;
            float d = ws.field[i] - ws.field[i - width];
            if (std::isfinite(d)) differences[n++] = d;
        }
        bool boundary = n < 16;
        float step = 0;
        if (!boundary) {
            std::nth_element(differences.begin(), differences.begin() + n / 2, differences.begin() + n);
            step = differences[n / 2];
            boundary = std::abs(step) > stepLimit;
        }
        segment[y] = segment[y - 1] + boundary;
        level[y] = boundary ? 0 : level[y - 1] + step;
    }
    for (int y = 0; y < height; ++y) {
        float residual = 0;
        int pairs = 0;
        for (int d = 1; d <= 4 && y >= d && y + d < height; ++d) {
            if (segment[y - d] != segment[y + d]) break;
            residual += 2 * level[y] - level[y - d] - level[y + d];
            ++pairs;
        }
        if (pairs == 0) continue;
        float bias = std::clamp(residual / (2 * pairs + 1), -sigma, sigma);
        for (int x = left; x < width; ++x) ws.field[y * width + x] -= bias;
    }
}

// Phone-side tuning of the radiometric channel. Independent flat/texture gains
// avoid turning smooth backgrounds into grain when enhancing small details.
struct Tuning {
    float temporal, flat, texture, sharpness, contrast, trim, shadow;
};
constexpr Tuning profiles[][2] = {
    {{.66f,.30f,1.f,.48f,.98f,.010f,0.f}, {.64f,.25f,.94f,.42f,.95f,.009f,.10f}},
    {{.57f,.22f,1.10f,.85f,.91f,.006f,.06f}, {.56f,.20f,1.04f,.75f,.90f,.006f,.14f}},
    {{.38f,.18f,1.25f,1.05f,1.06f,.003f,.04f}, {.36f,.16f,1.17f,.97f,1.02f,.003f,.11f}},
};

// Input and knees are fractions of the outer temperature window. Tiny tails
// keep their original slope when they already occupy less than 5% of the scale.
struct IronCurve {
    float low, high, weight, lowLevel, highLevel, coldSlope, middleSlope, hotSlope;

    IronCurve(float lower, float upper, int percent)
        : low(lower), high(upper), weight(std::clamp((percent - 100) / 50.f, 0.f, 1.f)),
          lowLevel(std::min(.05f, low)), highLevel(std::max(.95f, high)),
          coldSlope(low > 0 ? lowLevel / low : 1.f),
          middleSlope(high > low ? (highLevel - lowLevel) / (high - low) : 1.f),
          hotSlope(high < 1 ? (1.f - highLevel) / (1.f - high) : 1.f) {}

    float shade(float x, float shadow) const {
        float basic = x + shadow * x * (1.f - x);
        float target = x < low ? x * coldSlope : x > high ? highLevel + (x - high) * hotSlope
                                                       : lowLevel + (x - low) * middleSlope;
        return basic + weight * (target - basic);
    }
};

// [outer low, outer high, shadow lift, inner low, inner high] in sensor Celsius.
using Window = std::array<float, 5>;

void ironWindow(Window& window, float outerLow, float outerHigh, float minSpan, float follow, bool enhanced) {
    int tail = static_cast<int>(ws.sample.size() / 8);
    float innerLow = ws.quantile(tail);
    float innerHigh = ws.quantile(static_cast<int>(ws.sample.size()) - 1 - tail);
    if (follow < 1 && window[1] > window[0] && outerLow < window[1] && outerHigh > window[0]) {
        auto smooth = [follow](float past, float now) { return past + follow * (now - past); };
        outerLow = smooth(window[0], outerLow);
        outerHigh = smooth(window[1], outerHigh);
        innerLow = smooth(window[3], innerLow);
        innerHigh = smooth(window[4], innerHigh);
    }
    if (enhanced && outerHigh - outerLow < minSpan) {
        // A sudden increase in noise takes precedence over slow automatic tracking.
        float center = .5f * (outerLow + outerHigh);
        outerLow = center - .5f * minSpan;
        outerHigh = center + .5f * minSpan;
    }
    // Protect a nearly isothermal surface from having its noise fill the palette.
    float half = .5f * std::max(innerHigh - innerLow, minSpan);
    float center = .5f * (innerLow + innerHigh);
    if (2 * half >= outerHigh - outerLow) {
        innerLow = outerLow;
        innerHigh = outerHigh;
    } else {
        innerLow = std::clamp(center - half, outerLow, outerHigh - 2 * half);
        innerHigh = std::min(innerLow + 2 * half, outerHigh);
    }
    window = {outerLow, outerHigh, window[2], innerLow, innerHigh};
}

// Both windows consume the same denoised temperatures, before clipping to 8-bit gray.
void tone(int left, const jint* lut, const Tuning* profile, float mid, float span, const IronCurve* iron = nullptr) {
    ws.index(ws.smooth, left);
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float v = ws.smooth[i];
        if (!std::isfinite(v)) {
            if (!lut) ws.gray[i] = 0;
            continue;
        }
        float detail = std::clamp(v - ws.around(x, y, 9), -.15f * span, .15f * span);
        float signal = profile ? profile->contrast * (v - mid + profile->sharpness * detail) : v - mid + .5f * detail;
        float shade = span > 0.f ? std::clamp(.5f + signal / span, 0.f, 1.f) : .5f;
        if (iron) shade = iron->shade(shade, profile->shadow);
        else if (profile) shade += profile->shadow * shade * (1.f - shade);
        int level = static_cast<int>(shade * 255 + .5f);
        if (lut) ws.colors[i] = lut[level];
        else ws.gray[i] = static_cast<uint8_t>(level);
    }
}

void render(int left, const jint* lut, bool thermalOutput, float sigma, const Tuning* profile = nullptr,
            Window* autoWindow = nullptr, float fixedLow = NAN, float fixedHigh = NAN,
            int ironPercent = 0, float fixedMiddleLow = 0, float fixedMiddleHigh = 1, float follow = 1) {
    if (thermalOutput) correctRows(left, sigma);
    ws.index(ws.field, left);
    float preserve = .18f / (.18f + sigma);
    ws.sample.clear();
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float center = ws.field[i];
        if (!std::isfinite(center)) { ws.smooth[i] = NAN; continue; }
        float near = ws.around(x, y, 1), far = ws.around(x, y, 3);
        float edge = std::abs(near - far);
        float keep = (edge + .10f) / (edge + .10f + .27f * sigma);
        float filtered = far + keep * (near + preserve * (center - near) - far);
        if (thermalOutput) {
            // Suppress pixel noise on flat surfaces. Keep narrow features whose
            // contrast exceeds the noise measured in this exposure.
            float noise = std::max(sigma, .002f);
            float signal = center - near;
            float pixelWeight = signal * signal / (signal * signal + 9 * noise * noise);
            float edgeWeight = edge / (edge + noise);
            float flat = profile ? profile->flat : .30f;
            float texture = profile ? profile->texture : 1.f;
            filtered = far + (flat + (1.f - flat) * edgeWeight) * (near - far) + texture * pixelWeight * signal;
        }
        ws.smooth[i] = filtered;
        if (y % 3 == 0 && (x + y / 3) % 4 == 0) ws.sample.push_back(filtered);
    }
    if (ws.sample.empty()) {
        for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x)
            if (std::isfinite(ws.smooth[y * width + x])) ws.sample.push_back(ws.smooth[y * width + x]);
    }
    if (lut) std::fill(ws.colors.begin(), ws.colors.end(), static_cast<jint>(0xff000000u));
    else ws.gray.fill(0);
    if (ws.sample.empty()) {
        if (autoWindow) autoWindow->fill(NAN);
        return;
    }
    int trim = profile ? static_cast<int>(ws.sample.size() * profile->trim) : static_cast<int>(ws.sample.size() / 100);
    float low = ws.quantile(trim), high = ws.quantile(static_cast<int>(ws.sample.size()) - 1 - trim);
    float minSpan = thermalOutput ? std::max(1.f, 20 * sigma) : 1.f;
    float span = std::max(high - low, minSpan), mid = (low + high) * .5f;
    if (autoWindow) {
        float half = span / (2.f * profile->contrast);
        ironWindow(*autoWindow, mid - half, mid + half, minSpan, follow, ironPercent > 100);
        if (ironPercent > 100) {
            mid = ((*autoWindow)[0] + (*autoWindow)[1]) * .5f;
            span = ((*autoWindow)[1] - (*autoWindow)[0]) * profile->contrast;
        }
    }
    if (std::isfinite(fixedLow) && std::isfinite(fixedHigh)) {
        mid = (fixedLow + fixedHigh) * .5f;
        span = (fixedHigh - fixedLow) * profile->contrast;
    } else if (ironPercent > 100 && autoWindow) {
        float range = (*autoWindow)[1] - (*autoWindow)[0];
        fixedMiddleLow = ((*autoWindow)[3] - (*autoWindow)[0]) / range;
        fixedMiddleHigh = ((*autoWindow)[4] - (*autoWindow)[0]) / range;
    }
    IronCurve iron(fixedMiddleLow, fixedMiddleHigh, ironPercent);
    tone(left, lut, profile, mid, span, ironPercent > 100 ? &iron : nullptr);
}
} // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_thermal_wp09_PixelOps_displayBias(JNIEnv* env, jclass, jobjectArray samples,
                                            jobjectArray lumaPlanes, jint left) {
    int n = env->GetArrayLength(samples);
    if (n < 3 || n > 8 || left < 0 || left >= width) return nullptr;
    std::vector<Plane> residuals;
    residuals.reserve(n);
    std::vector<Signature> scenes;
    std::vector<Plane> guided;
    guided.reserve(n);
    std::array<jbyte, pixels> luma;
    for (int i = 0; i < n; ++i) {
        auto source = static_cast<jfloatArray>(env->GetObjectArrayElement(samples, i));
        if (!source || env->GetArrayLength(source) != pixels) return nullptr;
        env->GetFloatArrayRegion(source, 0, pixels, ws.field.data());
        env->DeleteLocalRef(source);
        if (env->ExceptionCheck()) return nullptr;
        if (!addReference(left, scenes, residuals)) continue;
        auto reference = static_cast<jbyteArray>(env->GetObjectArrayElement(lumaPlanes, i));
        if (reference) {
            env->GetByteArrayRegion(reference, 0, pixels, luma.data());
            env->DeleteLocalRef(reference);
            if (env->ExceptionCheck()) return nullptr;
            addPairedResidual(left, luma, scenes.back(), guided);
        }
    }
    if (residuals.size() < 3) return nullptr;
    const auto& references = guided.size() >= 3 ? guided : residuals;
    for (int p = 0; p < pixels; ++p) {
        std::array<float, 8> values;
        int used = 0;
        for (const auto& r : references) if (std::isfinite(r[p])) values[used++] = r[p];
        float bias = 0;
        if (used >= 3) {
            std::sort(values.begin(), values.begin() + used);
            bias = .5f * (values[(used - 1) / 2] + values[used / 2]);
        }
        ws.smooth[p] = bias;
    }
    jfloatArray result = env->NewFloatArray(pixels);
    if (result) env->SetFloatArrayRegion(result, 0, pixels, ws.smooth.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_renderMobile(JNIEnv* env, jclass, jfloatArray readings, jint left,
                                             jboolean thermalOutput, jfloatArray bias, jintArray palette, jintArray output) {
    std::array<jint, 256> lut;
    env->GetFloatArrayRegion(readings, 0, pixels, ws.field.data());
    env->GetIntArrayRegion(palette, 0, lut.size(), lut.data());
    if (bias) {
        env->GetFloatArrayRegion(bias, 0, pixels, ws.smooth.data());
        for (int i = 0; i < pixels; ++i) ws.field[i] -= ws.smooth[i];
    }
    if (env->ExceptionCheck()) return;
    render(left, lut.data(), thermalOutput, ws.noise(left));
    env->SetIntArrayRegion(output, 0, pixels, ws.colors.data());
}

namespace {
using Gray = std::array<uint8_t, pixels>;

float sampleAt(const Gray& plane, int i, float dx, float dy) {
    int right = dx < 0 ? -1 : 1, down = dy < 0 ? -width : width;
    float u = std::abs(dx), v = std::abs(dy);
    return (1 - v) * ((1 - u) * plane[i] + u * plane[i + right])
             + v * ((1 - u) * plane[i + down] + u * plane[i + down + right]);
}

// Estimate only small, well-constrained translations. An aperture, a scene cut
// or a large motion falls back to the current exposure's edge-directed image.
bool registerPrevious(float& dx, float& dy) {
    double xx = 0, xy = 0, yy = 0, xt = 0, yt = 0, error = 0;
    int used = 0;
    for (int y = 3; y < height - 3; y += 4) for (int x = 3; x < width - 3; x += 4) {
        int i = y * width + x;
        float gx = .5f * (ws.previous[i+1] - ws.previous[i-1]);
        float gy = .5f * (ws.previous[i+width] - ws.previous[i-width]);
        float dt = ws.gray[i] - ws.previous[i];
        if (std::abs(dt) > 32) continue;
        xx += gx * gx; xy += gx * gy; yy += gy * gy;
        xt += gx * dt; yt += gy * dt; error += dt * dt; ++used;
    }
    double det = xx * yy - xy * xy;
    if (used < 1400 || error < used || det < .012 * (xx + yy) * (xx + yy) || det < 1) return false;
    dx = static_cast<float>((yy * xt - xy * yt) / det);
    dy = static_cast<float>((xx * yt - xy * xt) / det);
    float motion = std::max(std::abs(dx), std::abs(dy));
    if (motion < .13f || motion > .88f) return false;
    double after = 0;
    for (int y = 3; y < height - 3; y += 4) for (int x = 3; x < width - 3; x += 4) {
        int i = y * width + x;
        if (std::abs(ws.gray[i] - ws.previous[i]) > 32) continue;
        float d = ws.gray[i] - sampleAt(ws.previous, i, dx, dy);
        after += d * d;
    }
    return after < error * .70;
}

// Two output samples between adjacent detector centers are at 1/4 and 3/4.
// Compute all four subpixels of a cell together, sharing its diagonal and gains.
void enlargeCurrent() {
    constexpr int stride = width * 2;
    ws.enlarged.resize(pixels * 4);
    for (int y = 0; y < height - 1; ++y) {
        auto* top = ws.enlarged.data() + (2*y + 1) * stride + 1;
        auto* bottom = top + stride;
        const uint8_t* upper = ws.gray.data() + y * width;
        const uint8_t* lower = upper + width;
        for (int x = 0; x < width - 1; ++x) {
            int a = upper[x], b = upper[x+1], c = lower[x], d = lower[x+1];
            int ad = std::abs(a-d), bc = std::abs(b-c);
            // The sum of bilinear interpolation and a weighted diagonal
            // correction is a convex blend of linear and triangular estimates.
            int gain = (a-b-c+d) * std::min(24, std::abs(ad-bc));
            int corners = ad < bc ? 3*gain : -gain;
            int sides = ad < bc ? gain : -3*gain;
            int leftTop = 3*a + b, rightTop = a + 3*b;
            int leftBottom = 3*c + d, rightBottom = c + 3*d;
            top[2*x] = ((3*leftTop+leftBottom)*24 + corners + 192) / 384;
            top[2*x+1] = ((3*rightTop+rightBottom)*24 + sides + 192) / 384;
            bottom[2*x] = ((leftTop+3*leftBottom)*24 + sides + 192) / 384;
            bottom[2*x+1] = ((rightTop+3*rightBottom)*24 + corners + 192) / 384;
        }
        for (int col : {0, width-1}) {
            int a = upper[col], b = lower[col], out = col == 0 ? -1 : stride-2;
            top[out] = (3*a+b+2)/4;
            bottom[out] = (a+3*b+2)/4;
        }
    }
    for (int y : {0, height-1}) {
        auto* row = ws.enlarged.data() + (y == 0 ? 0 : 2*height-1)*stride;
        const uint8_t* source = ws.gray.data() + y*width;
        for (int x = 0; x < width-1; ++x) {
            row[2*x+1] = (3*source[x]+source[x+1]+2)/4;
            row[2*x+2] = (source[x]+3*source[x+1]+2)/4;
        }
        row[0] = source[0];
        row[stride-1] = source[width-1];
    }
}

void reconstruct(bool paired) {
    enlargeCurrent();
    float dx = 0, dy = 0;
    if (!paired || !registerPrevious(dx, dy)) return;
    ws.smooth.fill(0);
    for (int y = 1; y < height-1; ++y) for (int x = 1; x < width-1; ++x) {
        int i = y*width+x;
        float error = ws.previous[i] - sampleAt(ws.gray, i, -dx, -dy);
        // Occlusions and locally moving features must not teach a false detail.
        if (std::abs(error) < 18) ws.smooth[i] = error * (1.f - std::abs(error)/18.f);
    }
    std::array<int, 2*width> col{};
    std::array<float, 2*width> weight{};
    for (int x = 0; x < width*2; ++x) {
        float pos = .5f*x - .25f + dx;
        col[x] = std::clamp(static_cast<int>(pos+.5f), 0, width-1);
        weight[x] = 1.f - std::abs(pos-col[x]);
    }
    for (int y = 0; y < height*2; ++y) {
        float pos = .5f*y - .25f + dy;
        int py = std::clamp(static_cast<int>(pos+.5f), 0, height-1);
        const float* residual = ws.smooth.data()+py*width;
        float wy = 1.f - std::abs(pos-py);
        auto* out = ws.enlarged.data()+y*2*width;
        for (int x = 0; x < width*2; ++x) {
            float value = out[x] + residual[col[x]]*wy*weight[x];
            out[x] = static_cast<uint8_t>(static_cast<int>(std::clamp(value, 0.f, 255.f)+.5f));
        }
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_liveLuma(JNIEnv* env, jclass, jfloatArray readings, jfloatArray history,
        jint scene, jboolean black, jboolean continuous, jbyteArray previous, jbyteArray low, jbyteArray high,
        jfloatArray autoWindow, jfloatArray filtered, jfloat fixedLow, jfloat fixedHigh, jint ironPercent,
        jfloat middleLow, jfloat middleHigh, jfloat follow, jfloatArray previousField) {
    const Tuning& profile = profiles[std::clamp(scene, 0, 2)][black ? 1 : 0];
    env->GetFloatArrayRegion(readings, 0, pixels, ws.field.data());
    float sigma = ws.noise(0);
    if (continuous) {
        env->GetFloatArrayRegion(history, 0, pixels, ws.history.data());
        float threshold = std::max(.05f, sigma * 4.f);
        for (int i = 0; i < pixels; ++i) {
            float delta = ws.history[i] - ws.field[i];
            float t = delta / threshold;
            float agreement = std::max(0.f, 1.f - t*t);
            ws.field[i] += delta * profile.temporal * agreement * agreement;
        }
    }
    env->SetFloatArrayRegion(history, 0, pixels, ws.field.data());
    Window window = {NAN, NAN, profile.shadow, NAN, NAN};
    if (autoWindow && follow < 1) env->GetFloatArrayRegion(autoWindow, 0, window.size(), window.data());
    render(0, nullptr, true, sigma, &profile, &window, fixedLow, fixedHigh,
           ironPercent, middleLow, middleHigh, follow);
    if (autoWindow) env->SetFloatArrayRegion(autoWindow, 0, window.size(), window.data());
    if (filtered) env->SetFloatArrayRegion(filtered, 0, pixels, ws.smooth.data());
    bool paired = high && continuous && previous;
    if (paired && !previousField)
        env->GetByteArrayRegion(previous, 0, pixels, reinterpret_cast<jbyte*>(ws.previous.data()));
    env->SetByteArrayRegion(low, 0, pixels, reinterpret_cast<const jbyte*>(ws.gray.data()));
    if (high) {
        if (previousField && continuous && window[1] > window[0]) {
            // Reproject the previous exposure through today's adaptive curve before registration.
            float range = window[1] - window[0];
            IronCurve iron((window[3] - window[0]) / range, (window[4] - window[0]) / range, ironPercent);
            env->GetFloatArrayRegion(previousField, 0, pixels, ws.smooth.data());
            ws.previous = ws.gray;
            tone(0, nullptr, &profile, (window[0] + window[1]) * .5f, range * profile.contrast, &iron);
            std::swap(ws.gray, ws.previous);
            paired = true;
        }
        reconstruct(paired);
        env->SetByteArrayRegion(high, 0, pixels * 4, reinterpret_cast<const jbyte*>(ws.enlarged.data()));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_windowLuma(JNIEnv* env, jclass, jfloatArray filtered, jfloatArray previous,
        jint scene, jboolean black, jfloat low, jfloat high, jint ironPercent,
        jfloat middleLow, jfloat middleHigh, jbyteArray output) {
    const Tuning& profile = profiles[std::clamp(scene, 0, 2)][black ? 1 : 0];
    float mid = (low + high) * .5f, span = (high - low) * profile.contrast;
    IronCurve iron(middleLow, middleHigh, ironPercent);
    const IronCurve* curve = ironPercent > 100 ? &iron : nullptr;
    bool doubled = env->GetArrayLength(output) == pixels * 4;
    if (previous && doubled) {
        // Register the preceding exposure in the same range as the current one.
        env->GetFloatArrayRegion(previous, 0, pixels, ws.smooth.data());
        tone(0, nullptr, &profile, mid, span, curve);
        ws.previous = ws.gray;
    }
    env->GetFloatArrayRegion(filtered, 0, pixels, ws.smooth.data());
    tone(0, nullptr, &profile, mid, span, curve);
    if (doubled) reconstruct(previous != nullptr);
    const uint8_t* result = doubled ? ws.enlarged.data() : ws.gray.data();
    env->SetByteArrayRegion(output, 0, doubled ? pixels * 4 : pixels, reinterpret_cast<const jbyte*>(result));
}

extern "C" JNIEXPORT void JNICALL
Java_org_thermal_wp09_PixelOps_ironLegend(JNIEnv* env, jclass, jfloat low, jfloat high, jint percent,
        jfloat shadow, jintArray palette, jintArray legend) {
    IronCurve curve(low, high, percent);
    std::array<jint, 256> colors, result;
    env->GetIntArrayRegion(palette, 0, colors.size(), colors.data());
    for (int i = 0; i < 256; ++i)
        result[i] = colors[std::clamp(static_cast<int>(255 * curve.shade(i / 255.f, shadow) + .5f), 0, 255)];
    env->SetIntArrayRegion(legend, 0, result.size(), result.data());
}
