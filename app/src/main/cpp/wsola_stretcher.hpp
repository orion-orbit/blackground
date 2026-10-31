#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <atomic>
#include <algorithm>

class WsolaTimeStretcher {
public:
    static constexpr size_t WINDOW_FRAMES = 1024;
    static constexpr size_t HALF_WINDOW   = WINDOW_FRAMES / 2;
    static constexpr size_t SEARCH_RANGE  = 128;
private:
    std::atomic<float> speed_ratio{1.0f};
    std::vector<float> hann_window;
    std::vector<float> input_fifo;
    std::vector<float> overlap_tail;
    std::vector<float> prev_synth_tail;
    bool is_primed = false;
public:
    WsolaTimeStretcher() :
        hann_window(WINDOW_FRAMES),
        overlap_tail(HALF_WINDOW * 2, 0.0f),
        prev_synth_tail(HALF_WINDOW * 2, 0.0f)
    {
        input_fifo.reserve(WINDOW_FRAMES * 8);
        for (size_t i = 0; i < WINDOW_FRAMES; ++i) {
            hann_window[i] = 0.5f * (1.0f - std::cos((2.0f * static_cast<float>(M_PI) * i) / (WINDOW_FRAMES - 1)));
        }
    }

    void setSpeed(float ratio) noexcept { speed_ratio.store(std::clamp(ratio, 0.50f, 2.00f), std::memory_order_release); }
    float getSpeed() const noexcept { return speed_ratio.load(std::memory_order_acquire); }
    bool isActive() const noexcept { return std::abs(getSpeed() - 1.0f) > 0.005f; }

    void reset() noexcept {
        input_fifo.clear();
        std::fill(overlap_tail.begin(), overlap_tail.end(), 0.0f);
        std::fill(prev_synth_tail.begin(), prev_synth_tail.end(), 0.0f);
        is_primed = false;
    }

    size_t processStereoFloat(const float* in_frames, size_t num_in_frames, std::vector<float>& out_buffer) noexcept {
        const float alpha = getSpeed();
        if (std::abs(alpha - 1.0f) <= 0.005f) {
            out_buffer.assign(in_frames, in_frames + num_in_frames * 2);
            return num_in_frames;
        }
        input_fifo.insert(input_fifo.end(), in_frames, in_frames + num_in_frames * 2);
        out_buffer.clear();
        const size_t nominal_hop = static_cast<size_t>(std::lrint(HALF_WINDOW * alpha));
        const size_t min_req = WINDOW_FRAMES + SEARCH_RANGE * 2 + nominal_hop;

        while ((input_fifo.size() / 2) >= min_req) {
            size_t best_offset = 0;
            if (is_primed) {
                float max_corr = -1e30f;
                for (size_t delta = 0; delta <= SEARCH_RANGE * 2; delta += 2) {
                    float corr = 0.0f;
                    const float* cand = input_fifo.data() + delta * 2;
                    for (size_t f = 0; f < HALF_WINDOW * 2; f += 4) {
                        corr += cand[f] * overlap_tail[f] + cand[f + 1] * overlap_tail[f + 1];
                    }
                    if (corr > max_corr) { max_corr = corr; best_offset = delta; }
                }
            } else {
                best_offset = SEARCH_RANGE;
                is_primed = true;
            }
            const float* chosen = input_fifo.data() + best_offset * 2;
            for (size_t i = 0; i < HALF_WINDOW; ++i) {
                const float w = hann_window[i];
                out_buffer.push_back(std::clamp(chosen[i * 2] * w + prev_synth_tail[i * 2], -1.0f, 1.0f));
                out_buffer.push_back(std::clamp(chosen[i * 2 + 1] * w + prev_synth_tail[i * 2 + 1], -1.0f, 1.0f));
            }
            for (size_t i = 0; i < HALF_WINDOW; ++i) {
                const float w = hann_window[i + HALF_WINDOW];
                prev_synth_tail[i * 2]     = chosen[(i + HALF_WINDOW) * 2] * w;
                prev_synth_tail[i * 2 + 1] = chosen[(i + HALF_WINDOW) * 2 + 1] * w;
            }
            std::memcpy(overlap_tail.data(), chosen + HALF_WINDOW * 2, HALF_WINDOW * 2 * sizeof(float));
            input_fifo.erase(input_fifo.begin(), input_fifo.begin() + nominal_hop * 2);
        }
        return out_buffer.size() / 2;
    }
};
