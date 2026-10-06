PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,device/samsung/nobleltespr/proprietary/vendor/etc/nxp,$(TARGET_COPY_OUT_VENDOR)/etc/nxp)

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
$(call inherit-product, device/samsung/noblelte/device.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_NAME := lineage_nobleltespr
PRODUCT_DEVICE := nobleltespr
PRODUCT_MODEL := SM-N920P
PRODUCT_BRAND := samsung
PRODUCT_MANUFACTURER := samsung
PRODUCT_GMS_CLIENTID_BASE := android-samsung
PRODUCT_SHIPPING_API_LEVEL := 22

PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/releasetools/note5-resize-system.sh:install/bin/note5-resize-system.sh

PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/proprietary/vendor/lib/liboemcrypto.so:$(TARGET_COPY_OUT_VENDOR)/lib/liboemcrypto.so

PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/display_settings.xml:$(TARGET_COPY_OUT_VENDOR)/etc/display_settings.xml

PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/init.nobleltespr-gnss.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nobleltespr-gnss.rc

PRODUCT_PACKAGES += mdm_pwron
PRODUCT_COPY_FILES += \
    device/samsung/nobleltespr/proprietary/system/bin/ks:$(TARGET_COPY_OUT_SYSTEM)/bin/ks \
    device/samsung/nobleltespr/proprietary/system/etc/data/netmgr_config.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/data/netmgr_config.xml \
    device/samsung/nobleltespr/proprietary/system/etc/data/qmi_config.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/data/qmi_config.xml \
    device/samsung/nobleltespr/proprietary/vendor/bin/mdm_helper:$(TARGET_COPY_OUT_VENDOR)/bin/mdm_helper \
    device/samsung/nobleltespr/proprietary/vendor/bin/qmuxd:$(TARGET_COPY_OUT_VENDOR)/bin/qmuxd \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libconfigdb.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libconfigdb.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libdiag.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libdiag.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libdsutils.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libdsutils.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libidl.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libidl.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libmdmdetect.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libmdmdetect.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmi.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmi.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmi_cci.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmi_cci.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmi_client_qmux.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmi_client_qmux.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmi_common_so.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmi_common_so.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmi_encdec.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmi_encdec.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqmiservices.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqmiservices.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libsmemlog.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libsmemlog.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libxml.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libxml.so \
    device/samsung/nobleltespr/modem/init.nobleltespr-modem.sh:$(TARGET_COPY_OUT_VENDOR)/bin/init.nobleltespr-modem.sh \
    device/samsung/nobleltespr/modem/init.nobleltespr-modem.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nobleltespr-modem.rc

PRODUCT_PACKAGES += libsec-ril-shim libperipheral_client
PRODUCT_COPY_FILES += \
    vendor/samsung/universal7420-common/proprietary/vendor/bin/hw/rild:$(TARGET_COPY_OUT_VENDOR)/bin/hw/rild \
    device/samsung/nobleltespr/proprietary/system/csc/feature.xml:$(TARGET_COPY_OUT_SYSTEM)/csc/feature.xml \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libprotobuf-mdm.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libprotobuf-mdm.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libprotobuf-pb29.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libprotobuf-pb29.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libqcci_legacy.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libqcci_legacy.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libril-qcril-hook-oem.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libril-qcril-hook-oem.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/librmnetctl.so:$(TARGET_COPY_OUT_VENDOR)/lib64/librmnetctl.so \
    device/samsung/nobleltespr/proprietary/vendor/lib64/libsec-ril-mdm.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libsec-ril-mdm.so
PRODUCT_VENDOR_PROPERTIES += \
    persist.ril.ims.eutranParam=3 \
    persist.ril.ims.utranParam=3 \
    persist.vendor.note5.ril.shim.mask=0x1fff
PRODUCT_SYSTEM_PROPERTIES += ro.telephony.default_network=9
PRODUCT_PACKAGES += PhhIms PhhImsTelephonyOverlay PhhImsFrameworkOverlay
PRODUCT_PACKAGES_DEBUG += sehraw
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
    device/samsung/nobleltespr/offline-features.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/note5-offline-features.xml \
    device/samsung/nobleltespr/init.note5-offline.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/init.note5-offline.rc
endif
