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
    Plane field{}, smooth{};
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

void render(int left, const jint* lut, bool thermalOutput) {
    float sigma = ws.noise(left);
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
            filtered = far + (.30f + .70f * edgeWeight) * (near - far) + pixelWeight * signal;
        }
        ws.smooth[i] = filtered;
        if (y % 3 == 0 && (x + y / 3) % 4 == 0) ws.sample.push_back(filtered);
    }
    if (ws.sample.empty()) {
        for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x)
            if (std::isfinite(ws.smooth[y * width + x])) ws.sample.push_back(ws.smooth[y * width + x]);
    }
    std::fill(ws.colors.begin(), ws.colors.end(), static_cast<jint>(0xff000000u));
    if (ws.sample.empty()) return;
    int trim = static_cast<int>(ws.sample.size() / 100);
    float low = ws.quantile(trim), high = ws.quantile(static_cast<int>(ws.sample.size()) - 1 - trim);
    float minSpan = thermalOutput ? std::max(1.f, 20 * sigma) : 1.f;
    float span = std::max(high - low, minSpan), mid = (low + high) * .5f;
    ws.index(ws.smooth, left);
    for (int y = 0; y < height; ++y) for (int x = left; x < width; ++x) {
        int i = y * width + x;
        float v = ws.smooth[i];
        if (!std::isfinite(v)) continue;
        float detail = std::clamp(v - ws.around(x, y, 9), -.15f * span, .15f * span);
        float shade = std::clamp(.5f + (v - mid + .5f * detail) / span, 0.f, 1.f);
        ws.colors[i] = lut[static_cast<int>(shade * 255 + .5f)];
    }
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
    render(left, lut.data(), thermalOutput);
    env->SetIntArrayRegion(output, 0, pixels, ws.colors.data());
}
