package me.phh.ims;

import android.telephony.TelephonyManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.stub.ImsRegistrationImplBase;

public class PhhMmTelFeatureProtected extends MmTelFeature {
    private int lteCapabilities;
    private int iwlanCapabilities;
    private int activeTech = ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
    private static final int SUPPORTED = MmTelCapabilities.CAPABILITY_TYPE_VOICE
            | MmTelCapabilities.CAPABILITY_TYPE_SMS;

    public PhhMmTelFeatureProtected(int slotId) {}

    public static int callNetworkType(int registrationTech) {
        if (registrationTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN)
            return TelephonyManager.NETWORK_TYPE_IWLAN;
        if (registrationTech == ImsRegistrationImplBase.REGISTRATION_TECH_LTE)
            return TelephonyManager.NETWORK_TYPE_LTE;
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    public static int bearerRegistrationTech(boolean legacyDevice, String iface, int subtype) {
        if (legacyDevice && iface != null) {
            if (iface.matches("rmnet[0-9]+"))
                return ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
            if (iface.matches("ipsec[0-9]+"))
                return ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
        }
        return subtype == TelephonyManager.NETWORK_TYPE_IWLAN
                ? ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
                : ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
    }

    public synchronized void setActiveRegistrationTech(int tech) {
        activeTech = tech;
        publishCapabilities();
    }

    private void publishCapabilities() {
        int enabled = activeTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
                ? iwlanCapabilities : lteCapabilities;
        MmTelCapabilities result = new MmTelCapabilities();
        result.addCapabilities(enabled);
        notifyCapabilitiesStatusChanged(result);
    }

    private void change(CapabilityChangeRequest.CapabilityPair pair, boolean enable) {
        int cap = pair.getCapability() & SUPPORTED;
        if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_LTE) {
            lteCapabilities = enable ? lteCapabilities | cap : lteCapabilities & ~cap;
        } else if (pair.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
            iwlanCapabilities = enable ? iwlanCapabilities | cap : iwlanCapabilities & ~cap;
        }
    }

    @Override
    public synchronized void changeEnabledCapabilities(CapabilityChangeRequest request,
            ImsFeature.CapabilityCallbackProxy callback) {
        for (CapabilityChangeRequest.CapabilityPair pair : request.getCapabilitiesToEnable())
            change(pair, true);
        for (CapabilityChangeRequest.CapabilityPair pair : request.getCapabilitiesToDisable())
            change(pair, false);
        publishCapabilities();
    }
}
