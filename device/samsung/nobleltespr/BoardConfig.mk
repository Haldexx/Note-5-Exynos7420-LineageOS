include device/samsung/noblelte/BoardConfig.mk

TARGET_OTA_ASSERT_DEVICE := nobleltespr
AB_OTA_UPDATER := false
TARGET_PRESERVE_EXTERNAL_RECOVERY := true
BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive enforcing=0
TARGET_KERNEL_CONFIG := exynos7420-nobleltespr_defconfig
KERNEL_CC := CC="$(KERNEL_CC_WRAPPER) clang"
$(call soong_config_set,samsungVars,target_specific_header_path,device/samsung/universal7420-common/include)
$(call soong_config_set,samsungVars,target_dtbh_model,USA CDMA)
BOARD_VENDOR_SEPOLICY_DIRS += device/samsung/nobleltespr/sepolicy/vendor
PRODUCT_SOONG_NAMESPACES += device/samsung/nobleltespr
PRODUCT_SOONG_NAMESPACES += hardware/broadcom/libbt
$(call soong_config_set,brcm_libbt,custom_bt_config,//device/samsung/nobleltespr:note5_bluetooth_vendor_config)
$(call soong_config_set,brcm_libbt,bdroid_buildcfg_include_dir,device/samsung/noblelte/bluetooth)

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE := $(filter-out vendor/lineage/config/device_framework_matrix.xml,$(DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE))
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += device/samsung/nobleltespr/framework_compatibility_matrix.xml
TARGET_RELEASETOOLS_EXTENSIONS := device/samsung/nobleltespr/releasetools

BOARD_ROOT_EXTRA_FOLDERS += firmware cpdump

DEVICE_MANIFEST_FILE += device/samsung/nobleltespr/modem/radio-manifest.xml
