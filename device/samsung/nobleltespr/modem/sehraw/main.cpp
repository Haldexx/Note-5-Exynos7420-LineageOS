#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include <hidl/HidlTransportSupport.h>
#include <hidl/Static.h>
#include <vendor/samsung/hardware/radio/2.0/BnHwSehRadioResponse.h>
#include <vendor/samsung/hardware/radio/2.0/ISehRadio.h>

#include "SehRadioIndication.h"
#include "SehRadioResponse.h"

using ::android::sp;
using ::android::status_t;
using ::android::hardware::hidl_vec;
using ::android::hardware::Parcel;
using vendor::samsung::hardware::radio::V2_0::BnHwSehRadioResponse;
using vendor::samsung::hardware::radio::V2_0::ISehRadio;
using vendor::samsung::hardware::radio::V2_0::ISehRadioResponse;
namespace impl = vendor::samsung::hardware::radio::implementation;

struct StockCodeResponseStub : public BnHwSehRadioResponse {
    static constexpr uint32_t kStockGetAvailableNetworks = 6;
    static constexpr uint32_t kStockLast = 40;  // sendRequestStringsResponse
    using BnHwSehRadioResponse::BnHwSehRadioResponse;
    status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags,
                        TransactCallback cb) override {
        if (code == kStockGetAvailableNetworks)
            return ::android::OK;  // oneway, not in the tree's interface; nothing to deliver
        if (code > kStockGetAvailableNetworks && code <= kStockLast)
            code -= 1;
        return BnHwSehRadioResponse::onTransact(code, data, reply, flags, cb);
    }
};

static std::mutex gLock;
static std::condition_variable gCond;
static bool gDone;
static int32_t gSerial, gError;
static std::vector<int8_t> gData;

namespace vendor::samsung::hardware::radio::implementation {
void sehraw_on_response(int32_t serial, int32_t error, const hidl_vec<int8_t>& data) {
    std::lock_guard<std::mutex> l(gLock);
    gSerial = serial;
    gError = error;
    gData.assign(data.begin(), data.end());
    gDone = true;
    gCond.notify_all();
}
}  // namespace vendor::samsung::hardware::radio::implementation

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <hex bytes> [timeout seconds]\n", argv[0]);
        return 2;
    }
    std::string hex = argv[1];
    if (hex.size() % 2 || hex.empty()) {
        fprintf(stderr, "hex must have an even number of digits\n");
        return 2;
    }
    std::vector<uint8_t> bytes;
    for (size_t i = 0; i < hex.size(); i += 2)
        bytes.push_back(static_cast<uint8_t>(strtoul(hex.substr(i, 2).c_str(), nullptr, 16)));
    int timeout = argc > 2 ? atoi(argv[2]) : 10;

    android::hardware::configureRpcThreadpool(1, false);
    sp<ISehRadio> radio = ISehRadio::getService("slot1");
    if (radio == nullptr) {
        fprintf(stderr, "ISehRadio/slot1 not available\n");
        return 1;
    }
    ::android::hardware::details::getBnConstructorMap().set(
            ISehRadioResponse::descriptor, [](void* iface) -> sp<::android::hardware::IBinder> {
                return new StockCodeResponseStub(static_cast<ISehRadioResponse*>(iface));
            });
    sp<impl::SehRadioResponse> response = new impl::SehRadioResponse();
    sp<impl::SehRadioIndication> indication = new impl::SehRadioIndication();
    radio->setResponseFunction(response, indication);

    const int32_t serial = 0x5e4a0001;
    hidl_vec<uint8_t> data(bytes);
    auto ret = radio->sendRequestRaw(serial, data);
    if (!ret.isOk()) {
        fprintf(stderr, "sendRequestRaw failed: %s\n", ret.description().c_str());
        return 1;
    }
    std::unique_lock<std::mutex> l(gLock);
    if (!gCond.wait_for(l, std::chrono::seconds(timeout), [] { return gDone; })) {
        fprintf(stderr, "no reply within %d s\n", timeout);
        return 1;
    }
    printf("serial %#x error %d length %zu\n", gSerial, gError, gData.size());
    for (size_t i = 0; i < gData.size(); i++)
        printf("%02x%s", static_cast<uint8_t>(gData[i]), (i + 1) % 16 ? " " : "\n");
    printf("\n");
    return gError == 0 ? 0 : 3;
}
