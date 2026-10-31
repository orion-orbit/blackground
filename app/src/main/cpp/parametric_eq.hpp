#pragma once
#include <cmath>
#include <cstdint>
#include <array>
#include <atomic>
#include <algorithm>

enum class FilterType : int { PEAKING = 0, LOW_SHELF = 1, HIGH_SHELF = 2 };

struct BandConfig {
    FilterType type = FilterType::PEAKING;
    double frequency = 1000.0;
    double gainDb = 0.0;
    double q = 0.707;
    bool enabled = true;
};

class BiquadFilter64 {
private:
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
    double z1_L = 0.0, z2_L = 0.0, z1_R = 0.0, z2_R = 0.0;
public:
    void resetState() noexcept { z1_L = z2_L = z1_R = z2_R = 0.0; }

    void configure(const BandConfig& cfg, double sampleRate) noexcept {
        if (!cfg.enabled || std::abs(cfg.gainDb) < 0.001 || sampleRate <= 0.0) {
            b0 = 1.0; b1 = b2 = a1 = a2 = 0.0;
            return;
        }
        const double A = std::pow(10.0, cfg.gainDb / 40.0);
        const double w0 = 2.0 * M_PI * std::clamp(cfg.frequency, 10.0, sampleRate * 0.48) / sampleRate;
        const double cos_w0 = std::cos(w0);
        const double sin_w0 = std::sin(w0);
        const double alpha = sin_w0 / (2.0 * std::max(0.1, cfg.q));
        double a0 = 1.0;

        switch (cfg.type) {
            case FilterType::PEAKING:
                b0 = 1.0 + alpha * A; b1 = -2.0 * cos_w0; b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A; a1 = -2.0 * cos_w0; a2 = 1.0 - alpha / A;
                break;
            case FilterType::LOW_SHELF: {
                const double sq = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) - (A - 1.0) * cos_w0 + sq);
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) - (A - 1.0) * cos_w0 - sq);
                a0 = (A + 1.0) + (A - 1.0) * cos_w0 + sq;
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cos_w0);
                a2 = (A + 1.0) + (A - 1.0) * cos_w0 - sq;
                break;
            }
            case FilterType::HIGH_SHELF: {
                const double sq = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) + (A - 1.0) * cos_w0 + sq);
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) + (A - 1.0) * cos_w0 - sq);
                a0 = (A + 1.0) - (A - 1.0) * cos_w0 + sq;
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cos_w0);
                a2 = (A + 1.0) - (A - 1.0) * cos_w0 - sq;
                break;
            }
        }
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
    }

    inline void processStereo(double& left, double& right) noexcept {
        const double outL = b0 * left + z1_L;
        z1_L = b1 * left - a1 * outL + z2_L;
        z2_L = b2 * left - a2 * outL;
        left = outL;
        const double outR = b0 * right + z1_R;
        z1_R = b1 * right - a1 * outR + z2_R;
        z2_R = b2 * right - a2 * outR;
        right = outR;
    }
};

class ParametricEqEngine {
public:
    static constexpr size_t NUM_BANDS = 5;
private:
    std::array<BiquadFilter64, NUM_BANDS> filters;
    std::array<BandConfig, NUM_BANDS> configs;
    std::atomic<bool> eq_enabled{false};
    double eq_preamp_linear = 1.0;
    double replay_gain_linear = 1.0;
    double total_preamp_linear = 1.0;
    double current_sample_rate = 48000.0;
    uint32_t prng_state = 0x12345678;

    inline double generateTpdfDither() noexcept {
        prng_state ^= prng_state << 13; prng_state ^= prng_state >> 17; prng_state ^= prng_state << 5;
        const double r1 = static_cast<double>(prng_state) / static_cast<double>(UINT32_MAX);
        prng_state ^= prng_state << 13; prng_state ^= prng_state >> 17; prng_state ^= prng_state << 5;
        const double r2 = static_cast<double>(prng_state) / static_cast<double>(UINT32_MAX);
        return (r1 - r2);
    }
public:
    void setEnabled(bool enabled) noexcept { eq_enabled.store(enabled, std::memory_order_release); }
    bool isEnabled() const noexcept { return eq_enabled.load(std::memory_order_acquire); }

