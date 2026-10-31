#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <array>
#include <algorithm>

enum class DsdCutoffFilter : int { STANDARD_30KHZ = 0, WIDEBAND_50KHZ = 1 };

class DsdToPcmDecimator {
public:
    static constexpr size_t NUM_TAPS = 128;
    static constexpr size_t NUM_BYTE_PHASES = NUM_TAPS / 8;
    static constexpr double DSD64_RATE = 2822400.0;
private:
    alignas(64) double lut[NUM_BYTE_PHASES][256]{};
    std::array<uint8_t, NUM_BYTE_PHASES> hist_L{};
    std::array<uint8_t, NUM_BYTE_PHASES> hist_R{};

    static constexpr uint8_t reverseBits(uint8_t b) noexcept {
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        return b;
    }
public:
    DsdToPcmDecimator() noexcept {
        resetHistory();
        rebuildLookupTable(DsdCutoffFilter::STANDARD_30KHZ);
    }

    void resetHistory() noexcept {
        hist_L.fill(0x69);
        hist_R.fill(0x69);
    }

    void rebuildLookupTable(DsdCutoffFilter mode) noexcept {
        const double cutoffHz = (mode == DsdCutoffFilter::WIDEBAND_50KHZ) ? 50000.0 : 30000.0;
        const double fc = cutoffHz / DSD64_RATE;
        const double center = (NUM_TAPS - 1) / 2.0;
        std::array<double, NUM_TAPS> taps{};
        double sum = 0.0;

        for (size_t i = 0; i < NUM_TAPS; ++i) {
            const double n = static_cast<double>(i) - center;
            const double sinc = (std::abs(n) < 1e-9) ? (2.0 * M_PI * fc) : (std::sin(2.0 * M_PI * fc * n) / n);
            const double w_phase = (2.0 * M_PI * i) / (NUM_TAPS - 1);
            const double window = 0.35875 - 0.48829 * std::cos(w_phase) + 0.14128 * std::cos(2.0 * w_phase) - 0.01168 * std::cos(3.0 * w_phase);
            taps[i] = sinc * window;
            sum += taps[i];
        }
        const double scale = 2.0 / sum;
        for (size_t i = 0; i < NUM_TAPS; ++i) taps[i] *= scale;

        for (size_t phase = 0; phase < NUM_BYTE_PHASES; ++phase) {
            for (int byteVal = 0; byteVal < 256; ++byteVal) {
                double acc = 0.0;
                for (int bit = 0; bit < 8; ++bit) {
                    const bool isOne = ((byteVal >> (7 - bit)) & 1) != 0;
                    acc += (isOne ? 1.0 : -1.0) * taps[phase * 8 + bit];
                }
                lut[phase][byteVal] = acc;
            }
        }
    }

    size_t decimateDsfBlockToFloatStereo(
        const uint8_t* left_dsd_4096,
        const uint8_t* right_dsd_4096,
        size_t bytes_per_channel,
        float* out_interleaved_float,
        bool is_lsb_first = true
    ) noexcept {
        size_t frames_generated = 0;
        for (size_t i = 0; i + 3 < bytes_per_channel; i += 4) {
            std::memmove(&hist_L[0], &hist_L[4], 12);
            std::memmove(&hist_R[0], &hist_R[4], 12);
            for (size_t k = 0; k < 4; ++k) {
                hist_L[12 + k] = is_lsb_first ? reverseBits(left_dsd_4096[i + k]) : left_dsd_4096[i + k];
                hist_R[12 + k] = is_lsb_first ? reverseBits(right_dsd_4096[i + k]) : right_dsd_4096[i + k];
            }
            double accL = 0.0, accR = 0.0;
            for (size_t p = 0; p < NUM_BYTE_PHASES; ++p) {
                accL += lut[p][hist_L[p]];
                accR += lut[p][hist_R[p]];
            }
            out_interleaved_float[frames_generated * 2]     = static_cast<float>(std::clamp(accL, -1.0, 1.0));
            out_interleaved_float[frames_generated * 2 + 1] = static_cast<float>(std::clamp(accR, -1.0, 1.0));
            ++frames_generated;
        }
        return frames_generated;
    }

    static size_t packFloatToUsb24Bit(const float* in_float, size_t num_frames, uint8_t* out_pcm24) noexcept {
        constexpr double SCALE_24 = 8388607.0;
        size_t byte_idx = 0;
        for (size_t f = 0; f < num_frames * 2; f += 2) {
            int32_t sL = static_cast<int32_t>(std::lrint(std::clamp<double>(in_float[f], -1.0, 1.0) * SCALE_24));
            int32_t sR = static_cast<int32_t>(std::lrint(std::clamp<double>(in_float[f + 1], -1.0, 1.0) * SCALE_24));
            out_pcm24[byte_idx++] = static_cast<uint8_t>(sL & 0xFF);
            out_pcm24[byte_idx++] = static_cast<uint8_t>((sL >> 8) & 0xFF);
            out_pcm24[byte_idx++] = static_cast<uint8_t>((sL >> 16) & 0xFF);
            out_pcm24[byte_idx++] = static_cast<uint8_t>(sR & 0xFF);
            out_pcm24[byte_idx++] = static_cast<uint8_t>((sR >> 8) & 0xFF);
            out_pcm24[byte_idx++] = static_cast<uint8_t>((sR >> 16) & 0xFF);
        }
        return byte_idx;
    }
};
