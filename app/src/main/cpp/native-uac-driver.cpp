#include <jni.h>
#include <libusb.h>
#include <android/log.h>
#include <sys/resource.h>
#include <thread>
#include <atomic>
#include <vector>

#include "dop_packer.hpp"
#include "parametric_eq.hpp"
#include "uac2_volume.hpp"
#include "gapless_fence.hpp"
#include "dsd_decimator.hpp"
#include "dr_meter.hpp"
#include "wsola_stretcher.hpp"

#define UAC2_CS_CUR 0x01
#define UAC2_CS_SAM_FREQ_CONTROL 0x01
#define UAC2_CS_CLOCK_VALID_CONTROL 0x02

constexpr int NUM_ISO_TRANSFERS = 8;
constexpr int PACKETS_PER_TRANSFER = 8;

static libusb_context* usb_ctx = nullptr;
static libusb_device_handle* dev_handle = nullptr;
static std::thread usb_event_thread;

static std::atomic<bool> engine_running{false};
static std::atomic<bool> is_dop_mode{false};
static std::atomic<bool> dsd_pcm_fallback_enabled{false};
static std::atomic<bool> usb_paused{false};

static std::atomic<uint32_t> prev_sample_rate_hz{0};
static std::atomic<uint32_t> locked_sample_rate_hz{0};
static std::atomic<uint32_t> xrun_underrun_count{0};
static std::atomic<bool>     dac_pll_locked{false};

static std::atomic<int64_t>  seek_anchor_ms{0};
static std::atomic<uint64_t> bytes_consumed_since_seek{0};

static LockFreeRingBuffer<uint8_t, 524288> audio_ring_buffer;
static DoPPacker dop_packer;
static ParametricEqEngine peq_engine;
static Uac2HardwareVolumeController hw_vol_controller;
static GaplessBoundaryTracker boundary_tracker;
static DsdToPcmDecimator dsd_decimator;
static RealTimeDrMeter dr_meter;
static WsolaTimeStretcher wsola_stretcher;

static struct libusb_transfer* iso_transfers[NUM_ISO_TRANSFERS] = {nullptr};
static uint8_t iso_ep_out = 0x01;
static uint32_t bytes_per_microframe = 132;

static bool verifyUac2ClockLock(uint8_t clockEntityId, uint32_t expectedRate) {
    if (!dev_handle) return false;
    uint8_t valid_byte = 0;
    int r_valid = libusb_control_transfer(
        dev_handle, 0xA1, UAC2_CS_CUR, (UAC2_CS_CLOCK_VALID_CONTROL << 8),
        (clockEntityId << 8) | 0x00, &valid_byte, sizeof(valid_byte), 200
    );
    uint8_t rate_bytes[4] = {0};
    int r_rate = libusb_control_transfer(
        dev_handle, 0xA1, UAC2_CS_CUR, (UAC2_CS_SAM_FREQ_CONTROL << 8),
        (clockEntityId << 8) | 0x00, rate_bytes, sizeof(rate_bytes), 200
    );
    uint32_t actual = expectedRate;
    if (r_rate == 4) {
        actual = static_cast<uint32_t>(rate_bytes[0]) |
                 (static_cast<uint32_t>(rate_bytes[1]) << 8) |
                 (static_cast<uint32_t>(rate_bytes[2]) << 16) |
                 (static_cast<uint32_t>(rate_bytes[3]) << 24);
    }
    bool pll_ok = (r_valid < 0) ? true : (valid_byte == 1);
    locked_sample_rate_hz.store(actual, std::memory_order_release);
    dac_pll_locked.store(pll_ok && (actual == expectedRate), std::memory_order_release);
    return dac_pll_locked.load();
}

static int setUac2ClockRate(uint8_t clockEntityId, uint32_t sampleRate) {
    uint32_t old_rate = locked_sample_rate_hz.load(std::memory_order_relaxed);
    if (old_rate != sampleRate && old_rate > 0) {
        prev_sample_rate_hz.store(old_rate, std::memory_order_release);
    }
    dac_pll_locked.store(false, std::memory_order_release);
    uint8_t rate_bytes[4] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFF)
    };
    int res = libusb_control_transfer(
        dev_handle, 0x21, UAC2_CS_CUR, (UAC2_CS_SAM_FREQ_CONTROL << 8),
        (clockEntityId << 8) | 0x00, rate_bytes, sizeof(rate_bytes), 1000
    );
    verifyUac2ClockLock(clockEntityId, sampleRate);
    peq_engine.setSampleRate(static_cast<double>(sampleRate));
    return res;
}

