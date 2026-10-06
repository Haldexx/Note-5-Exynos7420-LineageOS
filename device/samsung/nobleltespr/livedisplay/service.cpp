#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <livedisplay/samsung/DisplayColorCalibration.h>
#include <livedisplay/samsung/DisplayModes.h>
#include <livedisplay/samsung/ReadingEnhancementExynos.h>
#include <livedisplay/samsung/SunlightEnhancementExynos.h>
#include <cstdlib>
#include <string>

using namespace aidl::vendor::lineage::livedisplay::samsung;

template <typename T>
bool registerFeature() {
    static const auto feature = ndk::SharedRefBase::make<T>();
    if (!feature->isSupported()) {
        LOG(WARNING) << "Unavailable display control: " << T::descriptor;
        return true;
    }
    const std::string instance = std::string(T::descriptor) + "/default";
    const auto status = AServiceManager_addService(feature->asBinder().get(), instance.c_str());
    if (status != STATUS_OK) {
        LOG(ERROR) << "Failed to register " << instance << ": " << status;
        return false;
    }
    return true;
}

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    if (!registerFeature<DisplayColorCalibration>() ||
        !registerFeature<DisplayModes>() ||
        !registerFeature<ReadingEnhancementExynos>() ||
        !registerFeature<SunlightEnhancementExynos>()) {
        return EXIT_FAILURE;
    }
    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
