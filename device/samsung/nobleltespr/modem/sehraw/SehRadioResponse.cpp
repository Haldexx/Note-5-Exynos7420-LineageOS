#include "SehRadioResponse.h"

namespace vendor::samsung::hardware::radio::implementation {

Return<void> SehRadioResponse::getIccCardStatusResponse() {
    return Void();
}

Return<void> SehRadioResponse::supplyNetworkDepersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::dialResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getCurrentCallsResponse() {
    return Void();
}

Return<void> SehRadioResponse::getImsRegistrationStateResponse() {
    return Void();
}

Return<void> SehRadioResponse::setImsCallListResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getPreferredNetworkListResponse() {
    return Void();
}

Return<void> SehRadioResponse::setPreferredNetworkListResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::sendEncodedUssdResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getDisable2gResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t isDisable) {
    return Void();
}

Return<void> SehRadioResponse::setDisable2gResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getCnapResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t m) {
    return Void();
}

Return<void> SehRadioResponse::getPhonebookStorageInfoResponse() {
    return Void();
}

Return<void> SehRadioResponse::getUsimPhonebookCapabilityResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<int32_t>& phonebookCapability) {
    return Void();
}

Return<void> SehRadioResponse::setSimOnOffResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::setSimInitEventResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getSimLockInfoResponse() {
    return Void();
}

Return<void> SehRadioResponse::supplyIccPersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::changeIccPersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getPhonebookEntryResponse() {
    return Void();
}

Return<void> SehRadioResponse::accessPhonebookEntryResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t SimPhonmebookAccessResp) {
    return Void();
}

Return<void> SehRadioResponse::getCellBroadcastConfigResponse() {
    return Void();
}

Return<void> SehRadioResponse::emergencySearchResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t respEmergencySearch) {
    return Void();
}

Return<void> SehRadioResponse::emergencyControlResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getAtrResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_string& atr) {
    return Void();
}

Return<void> SehRadioResponse::sendCdmaSmsExpectMoreResponse() {
    return Void();
}

Return<void> SehRadioResponse::sendSmsResponse() {
    return Void();
}

Return<void> SehRadioResponse::sendSMSExpectMoreResponse() {
    return Void();
}

Return<void> SehRadioResponse::sendCdmaSmsResponse() {
    return Void();
}

Return<void> SehRadioResponse::sendImsSmsResponse() {
    return Void();
}

Return<void> SehRadioResponse::getStoredMsgCountFromSimResponse() {
    return Void();
}

Return<void> SehRadioResponse::readSmsFromSimResponse() {
    return Void();
}

Return<void> SehRadioResponse::writeSmsToSimResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t index) {
    return Void();
}

Return<void> SehRadioResponse::setDataAllowedResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::getCsgListResponse() {
    return Void();
}

Return<void> SehRadioResponse::selectCsgManualResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::setMobileDataSettingResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) {
    return Void();
}

Return<void> SehRadioResponse::sendRequestRawResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<int8_t>& data) {
    sehraw_on_response(info.serial, static_cast<int32_t>(info.error), data);
    return Void();
}

Return<void> SehRadioResponse::sendRequestStringsResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<hidl_string>& data) {
    return Void();
}

}  // namespace vendor::samsung::hardware::radio::implementation
