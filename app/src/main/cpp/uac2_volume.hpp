#pragma once
#include <libusb.h>
#include <cstdint>
#include <cmath>
#include <algorithm>

constexpr uint8_t USB_DT_CS_INTERFACE    = 0x24;
constexpr uint8_t UAC2_FEATURE_UNIT      = 0x06;
constexpr uint8_t UAC2_CS_CUR_REQ        = 0x01;
constexpr uint8_t UAC2_CS_RANGE_REQ      = 0x02;
constexpr uint8_t UAC2_FU_VOLUME_CONTROL = 0x02;

#pragma pack(push, 1)
struct Uac2VolumeRangeResponse {
    uint16_t wNumSubRanges;
    int16_t  wMIN;
    int16_t  wMAX;
    int16_t  wRES;
};
#pragma pack(pop)

class Uac2HardwareVolumeController {
private:
    libusb_device_handle* handle = nullptr;
    uint8_t control_interface_num = 0;
    uint8_t feature_unit_id = 0;
    bool    has_hw_volume = false;
    int16_t raw_min = -15360;
    int16_t raw_max = 0;
    int16_t raw_res = 128;
    int16_t raw_cur = -4608;

public:
    bool init(libusb_device_handle* dev_handle) {
        handle = dev_handle;
        has_hw_volume = false;
        if (!handle) return false;
        libusb_device* dev = libusb_get_device(handle);
        struct libusb_config_descriptor* config = nullptr;
        if (libusb_get_active_config_descriptor(dev, &config) < 0 || !config) return false;

        for (int i = 0; i < config->bNumInterfaces; ++i) {
            const struct libusb_interface& iface = config->interface[i];
            for (int a = 0; a < iface.num_altsetting; ++a) {
                const struct libusb_interface_descriptor& alt = iface.altsetting[a];
                if (alt.bInterfaceClass == 0x01 && alt.bInterfaceSubClass == 0x01) {
                    control_interface_num = alt.bInterfaceNumber;
                    parseExtraDescriptors(alt.extra, alt.extra_length);
                }
            }
        }
        libusb_free_config_descriptor(config);
        if (feature_unit_id != 0 && queryHardwareRange()) {
            has_hw_volume = true;
            return true;
        }
        return false;
    }

    void parseExtraDescriptors(const unsigned char* extra, int length) {
        int offset = 0;
        while (offset + 4 <= length) {
            uint8_t bLength = extra[offset];
            if (bLength == 0) break;
            if (extra[offset + 1] == USB_DT_CS_INTERFACE && extra[offset + 2] == UAC2_FEATURE_UNIT) {
                feature_unit_id = extra[offset + 3];
                return;
            }
            offset += bLength;
        }
    }

    bool queryHardwareRange() {
        Uac2VolumeRangeResponse range{};
        for (uint8_t ch = 0; ch <= 1; ++ch) {
            int ret = libusb_control_transfer(
                handle, 0xA1, UAC2_CS_RANGE_REQ, (UAC2_FU_VOLUME_CONTROL << 8) | ch,
                (feature_unit_id << 8) | control_interface_num,
                reinterpret_cast<uint8_t*>(&range), sizeof(range), 500
            );
            if (ret >= 8 && range.wNumSubRanges >= 1) {
                raw_min = range.wMIN;
                raw_max = range.wMAX;
                raw_res = (range.wRES > 0) ? range.wRES : 128;
                return true;
            }
        }
        return false;
    }

    bool setVolumeDb(float targetDb) {
        int32_t targetRaw = static_cast<int32_t>(std::lrint(targetDb * 256.0f));
        targetRaw = std::clamp<int32_t>(targetRaw, raw_min, raw_max);
        if (raw_res > 1) targetRaw = raw_min + ((targetRaw - raw_min) / raw_res) * raw_res;
        raw_cur = static_cast<int16_t>(targetRaw);
        if (!handle || !has_hw_volume) return false;

        uint8_t payload[2] = { static_cast<uint8_t>(raw_cur & 0xFF), static_cast<uint8_t>((raw_cur >> 8) & 0xFF) };
        bool success = false;
        for (uint8_t ch = 0; ch <= 2; ++ch) {
            int ret = libusb_control_transfer(
                handle, 0x21, UAC2_CS_CUR_REQ, (UAC2_FU_VOLUME_CONTROL << 8) | ch,
                (feature_unit_id << 8) | control_interface_num, payload, sizeof(payload), 200
            );
            if (ret >= 0) success = true;
        }
        return success;
    }

    float stepVolume(int direction) {
        int16_t step = std::max<int16_t>(raw_res, 128);
        int32_t nextRaw = std::clamp<int32_t>(raw_cur + direction * step, raw_min, raw_max);
        setVolumeDb(static_cast<float>(nextRaw) / 256.0f);
        return getCurrentDb();
    }

    float getMinDb() const noexcept { return static_cast<float>(raw_min) / 256.0f; }
    float getMaxDb() const noexcept { return static_cast<float>(raw_max) / 256.0f; }
    float getStepDb() const noexcept { return static_cast<float>(std::max<int16_t>(raw_res, 128)) / 256.0f; }
    float getCurrentDb() const noexcept { return static_cast<float>(raw_cur) / 256.0f; }
    bool isSupported() const noexcept { return has_hw_volume; }
};