static void applySeamlessTrackTransition(const TrackBoundaryEvent& ev) {
    uint32_t current_rate = locked_sample_rate_hz.load(std::memory_order_relaxed);
    uint32_t next_rate = ev.is_dop ? 176400 : ev.sample_rate;
    is_dop_mode.store(ev.is_dop, std::memory_order_relaxed);
    if (next_rate != current_rate && next_rate > 0) {
        setUac2ClockRate(0x29, next_rate);
        bytes_per_microframe = ((next_rate / 1000) * 6) / 8;
        if (bytes_per_microframe == 0) bytes_per_microframe = 132;
    }
    if (peq_engine.isEnabled() && !ev.is_dop) {
        float max_safe = (ev.track_peak > 0.001f) ? (-20.0f * std::log10(ev.track_peak)) : 0.0f;
        peq_engine.setReplayGainOffsetDb(std::min(ev.replay_gain_db, max_safe));
    }
}

static void fillIsoTransferBuffer(struct libusb_transfer* transfer) {
    uint8_t* ptr = transfer->buffer;
    static bool silence_marker_toggle = false;

    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        uint32_t pkt_len = bytes_per_microframe;
        transfer->iso_packet_desc[i].length = pkt_len;

        TrackBoundaryEvent boundary_ev;
        if (boundary_tracker.pollReachedBoundary(audio_ring_buffer.currentReadCursor(), boundary_ev)) {
            applySeamlessTrackTransition(boundary_ev);
        }

        size_t bytes_read = 0;
        if (!usb_paused.load(std::memory_order_relaxed)) {
            bytes_read = audio_ring_buffer.read(ptr, pkt_len);
            bytes_consumed_since_seek.fetch_add(bytes_read, std::memory_order_relaxed);
        }

        if (bytes_read > 0 && !is_dop_mode.load(std::memory_order_relaxed)) {
            peq_engine.processUsb24BitPacked(ptr, bytes_read);
            dr_meter.analyzeUsb24BitPacked(ptr, bytes_read);
        }

        if (bytes_read < pkt_len) {
            if (!usb_paused.load(std::memory_order_relaxed) && engine_running.load(std::memory_order_relaxed)) {
                xrun_underrun_count.fetch_add(1, std::memory_order_relaxed);
            }
            size_t missing = pkt_len - bytes_read;
            uint8_t* silence_ptr = ptr + bytes_read;
            if (is_dop_mode.load(std::memory_order_relaxed)) {
                for (size_t b = 0; b + 5 < missing; b += 6) {
                    uint8_t marker = silence_marker_toggle ? 0xFA : 0x05;
                    silence_marker_toggle = !silence_marker_toggle;
                    silence_ptr[b] = 0x69; silence_ptr[b + 1] = 0x69; silence_ptr[b + 2] = marker;
                    silence_ptr[b + 3] = 0x69; silence_ptr[b + 4] = 0x69; silence_ptr[b + 5] = marker;
                }
            } else {
                std::memset(silence_ptr, 0, missing);
            }
        }
        ptr += pkt_len;
    }
}

static void LIBUSB_CALL isoCallback(struct libusb_transfer* transfer) {
    if (!engine_running.load(std::memory_order_relaxed)) return;
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        fillIsoTransferBuffer(transfer);
        libusb_submit_transfer(transfer);
    }
}

