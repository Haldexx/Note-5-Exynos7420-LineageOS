#
# Copyright (C) 2026 Haldexx (https://github.com/Haldexx)
#
# SPDX-License-Identifier: Apache-2.0
#

include device/samsung/noblelte/BoardConfig.mk

TARGET_OTA_ASSERT_DEVICE := noblelte,nobleltejv,nobleltedd,nobleltedv,nobleltetmo,nobleltebmc,nobleltelgt,noblelteskt,nobleltektt
AB_OTA_UPDATER := false
TARGET_PRESERVE_EXTERNAL_RECOVERY := true
KERNEL_CC := CC="$(KERNEL_CC_WRAPPER) clang"
$(call soong_config_set,samsungVars,target_specific_header_path,device/samsung/universal7420-common/include)

BOARD_VENDOR_SEPOLICY_DIRS += device/samsung/nobleltespr/sepolicy/vendor
BOARD_VENDOR_SEPOLICY_DIRS += device/samsung/nobleltedv/sepolicy/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += device/samsung/nobleltedv/sepolicy/private
PRODUCT_SOONG_NAMESPACES += device/samsung/nobleltespr
PRODUCT_SOONG_NAMESPACES += hardware/broadcom/libbt
$(call soong_config_set,brcm_libbt,custom_bt_config,//device/samsung/nobleltespr:note5_bluetooth_vendor_config)
$(call soong_config_set,brcm_libbt,bdroid_buildcfg_include_dir,device/samsung/noblelte/bluetooth)

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := $(filter-out vendor/lineage/config/device_framework_matrix.xml,$(DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE))
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += device/samsung/nobleltespr/framework_compatibility_matrix.xml
TARGET_RELEASETOOLS_EXTENSIONS := device/samsung/nobleltespr/releasetools

DEVICE_MANIFEST_FILE += device/samsung/nobleltespr/modem/radio-manifest.xml
