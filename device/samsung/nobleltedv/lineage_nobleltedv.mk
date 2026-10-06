#
# Copyright (C) 2026 Haldexx (https://github.com/Haldexx)
#
# SPDX-License-Identifier: Apache-2.0
#

PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,device/samsung/nobleltespr/proprietary/vendor/etc/nxp,$(TARGET_COPY_OUT_VENDOR)/etc/nxp)

PRODUCT_COPY_FILES += \
    device/samsung/nobleltedv/performance/powerhint.json:$(TARGET_COPY_OUT_VENDOR)/etc/powerhint.json \
    device/samsung/nobleltedv/performance/init.nobleltedv-power.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nobleltedv-power.rc

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
$(call inherit-product, device/samsung/noblelte/device.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_NAME := lineage_nobleltedv
PRODUCT_DEVICE := nobleltedv
PRODUCT_MODEL := SM-N920I
PRODUCT_BRAND := samsung
PRODUCT_MANUFACTURER := samsung
PRODUCT_GMS_CLIENTID_BASE := android-samsung
PRODUCT_SHIPPING_API_LEVEL := 22

PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/releasetools/note5-resize-system.sh:install/bin/note5-resize-system.sh

PRODUCT_PACKAGES += cbd modemloader libexpat.vendor
PRODUCT_COPY_FILES += \
    device/samsung/nobleltedv/modem/init.nobleltedv-baseband.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nobleltedv-baseband.rc \
    device/samsung/nobleltedv/modem/init.nobleltedv-ril.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nobleltedv-ril.rc \
    vendor/samsung/universal7420-common/proprietary/vendor/bin/hw/rild:$(TARGET_COPY_OUT_VENDOR)/bin/hw/rild \
    vendor/samsung/universal7420-common/proprietary/vendor/etc/init/init.vendor.rilcommon.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.vendor.rilcommon.rc
PRODUCT_VENDOR_PROPERTIES += ro.vendor.multisim.simslotcount=1
PRODUCT_SYSTEM_PROPERTIES += ro.telephony.default_network=9
PRODUCT_SYSTEM_PROPERTIES += ro.arch=exynos7420

PRODUCT_PACKAGES += PhhIms PhhImsTelephonyOverlay PhhImsFrameworkOverlay
PRODUCT_PACKAGES += Iwlan QualifiedNetworksService
DEVICE_PACKAGE_OVERLAYS := device/samsung/nobleltedv/overlay-wfc $(DEVICE_PACKAGE_OVERLAYS)
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.ipsec_tunnels.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.ipsec_tunnels.xml
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.ims.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.telephony.ims.xml

PRODUCT_SOURCE_ROOT_DIRS += \
    -hardware/samsung_slsi-linaro/interfaces/ExynosA2DPOffload \
    -hardware/samsung_slsi-linaro/interfaces/a2dp \
    -hardware/samsung/hidl/livedisplay \
    -hardware/samsung/hidl/touch \
    -hardware/samsung/hidl/fastcharge \
    -hardware/samsung/hidl/powershare \
    -hardware/samsung/hidl/vibrator/haptic \
    -device/samsung/universal7420-common/hardware/livedisplay \
    -hardware/samsung_slsi-linaro/exynos/libdisplaycolor/unittest \
    -hardware/samsung_slsi-linaro/exynos/libhdr/unittest \
    -hardware/samsung_slsi-linaro/exynos/ssp

ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)
PRODUCT_SYSTEM_PROPERTIES += ro.note5.legacy_bpf=true
PRODUCT_SYSTEM_PROPERTIES += ro.note5.wfd_disabled=true
DEVICE_PACKAGE_OVERLAYS := device/samsung/nobleltespr/overlay-offline $(DEVICE_PACKAGE_OVERLAYS)
PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/init.note5-offline.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/init.note5-offline.rc
endif

PRODUCT_COPY_FILES += \
    device/samsung/nobleltedv/n920i-features.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/note5-n920i-features.xml

PRODUCT_SYSTEM_PROPERTIES += ro.note5.legacy_vti=true
