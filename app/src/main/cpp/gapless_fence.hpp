#pragma once
#include <atomic>
#include <cstdint>
#include <array>

struct TrackBoundaryEvent {
    size_t   trigger_byte_index = 0;
    uint32_t sample_rate = 44100;
    bool     is_dop = false;
    float    replay_gain_db = 0.0f;
    float    track_peak = 1.0f;
    int32_t  track_id = -1;
    bool     active = false;
};

class GaplessBoundaryTracker {
private:
    static constexpr size_t MAX_PENDING = 4;
    std::array<TrackBoundaryEvent, MAX_PENDING> events{};
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
public:
    void scheduleBoundary(size_t byte_idx, uint32_t rate, bool dop, float rg_db, float peak, int32_t id) noexcept {
        const size_t w = head.load(std::memory_order_relaxed);
        events[w & (MAX_PENDING - 1)] = {byte_idx, rate, dop, rg_db, peak, id, true};
        head.store(w + 1, std::memory_order_release);
    }

    bool pollReachedBoundary(size_t current_read_index, TrackBoundaryEvent& out_event) noexcept {
        const size_t r = tail.load(std::memory_order_relaxed);
        const size_t w = head.load(std::memory_order_acquire);
        if (r == w) return false;
        const TrackBoundaryEvent& front = events[r & (MAX_PENDING - 1)];
        if (front.active && current_read_index >= front.trigger_byte_index) {
            out_event = front;
            tail.store(r + 1, std::memory_order_release);
            return true;
        }
        return false;
    }

    void clear() noexcept {
        head.store(0, std::memory_order_relaxed);
        tail.store(0, std::memory_order_relaxed);
    }
};
