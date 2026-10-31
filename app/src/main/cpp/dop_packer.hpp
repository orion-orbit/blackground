#pragma once
#include <atomic>
#include <cstdint>
#include <vector>
#include <cstring>

template <typename T, size_t Capacity>
class LockFreeRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2");
private:
    T buffer[Capacity];
    alignas(64) std::atomic<size_t> write_idx{0};
    alignas(64) std::atomic<size_t> read_idx{0};

public:
    size_t write(const T* data, size_t count) noexcept {
        const size_t w = write_idx.load(std::memory_order_relaxed);
        const size_t r = read_idx.load(std::memory_order_acquire);
        const size_t free_space = Capacity - (w - r);
        const size_t to_write = (count < free_space) ? count : free_space;
        for (size_t i = 0; i < to_write; ++i) {
            buffer[(w + i) & (Capacity - 1)] = data[i];
        }
        write_idx.store(w + to_write, std::memory_order_release);
        return to_write;
    }

    size_t read(T* out, size_t count) noexcept {
        const size_t r = read_idx.load(std::memory_order_relaxed);
        const size_t w = write_idx.load(std::memory_order_acquire);
        const size_t available = w - r;
        const size_t to_read = (count < available) ? count : available;
        for (size_t i = 0; i < to_read; ++i) {
            out[i] = buffer[(r + i) & (Capacity - 1)];
        }
        read_idx.store(r + to_read, std::memory_order_release);
        return to_read;
    }

    size_t currentWriteCursor() const noexcept { return write_idx.load(std::memory_order_acquire); }
    size_t currentReadCursor() const noexcept  { return read_idx.load(std::memory_order_acquire); }

    void flushFrameAligned() noexcept {
        write_idx.store(0, std::memory_order_release);
        read_idx.store(0, std::memory_order_release);
    }

    void clear() noexcept { flushFrameAligned(); }
};

class DoPPacker {
private:
    bool marker_toggle = false;
    static constexpr uint8_t reverseBits(uint8_t b) noexcept {
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        return b;
    }
public:
    size_t packDsfBlockTo24BitDoP(
        const uint8_t* left_dsd_4096,
        const uint8_t* right_dsd_4096,
        size_t bytes_per_channel,
        uint8_t* out_pcm24_le,
        bool is_lsb_first = true
    ) noexcept {
        size_t out_offset = 0;
        for (size_t i = 0; i + 1 < bytes_per_channel; i += 2) {
            const uint8_t marker = marker_toggle ? 0xFA : 0x05;
            marker_toggle = !marker_toggle;
            uint8_t l0 = left_dsd_4096[i];
            uint8_t l1 = left_dsd_4096[i + 1];
            uint8_t r0 = right_dsd_4096[i];
            uint8_t r1 = right_dsd_4096[i + 1];
            if (is_lsb_first) {
                l0 = reverseBits(l0); l1 = reverseBits(l1);
                r0 = reverseBits(r0); r1 = reverseBits(r1);
            }
            out_pcm24_le[out_offset++] = l1;
            out_pcm24_le[out_offset++] = l0;
            out_pcm24_le[out_offset++] = marker;
            out_pcm24_le[out_offset++] = r1;
            out_pcm24_le[out_offset++] = r0;
            out_pcm24_le[out_offset++] = marker;
        }
        return out_offset;
    }
};
