#include "SehRadioIndication.h"

namespace vendor::samsung::hardware::radio::implementation {

Return<void> SehRadioIndication::acbInfoChanged(int32_t type, const hidl_vec<int32_t>& acbInfo) {
    return Void();
}

Return<void> SehRadioIndication::csFallback(int32_t type, int32_t state) {
    return Void();
}

Return<void> SehRadioIndication::imsPreferenceChanged(int32_t type, const hidl_vec<int32_t>& imsPref) {
    return Void();
}

Return<void> SehRadioIndication::voiceRadioBearerHandoverStatusChanged(int32_t type, int32_t state) {
    return Void();
}

Return<void> SehRadioIndication::timerStatusChangedInd(int32_t type, const hidl_vec<int32_t>& eventNoti) {
    return Void();
}

Return<void> SehRadioIndication::modemCapabilityIndication(int32_t type, const hidl_vec<int8_t>& data) {
    return Void();
}

Return<void> SehRadioIndication::needTurnOnRadioIndication(int32_t type) {
    return Void();
}

Return<void> SehRadioIndication::simPhonebookReadyIndication(int32_t type) {
    return Void();
}

Return<void> SehRadioIndication::phonebookInitCompleteIndication(int32_t type) {
    return Void();
}

Return<void> SehRadioIndication::deviceReadyNoti(int32_t type) {
    return Void();
}

Return<void> SehRadioIndication::stkSmsSendResultIndication(int32_t type, int32_t result) {
    return Void();
}

Return<void> SehRadioIndication::stkCallControlResultIndication(int32_t type, const hidl_string& cmd) {
    return Void();
}

Return<void> SehRadioIndication::simSwapStateChangedIndication(int32_t type, int32_t state) {
    return Void();
}

Return<void> SehRadioIndication::simCountMismatchedIndication(int32_t type, int32_t state) {
    return Void();
}

Return<void> SehRadioIndication::simOnOffStateChangedNotify(int32_t type, int32_t mode) {
    return Void();
}

Return<void> SehRadioIndication::releaseCompleteMessageIndication(int32_t type, const ::vendor::samsung::hardware::radio::V2_0::SehSsReleaseComplete& result) {
    return Void();
}

Return<void> SehRadioIndication::sapNotify(int32_t type, const hidl_vec<int8_t>& data) {
    return Void();
}

Return<void> SehRadioIndication::nrBearerAllocationChanged(int32_t type, int32_t status) {
    return Void();
}

Return<void> SehRadioIndication::nrNetworkTypeAdded(int32_t type, int32_t status) {
    return Void();
}

Return<void> SehRadioIndication::rrcStateChanged(int32_t type, const ::vendor::samsung::hardware::radio::V2_0::SehRrcStateInfo& state) {
    return Void();
}

Return<void> SehRadioIndication::configModemCapabilityChangeNoti(int32_t type, const ::vendor::samsung::hardware::radio::V2_0::SehConfigModemCapability& configModemCapa) {
    return Void();
}

Return<void> SehRadioIndication::needApnProfileIndication(const hidl_string& select) {
    return Void();
}

Return<int32_t> SehRadioIndication::needSettingValueIndication(const hidl_string& key, const hidl_string& table) {
    return int32_t {};
}

Return<void> SehRadioIndication::execute(int32_t type, const hidl_string& cmd) {
    return Void();
}

Return<void> SehRadioIndication::signalLevelInfoChanged(int32_t type, const ::vendor::samsung::hardware::radio::V2_0::SehSignalBar& signalBarInfo) {
    return Void();
}

Return<void> SehRadioIndication::extendedRegistrationState(int32_t type, const ::vendor::samsung::hardware::radio::V2_0::SehExtendedRegStateResult& state) {
    return Void();
}

Return<void> SehRadioIndication::needPacketUsage(const hidl_string& iface, needPacketUsage_cb _hidl_cb) {
    return Void();
}

}  // namespace vendor::samsung::hardware::radio::implementation