    void setSampleRate(double rate) noexcept {
        current_sample_rate = rate;
        for (size_t i = 0; i < NUM_BANDS; ++i) {
            filters[i].configure(configs[i], current_sample_rate);
            filters[i].resetState();
        }
    }

    void setPreampDb(double preampDb) noexcept {
        eq_preamp_linear = std::pow(10.0, preampDb / 20.0);
        total_preamp_linear = eq_preamp_linear * replay_gain_linear;
    }

    void setReplayGainOffsetDb(double rgDb) noexcept {
        replay_gain_linear = std::pow(10.0, rgDb / 20.0);
        total_preamp_linear = eq_preamp_linear * replay_gain_linear;
    }

    void updateBand(size_t index, int type, double freq, double gainDb, double q, bool enabled) noexcept {
        if (index >= NUM_BANDS) return;
        configs[index] = {static_cast<FilterType>(type), freq, gainDb, q, enabled};
        filters[index].configure(configs[index], current_sample_rate);
    }

    void processFloatBuffer(float* buffer, int numFrames) noexcept {
        if (!isEnabled()) return;
        for (int i = 0; i < numFrames * 2; i += 2) {
            double left = static_cast<double>(buffer[i]) * total_preamp_linear;
            double right = static_cast<double>(buffer[i + 1]) * total_preamp_linear;
            for (size_t b = 0; b < NUM_BANDS; ++b) filters[b].processStereo(left, right);
            buffer[i]     = static_cast<float>(std::clamp(left, -1.0, 1.0));
            buffer[i + 1] = static_cast<float>(std::clamp(right, -1.0, 1.0));
        }
    }

    void processUsb24BitPacked(uint8_t* buffer, size_t numBytes) noexcept {
        if (!isEnabled()) return;
        constexpr double SCALE_IN  = 1.0 / 8388608.0;
        constexpr double SCALE_OUT = 8388607.0;
        for (size_t i = 0; i + 5 < numBytes; i += 6) {
            int32_t rawL = (buffer[i] | (buffer[i + 1] << 8) | (static_cast<int8_t>(buffer[i + 2]) << 16));
            int32_t rawR = (buffer[i + 3] | (buffer[i + 4] << 8) | (static_cast<int8_t>(buffer[i + 5]) << 16));
            double left  = static_cast<double>(rawL) * SCALE_IN * total_preamp_linear;
            double right = static_cast<double>(rawR) * SCALE_IN * total_preamp_linear;
            for (size_t b = 0; b < NUM_BANDS; ++b) filters[b].processStereo(left, right);
            int32_t outL = static_cast<int32_t>(std::lrint(std::clamp(left, -1.0, 1.0) * SCALE_OUT + generateTpdfDither()));
            int32_t outR = static_cast<int32_t>(std::lrint(std::clamp(right, -1.0, 1.0) * SCALE_OUT + generateTpdfDither()));
            outL = std::clamp(outL, -8388608, 8388607);
            outR = std::clamp(outR, -8388608, 8388607);
            buffer[i] = static_cast<uint8_t>(outL & 0xFF);
            buffer[i + 1] = static_cast<uint8_t>((outL >> 8) & 0xFF);
            buffer[i + 2] = static_cast<uint8_t>((outL >> 16) & 0xFF);
            buffer[i + 3] = static_cast<uint8_t>(outR & 0xFF);
            buffer[i + 4] = static_cast<uint8_t>((outR >> 8) & 0xFF);
            buffer[i + 5] = static_cast<uint8_t>((outR >> 16) & 0xFF);
        }
    }
};