static bool discoverAudioEndpoint(libusb_device* dev) {
    struct libusb_config_descriptor* config = nullptr;
    if (libusb_get_active_config_descriptor(dev, &config) < 0 || !config) return false;
    bool found = false;
    for (int i = 0; i < config->bNumInterfaces && !found; ++i) {
        const struct libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting && !found; ++a) {
            const struct libusb_interface_descriptor& alt = iface.altsetting[a];
            for (int e = 0; e < alt.bNumEndpoints; ++e) {
                const struct libusb_endpoint_descriptor& ep = alt.endpoint[e];
                bool is_iso = (ep.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
                bool is_out = (ep.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_OUT;
                if (is_iso && is_out) {
                    iso_ep_out = ep.bEndpointAddress;
                    found = true;
                    break;
                }
            }
        }
    }
    libusb_free_config_descriptor(config);
    return found;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_minimal_hifi_UsbNativeBridge_initDirectUsbDac(
    JNIEnv*, jobject, jint fd, jint sample_rate, jboolean enable_dop
) {
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (libusb_init(&usb_ctx) < 0) return JNI_FALSE;
    if (libusb_wrap_sys_device(usb_ctx, (intptr_t)fd, &dev_handle) < 0) return JNI_FALSE;

    libusb_set_auto_detach_kernel_driver(dev_handle, 1);
    libusb_claim_interface(dev_handle, 0);
    libusb_claim_interface(dev_handle, 1);
    libusb_set_interface_alt_setting(dev_handle, 1, 1);

    discoverAudioEndpoint(libusb_get_device(dev_handle));
    hw_vol_controller.init(dev_handle);
    hw_vol_controller.setVolumeDb(-18.0f);

    bool use_dop = (enable_dop == JNI_TRUE) && !dsd_pcm_fallback_enabled.load(std::memory_order_relaxed);
    uint32_t target_rate = use_dop ? 176400 : (enable_dop ? 88200 : static_cast<uint32_t>(sample_rate));
    is_dop_mode.store(use_dop, std::memory_order_relaxed);
    setUac2ClockRate(0x29, target_rate);

    bytes_per_microframe = ((target_rate / 1000) * 6) / 8;
    if (bytes_per_microframe == 0) bytes_per_microframe = 132;

    audio_ring_buffer.clear();
    xrun_underrun_count.store(0, std::memory_order_relaxed);
    engine_running.store(true, std::memory_order_release);

    for (int i = 0; i < NUM_ISO_TRANSFERS; ++i) {
        uint32_t buf_size = bytes_per_microframe * PACKETS_PER_TRANSFER;
        uint8_t* buffer = new uint8_t[buf_size];
        iso_transfers[i] = libusb_alloc_transfer(PACKETS_PER_TRANSFER);
        libusb_fill_iso_transfer(
            iso_transfers[i], dev_handle, iso_ep_out,
            buffer, buf_size, PACKETS_PER_TRANSFER, isoCallback, nullptr, 1000
        );
        fillIsoTransferBuffer(iso_transfers[i]);
        libusb_submit_transfer(iso_transfers[i]);
    }

    usb_event_thread = std::thread([]() {
        setpriority(PRIO_PROCESS, 0, -19);
        while (engine_running.load(std::memory_order_acquire)) {
            struct timeval tv = {0, 50000};
            libusb_handle_events_timeout_completed(usb_ctx, &tv, nullptr);
        }
    });
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_minimal_hifi_UsbNativeBridge_pushDsdBlockToDoP(
    JNIEnv* env, jobject, jbyteArray left_block, jbyteArray right_block, jint block_size
) {
    jbyte* l_ptr = env->GetByteArrayElements(left_block, nullptr);
    jbyte* r_ptr = env->GetByteArrayElements(right_block, nullptr);
    std::vector<uint8_t> dop_encoded(block_size * 3);
    size_t packed = dop_packer.packDsfBlockTo24BitDoP(
        reinterpret_cast<const uint8_t*>(l_ptr),
        reinterpret_cast<const uint8_t*>(r_ptr),
        static_cast<size_t>(block_size),
        dop_encoded.data(), true
    );
    size_t written = audio_ring_buffer.write(dop_encoded.data(), packed);
    env->ReleaseByteArrayElements(left_block, l_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(right_block, r_ptr, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_minimal_hifi_UsbNativeBridge_pushDsdBlockAuto(
    JNIEnv* env, jobject thiz, jbyteArray left_block, jbyteArray right_block, jint block_size
) {
    if (!dsd_pcm_fallback_enabled.load(std::memory_order_acquire) && !wsola_stretcher.isActive()) {
        return Java_com_minimal_hifi_UsbNativeBridge_pushDsdBlockToDoP(env, thiz, left_block, right_block, block_size);
    }
    jbyte* l_ptr = env->GetByteArrayElements(left_block, nullptr);
    jbyte* r_ptr = env->GetByteArrayElements(right_block, nullptr);
    const size_t expected_frames = static_cast<size_t>(block_size) / 4;
    std::vector<float> pcm_float(expected_frames * 2);

    size_t frames = dsd_decimator.decimateDsfBlockToFloatStereo(
        reinterpret_cast<const uint8_t*>(l_ptr),
        reinterpret_cast<const uint8_t*>(r_ptr),
        static_cast<size_t>(block_size),
        pcm_float.data(), true
    );

    std::vector<float> stretched_float;
    const float* active_float_ptr = pcm_float.data();
    size_t active_frames = frames;
    if (wsola_stretcher.isActive()) {
        active_frames = wsola_stretcher.processStereoFloat(pcm_float.data(), frames, stretched_float);
        active_float_ptr = stretched_float.data();
    }

    dr_meter.analyzeFloatStereo(active_float_ptr, active_frames);
    std::vector<uint8_t> pcm24_bytes(active_frames * 6);
    size_t byte_count = DsdToPcmDecimator::packFloatToUsb24Bit(active_float_ptr, active_frames, pcm24_bytes.data());
    size_t written = audio_ring_buffer.write(pcm24_bytes.data(), byte_count);

    env->ReleaseByteArrayElements(left_block, l_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(right_block, r_ptr, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_stopUsbEngine(JNIEnv*, jobject) {
    engine_running.store(false, std::memory_order_release);
    if (usb_event_thread.joinable()) usb_event_thread.join();
    for (int i = 0; i < NUM_ISO_TRANSFERS; ++i) {
        if (iso_transfers[i]) {
            libusb_cancel_transfer(iso_transfers[i]);
            delete[] iso_transfers[i]->buffer;
            libusb_free_transfer(iso_transfers[i]);
            iso_transfers[i] = nullptr;
        }
    }
    if (dev_handle) {
        libusb_release_interface(dev_handle, 1);
        libusb_release_interface(dev_handle, 0);
        libusb_close(dev_handle);
        dev_handle = nullptr;
    }
    if (usb_ctx) {
        libusb_exit(usb_ctx);
        usb_ctx = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setEqEnabled(JNIEnv*, jobject, jboolean enabled) {
    peq_engine.setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setEqPreamp(JNIEnv*, jobject, jdouble preampDb) {
    peq_engine.setPreampDb(preampDb);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_updateEqBand(
    JNIEnv*, jobject, jint idx, jint type, jdouble freq, jdouble gain, jdouble q, jboolean enabled
) {
    peq_engine.updateBand(static_cast<size_t>(idx), type, freq, gain, q, enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_processFloatPcm(
    JNIEnv* env, jobject, jobject directBuffer, jint offset, jint numFrames, jint sampleRate
) {
    auto* ptr = reinterpret_cast<float*>(static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer)) + offset);
    peq_engine.setSampleRate(static_cast<double>(sampleRate));
    peq_engine.processFloatBuffer(ptr, numFrames);
    dr_meter.analyzeFloatStereo(ptr, static_cast<size_t>(numFrames));
}

JNIEXPORT jfloatArray JNICALL
Java_com_minimal_hifi_UsbNativeBridge_getHardwareVolumeRange(JNIEnv* env, jobject) {
    jfloatArray res = env->NewFloatArray(4);
    float vals[4] = {
        hw_vol_controller.getMinDb(),
        hw_vol_controller.getMaxDb(),
        hw_vol_controller.getStepDb(),
        hw_vol_controller.getCurrentDb()
    };
    env->SetFloatArrayRegion(res, 0, 4, vals);
    return res;
}

JNIEXPORT jfloat JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setHardwareVolumeDb(JNIEnv*, jobject, jfloat targetDb) {
    hw_vol_controller.setVolumeDb(targetDb);
    return hw_vol_controller.getCurrentDb();
}

JNIEXPORT jfloat JNICALL
Java_com_minimal_hifi_UsbNativeBridge_stepHardwareVolume(JNIEnv*, jobject, jint direction) {
    return hw_vol_controller.stepVolume(direction);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_markNextTrackBoundary(
    JNIEnv*, jobject, jint sampleRate, jboolean isDop, jfloat replayGainDb, jfloat trackPeak, jint trackId
) {
    boundary_tracker.scheduleBoundary(
        audio_ring_buffer.currentWriteCursor(),
        static_cast<uint32_t>(sampleRate),
        isDop == JNI_TRUE, replayGainDb, trackPeak, trackId
    );
}

JNIEXPORT jintArray JNICALL
Java_com_minimal_hifi_UsbNativeBridge_getClockAndBufferTelemetry(JNIEnv* env, jobject) {
    jintArray out = env->NewIntArray(6);
    size_t buffered = audio_ring_buffer.currentWriteCursor() - audio_ring_buffer.currentReadCursor();
    int fill_pct = static_cast<int>((buffered * 100) / 524288);
    int telemetry[6] = {
        static_cast<int>(prev_sample_rate_hz.load(std::memory_order_relaxed)),
        static_cast<int>(locked_sample_rate_hz.load(std::memory_order_relaxed)),
        dac_pll_locked.load(std::memory_order_relaxed) ? 1 : 0,
        is_dop_mode.load(std::memory_order_relaxed) ? 1 : 0,
        std::clamp(fill_pct, 0, 100),
        static_cast<int>(xrun_underrun_count.load(std::memory_order_relaxed))
    };
    env->SetIntArrayRegion(out, 0, 6, telemetry);
    return out;
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setDsdPcmFallbackMode(
    JNIEnv*, jobject, jboolean enablePcmFallback, jint cutoffMode
) {
    dsd_pcm_fallback_enabled.store(enablePcmFallback == JNI_TRUE, std::memory_order_release);
    dsd_decimator.rebuildLookupTable(static_cast<DsdCutoffFilter>(cutoffMode));
}

JNIEXPORT jfloatArray JNICALL
Java_com_minimal_hifi_UsbNativeBridge_getDrTelemetry(JNIEnv* env, jobject) {
    jfloatArray res = env->NewFloatArray(4);
    float vals[4] = {
        dr_meter.getPeakDbfs(),
        dr_meter.getRmsDbfs(),
        dr_meter.getDrScore(),
        dr_meter.hasClipped() ? 1.0f : 0.0f
    };
    env->SetFloatArrayRegion(res, 0, 4, vals);
    return res;
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setPlaybackSpeed(JNIEnv*, jobject, jfloat speed) {
    wsola_stretcher.setSpeed(speed);
    if (std::abs(speed - 1.0f) > 0.005f && is_dop_mode.load(std::memory_order_relaxed)) {
        dsd_pcm_fallback_enabled.store(true, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_nativeSeekFlush(JNIEnv*, jobject, jlong targetPositionMs) {
    audio_ring_buffer.flushFrameAligned();
    boundary_tracker.clear();
    wsola_stretcher.reset();
    dsd_decimator.resetHistory();
    seek_anchor_ms.store(targetPositionMs, std::memory_order_release);
    bytes_consumed_since_seek.store(0, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_minimal_hifi_UsbNativeBridge_setUsbPaused(JNIEnv*, jobject, jboolean paused) {
    usb_paused.store(paused == JNI_TRUE, std::memory_order_release);
}

JNIEXPORT jlong JNICALL
Java_com_minimal_hifi_UsbNativeBridge_getCurrentUsbPositionMs(JNIEnv*, jobject) {
    const uint32_t rate = locked_sample_rate_hz.load(std::memory_order_relaxed);
    if (rate == 0) return seek_anchor_ms.load(std::memory_order_relaxed);
    const uint64_t stereo_frames = bytes_consumed_since_seek.load(std::memory_order_relaxed) / 6;
    const float speed = wsola_stretcher.getSpeed();
    const int64_t elapsed_ms = static_cast<int64_t>((stereo_frames * 1000ULL * speed) / rate);
    return seek_anchor_ms.load(std::memory_order_relaxed) + elapsed_ms;
}

} // extern "C"
