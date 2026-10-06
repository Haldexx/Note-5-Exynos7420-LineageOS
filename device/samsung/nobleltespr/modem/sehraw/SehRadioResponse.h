#pragma once

#include <vendor/samsung/hardware/radio/2.0/ISehRadioResponse.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>

namespace vendor::samsung::hardware::radio::implementation {

void sehraw_on_response(int32_t serial, int32_t error, const ::android::hardware::hidl_vec<int8_t>& data);

using ::android::hardware::hidl_array;
using ::android::hardware::hidl_memory;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::sp;

struct SehRadioResponse : public V2_0::ISehRadioResponse {
    Return<void> getIccCardStatusResponse() override;
    Return<void> supplyNetworkDepersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> dialResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getCurrentCallsResponse() override;
    Return<void> getImsRegistrationStateResponse() override;
    Return<void> setImsCallListResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getPreferredNetworkListResponse() override;
    Return<void> setPreferredNetworkListResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> sendEncodedUssdResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getDisable2gResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t isDisable) override;
    Return<void> setDisable2gResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getCnapResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t m) override;
    Return<void> getPhonebookStorageInfoResponse() override;
    Return<void> getUsimPhonebookCapabilityResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<int32_t>& phonebookCapability) override;
    Return<void> setSimOnOffResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> setSimInitEventResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getSimLockInfoResponse() override;
    Return<void> supplyIccPersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> changeIccPersonalizationResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getPhonebookEntryResponse() override;
    Return<void> accessPhonebookEntryResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t SimPhonmebookAccessResp) override;
    Return<void> getCellBroadcastConfigResponse() override;
    Return<void> emergencySearchResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t respEmergencySearch) override;
    Return<void> emergencyControlResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getAtrResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_string& atr) override;
    Return<void> sendCdmaSmsExpectMoreResponse() override;
    Return<void> sendSmsResponse() override;
    Return<void> sendSMSExpectMoreResponse() override;
    Return<void> sendCdmaSmsResponse() override;
    Return<void> sendImsSmsResponse() override;
    Return<void> getStoredMsgCountFromSimResponse() override;
    Return<void> readSmsFromSimResponse() override;
    Return<void> writeSmsToSimResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, int32_t index) override;
    Return<void> setDataAllowedResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> getCsgListResponse() override;
    Return<void> selectCsgManualResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> setMobileDataSettingResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info) override;
    Return<void> sendRequestRawResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<int8_t>& data) override;
    Return<void> sendRequestStringsResponse(const ::android::hardware::radio::V1_0::RadioResponseInfo& info, const hidl_vec<hidl_string>& data) override;

};

}  // namespace vendor::samsung::hardware::radio::implementation
