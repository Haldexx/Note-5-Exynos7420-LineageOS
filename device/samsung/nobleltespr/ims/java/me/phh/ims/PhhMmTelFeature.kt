package me.phh.ims

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.telephony.Rlog
import android.telephony.ServiceState
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsCallProfile
import android.telephony.ims.ImsCallSessionListener
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsStreamMediaProfile
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.stub.ImsCallSessionImplBase
import android.telephony.ims.stub.ImsMultiEndpointImplBase
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsSmsImplBase
import android.telephony.ims.stub.ImsUtImplBase
import me.phh.sip.SipHandler
import me.phh.sip.randomBytes
import me.phh.sip.toHex
import java.lang.Object
import java.util.concurrent.Executors

class PhhMmTelFeature(
    val slotId: Int,
) : PhhMmTelFeatureProtected(slotId) {
    companion object {
        private const val TAG = "PHH MmTelFeature"

        private const val INVITE_TIMEOUT_MS = 32_000L
    }

    var telephonyManager: TelephonyManager? = null

    private val setupDone = java.util.concurrent.atomic.AtomicBoolean(false)

    private var outgoingListener: ImsCallSessionListener? = null
    private var outgoingStateChanged: ((Int) -> Unit)? = null

    val imsSms = PhhImsSms(slotId)
    lateinit var sipHandler: SipHandler

    fun getSipHandlerOrNull(): SipHandler? = if (this::sipHandler.isInitialized) sipHandler else null

    private var serviceStateListener: PhoneStateListener? = null

    fun prepare(context: Context) {
        if (!setupDone.compareAndSet(false, true)) return
        featureState = STATE_INITIALIZING
        val subId = SubscriptionManager.getSubId(slotId)?.firstOrNull()
            ?: SubscriptionManager.getDefaultSubscriptionId()
        telephonyManager = context.getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(subId)
        val listener = object : PhoneStateListener(context.mainExecutor) {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                val registered = serviceState.networkRegistrationInfoList.any {
                    it.isRegistered && !it.registeredPlmn.isNullOrEmpty()
                }
                if (!registered) return
                val subscription = context.getSystemService(SubscriptionManager::class.java)
                    .getActiveSubscriptionInfoForSimSlotIndex(slotId) ?: return
                val manager = context.getSystemService(TelephonyManager::class.java)
                    .createForSubscriptionId(subscription.subscriptionId)
                if (manager.simOperator.length !in 5..6) return
                featureState = STATE_READY
                telephonyManager?.listen(this, PhoneStateListener.LISTEN_NONE)
                serviceStateListener = null
            }
        }
        serviceStateListener = listener
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
    }

    override fun createCallProfile(
        callSessionType: Int,
        callType: Int,
    ): ImsCallProfile {
        Rlog.d(TAG, "$slotId createCallProfile $callSessionType $callType")
        return ImsCallProfile(callSessionType, callType).also { updateCallNetworkType(it) }
    }

    private fun updateCallNetworkType(profile: ImsCallProfile) {
        val tech = if (this::sipHandler.isInitialized) sipHandler.refreshRegistrationTech() else -1
        profile.setCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
            PhhMmTelFeatureProtected.callNetworkType(tech))
    }

    override fun createCallSession(profile: ImsCallProfile): ImsCallSessionImplBase {
        Rlog.d(TAG, "$slotId createCallSession")
        updateCallNetworkType(profile)
        return object : ImsCallSessionImplBase() {
            private val mCallId = randomBytes(12).toHex()
            lateinit var mListener: ImsCallSessionListener
            var mState = State.INITIATED
            var currentProfile = profile

            override fun getCallId(): String = mCallId

            override fun close() {
                Rlog.d(TAG, "Closing call")
            }

            override fun accept(
                callType: Int,
                profile: ImsStreamMediaProfile,
            ) {
                Rlog.d(TAG, "Accepting call with callType $callType profile $profile")
            }

            override fun isInCall(): Boolean = mState == State.ESTABLISHED

            override fun getCallProfile(): ImsCallProfile = currentProfile
            override fun getLocalCallProfile(): ImsCallProfile = currentProfile

            override fun start(
                callee: String,
                profile: ImsCallProfile,
            ) {
                Rlog.d(TAG, "Starting call with $callee profile $profile")
                updateCallNetworkType(profile)
                currentProfile = profile
                sipHandler.call(callee)

                sipHandler.myHandler.postDelayed({
                    if (mState == State.INITIATED) {
                        Rlog.w(TAG, "No response to INVITE after ${INVITE_TIMEOUT_MS}ms, failing the call")
                        sipHandler.onOutgoingCallFailed?.invoke(408, "No response to INVITE")
                    }
                }, INVITE_TIMEOUT_MS)
            }

            override fun getState(): Int = mState

            override fun setListener(listener: ImsCallSessionListener) {
                Rlog.d(TAG, "Setting CallListener to $listener")
                mListener = listener
                outgoingListener = listener
                outgoingStateChanged = { mState = it }
            }

            override fun reject(reason: Int) {
                Rlog.d(TAG, "Rejecting call with reason $reason")
            }

            override fun terminate(reason: Int) {
                Rlog.d(TAG, "Terminating call with reason $reason")
                sipHandler.myHandler.post {
                    try {
                        sipHandler.terminateCall()
                    } catch (t: Throwable) {
                        Rlog.w(TAG, "terminateCall() failed, reporting the call ended anyway", t)
                    } finally {
                        val progressed = mState != State.INITIATED
                        mState = State.TERMINATED
                        outgoingListener = null
                        outgoingStateChanged = null
                        val reason = ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, "Kikoo")
                        if (progressed) mListener.callSessionTerminated(reason)
                        else mListener.callSessionInitiatedFailed(reason)
                    }
                }
            }
        }.also { session ->
            sipHandler.onOutgoingCallFailed = { code, detail ->
                Rlog.w(TAG, "Outgoing call rejected: SIP $code ($detail)")
                val hadProgressed = session.mState != ImsCallSessionImplBase.State.INITIATED
                session.mState = ImsCallSessionImplBase.State.TERMINATED
                outgoingListener = null
                outgoingStateChanged = null
                val reason = ImsReasonInfo(ImsReasonInfo.CODE_NETWORK_REJECT, code, detail)
                if (hadProgressed) {
                    Rlog.d(TAG, "Call had already progressed; reporting terminated, not start-failed")
                    session.mListener.callSessionTerminated(reason)
                } else {
                    session.mListener.callSessionInitiatedFailed(reason)
                }
            }
            sipHandler.onOutgoingCallProgressing = { earlyMedia ->
                session.mState = ImsCallSessionImplBase.State.ESTABLISHING
                session.mListener.callSessionProgressing(
                    ImsStreamMediaProfile(
                        ImsStreamMediaProfile.AUDIO_QUALITY_AMR,
                        if (earlyMedia) ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE
                        else ImsStreamMediaProfile.DIRECTION_INACTIVE,
                        ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                        ImsStreamMediaProfile.DIRECTION_INACTIVE,
                        ImsStreamMediaProfile.RTT_MODE_DISABLED,
                    ),
                )
            }
            sipHandler.onOutgoingCallConnected = { _: Object, _: Map<String, String> ->
                Rlog.d(TAG, "Outgoing call connected")
                session.mState = ImsCallSessionImplBase.State.ESTABLISHED
                val callProfile =
                    ImsCallProfile(
                        ImsCallProfile.SERVICE_TYPE_NORMAL,
                        ImsCallProfile.CALL_TYPE_VOICE,
                        Bundle(),
                        ImsStreamMediaProfile(
                            ImsStreamMediaProfile.AUDIO_QUALITY_AMR,
                            ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                            ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                            ImsStreamMediaProfile.DIRECTION_INACTIVE,
                            ImsStreamMediaProfile.RTT_MODE_DISABLED,
                        ),
                    )
                callProfile.setCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
                    session.currentProfile.getCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
                        TelephonyManager.NETWORK_TYPE_UNKNOWN))
                session.currentProfile = callProfile
                session.mListener.callSessionInitiated(callProfile)
            }
        }
    }

    fun getInstance(slotId: Int): PhhMmTelFeature {
        Rlog.d(TAG, "$slotId getInstance")
        return PhhMmTelFeature(slotId)
    }

    override fun getMultiEndpoint(): ImsMultiEndpointImplBase {
        Rlog.d(TAG, "$slotId getMultiEndpoint")
        return ImsMultiEndpointImplBase()
    }

    override fun getSmsImplementation(): ImsSmsImplBase {
        Rlog.d(TAG, "$slotId getSmsImplementation")
        return imsSms
    }

    override fun getUt(): ImsUtImplBase {
        Rlog.d(TAG, "$slotId getUt")
        return ImsUtImplBase()
    }

    override fun onFeatureReady() {
        Rlog.d(TAG, "$slotId onFeatureReady")
        if (this::sipHandler.isInitialized) return

        val imsService = PhhImsService.Companion.instance!!
        sipHandler = SipHandler(imsService)
        sipHandler.imsFailureCallback = { imsService.getRegistration(slotId).onDeregistered(null) }
        sipHandler.imsReadyCallback = {
            setActiveRegistrationTech(sipHandler.registrationTech)
            imsService.getRegistration(slotId).onRegistered(sipHandler.registrationTech)
        }
        sipHandler.imsRegisteringCallback = {
            imsService.getRegistration(slotId).onRegistering(sipHandler.registrationTech)
        }
        imsSms.sipHandler = sipHandler
        sipHandler.onSmsReceived = imsSms::onSmsReceived
        sipHandler.onSmsStatusReportReceived = imsSms::onSmsStatusReportReceived

        var callListener: ImsCallSessionListener? = null
        sipHandler.onIncomingCall = { handle: Object, from: String, extras: Map<String, String> ->
            outgoingListener = null
            outgoingStateChanged = null
            val callProfile =
                ImsCallProfile(
                    ImsCallProfile.SERVICE_TYPE_NORMAL,
                    ImsCallProfile.CALL_TYPE_VOICE,
                    Bundle(),
                    ImsStreamMediaProfile(
                        ImsStreamMediaProfile.AUDIO_QUALITY_AMR,
                        ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                        ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                        ImsStreamMediaProfile.DIRECTION_INACTIVE,
                        ImsStreamMediaProfile.RTT_MODE_DISABLED,
                    ),
                )

            updateCallNetworkType(callProfile)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, from)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_DISPLAY_TEXT, from)
            val oir = when (extras["caller-presentation"]) {
                "ALLOWED" -> ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED
                "RESTRICTED" -> ImsCallProfile.OIR_PRESENTATION_RESTRICTED
                else -> ImsCallProfile.OIR_PRESENTATION_UNKNOWN
            }
            callProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, oir)
            Rlog.d(TAG, "Incoming caller OIR=$oir")
            notifyIncomingCall(
                object : ImsCallSessionImplBase() {
                    var mState = State.IDLE

                    override fun getCallProfile(): ImsCallProfile = callProfile

                    override fun setListener(listener: ImsCallSessionListener) {
                        Rlog.d(TAG, "Setting CallListener to $listener")
                        callListener = listener
                    }

                    override fun getCallId(): String = extras["call-id"]!!

                    override fun getLocalCallProfile(): ImsCallProfile = callProfile

                    override fun getRemoteCallProfile(): ImsCallProfile = callProfile

                    override fun getProperty(name: String): String {
                        Rlog.d(TAG, "ImsCallSession.getProperty " + name)
                        return ""
                    }

                    override fun getState(): Int = mState

                    override fun start(
                        callee: String,
                        profile: ImsCallProfile,
                    ) {
                        Rlog.d(TAG, "Starting call with $callee")
                    }

                    override fun accept(
                        callType: Int,
                        profile: ImsStreamMediaProfile,
                    ) {
                        Rlog.d(TAG, "Accepting call with profile $profile")
                        sipHandler.acceptCall()
                        mState = State.ESTABLISHED
                        callListener?.callSessionInitiated(callProfile)
                    }

                    override fun deflect(deflectNumber: String?) {
                        Rlog.d(TAG, "Deflecting call to $deflectNumber")
                    }

                    override fun reject(reason: Int) {
                        sipHandler.rejectCall()
                        Rlog.d(TAG, "Rejecting call $reason")
                    }

                    override fun terminate(reason: Int) {
                        Rlog.d(TAG, "Terminating call")
                        sipHandler.myHandler.post {
                            try {
                                sipHandler.terminateCall()
                            } catch (t: Throwable) {
                                Rlog.w(TAG, "terminateCall() failed, reporting the call ended anyway", t)
                            } finally {
                                callListener?.callSessionTerminated(
                                    ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, "Kikoo"))
                            }
                        }
                    }
                },
                Bundle(),
            )
        }
        sipHandler.onCancelledCall = { param: Object, s: String, map: Map<String, String> ->
            Rlog.d(TAG, "Cancelling call")
            val outgoing = outgoingListener
            if (outgoing != null) {
                outgoingStateChanged?.invoke(ImsCallSessionImplBase.State.TERMINATED)
                outgoingListener = null
                outgoingStateChanged = null
                outgoing.callSessionTerminated(ImsReasonInfo(
                    ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, 0, "Remote ended call"))
            }
            val statusCode = map["statusCode"]?.toInt() ?: -1
            if (statusCode >= 400) {
                val statusMessage = map["statusString"] ?: "Kikoo"
                callListener?.callSessionTerminated(ImsReasonInfo(ImsReasonInfo.CODE_NETWORK_REJECT, 0, statusMessage))
            } else {
                callListener?.callSessionTerminated(
                    ImsReasonInfo(
                        ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE,
                        0,
                        "Kikoo",
                    ),
                )
            }
        }

        sipHandler.getVolteNetwork()
    }

    override fun onFeatureRemoved() {
        Rlog.d(TAG, "$slotId onFeatureRemoved")
        serviceStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        serviceStateListener = null
    }

    override fun queryCapabilityConfiguration(
        capability: Int,
        radioTech: Int,
    ): Boolean {
        Rlog.d(TAG, "$slotId queryCapabilityConfiguration $capability $radioTech")
        return (radioTech == REGISTRATION_TECH_LTE || radioTech == REGISTRATION_TECH_IWLAN) &&
            (capability == MmTelCapabilities.CAPABILITY_TYPE_SMS || capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE)
    }

    override fun setUiTtyMode(
        mode: Int,
        onCompleteMessage: Message?,
    ) {
        Rlog.d(TAG, "$slotId setUiTtyMode $onCompleteMessage")
    }

    override fun shouldProcessCall(numbers: Array<out String>): Int {
        Rlog.d(TAG, "Should process call? ${numbers.toList()}")
        return PROCESS_CALL_IMS
    }
}
