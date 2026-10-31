#pragma once
#include <atomic>
#include <cmath>
#include <cstdint>
#include <algorithm>

class RealTimeDrMeter {
private:
    std::atomic<float> peak_dbfs{-96.0f};
    std::atomic<float> rms_dbfs{-96.0f};
    std::atomic<float> dynamic_range_db{0.0f};
    std::atomic<bool>  clipping_detected{false};
    double smoothed_peak_sq = 1e-10;
    double smoothed_rms_sq  = 1e-10;

    void updateSmoothedMetrics(double block_peak_sq, double block_mean_sq) noexcept {
        if (block_peak_sq > smoothed_peak_sq) {
            smoothed_peak_sq = 0.55 * smoothed_peak_sq + 0.45 * block_peak_sq;
        } else {
            smoothed_peak_sq = 0.94 * smoothed_peak_sq + 0.06 * block_peak_sq;
        }
        smoothed_rms_sq = 0.92 * smoothed_rms_sq + 0.08 * block_mean_sq;
        const float pkDb  = static_cast<float>(10.0 * std::log10(std::max(smoothed_peak_sq, 1e-10)));
        const float rmsDb = static_cast<float>(10.0 * std::log10(std::max(smoothed_rms_sq, 1e-10)));
        const float crest = std::clamp(pkDb - rmsDb - 1.5f, 0.0f, 24.0f);
        peak_dbfs.store(std::clamp(pkDb, -96.0f, 0.0f), std::memory_order_relaxed);
        rms_dbfs.store(std::clamp(rmsDb, -96.0f, 0.0f), std::memory_order_relaxed);
        dynamic_range_db.store(crest, std::memory_order_relaxed);
        if (block_peak_sq >= 0.998001) clipping_detected.store(true, std::memory_order_relaxed);
    }
public:
    void analyzeFloatStereo(const float* buffer, size_t num_frames) noexcept {
        if (num_frames == 0) return;
        double sum_sq = 0.0, max_sq = 0.0;
        const size_t total = num_frames * 2;
        for (size_t i = 0; i < total; ++i) {
            const double s = static_cast<double>(buffer[i]);
            const double sq = s * s;
            sum_sq += sq;
            if (sq > max_sq) max_sq = sq;
        }
        updateSmoothedMetrics(max_sq, sum_sq / static_cast<double>(total));
    }

    void analyzeUsb24BitPacked(const uint8_t* buffer, size_t num_bytes) noexcept {
        if (num_bytes < 6) return;
        constexpr double INV_24BIT = 1.0 / 8388608.0;
        double sum_sq = 0.0, max_sq = 0.0;
        size_t count = 0;
        for (size_t i = 0; i + 5 < num_bytes; i += 6) {
            int32_t rawL = (buffer[i] | (buffer[i + 1] << 8) | (static_cast<int8_t>(buffer[i + 2]) << 16));
            int32_t rawR = (buffer[i + 3] | (buffer[i + 4] << 8) | (static_cast<int8_t>(buffer[i + 5]) << 16));
            const double sqL = (rawL * INV_24BIT) * (rawL * INV_24BIT);
            const double sqR = (rawR * INV_24BIT) * (rawR * INV_24BIT);
            sum_sq += (sqL + sqR);
            max_sq = std::max({max_sq, sqL, sqR});
            count += 2;
        }
        if (count > 0) updateSmoothedMetrics(max_sq, sum_sq / static_cast<double>(count));
    }

    float getPeakDbfs() const noexcept { return peak_dbfs.load(std::memory_order_relaxed); }
    float getRmsDbfs() const noexcept { return rms_dbfs.load(std::memory_order_relaxed); }
    float getDrScore() const noexcept { return dynamic_range_db.load(std::memory_order_relaxed); }
    bool  hasClipped() const noexcept { return clipping_detected.load(std::memory_order_relaxed); }
};
