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

    @Volatile private var incomingCallId: String? = null
    private var incomingAccepted: (() -> Unit)? = null
    private var incomingEnded: ((ImsReasonInfo) -> Unit)? = null

    private var outgoingCallId: String? = null
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

            override fun hold(profile: ImsStreamMediaProfile) {
                sipHandler.myHandler.post {
                    if (this::mListener.isInitialized) mListener.callSessionHoldFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS hold is not implemented"))
                }
            }
            override fun resume(profile: ImsStreamMediaProfile) {
                sipHandler.myHandler.post {
                    if (this::mListener.isInitialized) mListener.callSessionResumeFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS resume is not implemented"))
                }
            }
            override fun merge() {
                sipHandler.myHandler.post {
                    if (this::mListener.isInitialized) mListener.callSessionMergeFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS conference is not implemented"))
                }
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
                sipHandler.call(callee, mCallId)

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
                outgoingCallId = mCallId
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
                // Report the call's network type (WiFi vs LTE) as soon as it is progressing so the
                // VoWiFi indicator appears while placing/ringing, not only once connected. This is
                // an ignoreState update, so it refreshes the RAT without changing the call state.
                updateCallNetworkType(session.currentProfile)
                session.mListener.callSessionUpdated(session.currentProfile)
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
                // Re-evaluate the transport at connect so a WiFi->LTE fallback during setup is
                // reflected (the VoWiFi indicator is removed if the call ended up on VoLTE).
                updateCallNetworkType(callProfile)
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

        sipHandler.onIncomingCallAccepted = { callId ->
            sipHandler.myHandler.post {
                if (incomingCallId == callId) incomingAccepted?.invoke()
            }
        }
        sipHandler.onIncomingCall = incoming@{ _: Object, from: String, extras: Map<String, String> ->
            val callId = extras["call-id"] ?: return@incoming
            val callProfile = ImsCallProfile(
                ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE,
                Bundle(), ImsStreamMediaProfile(
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
            val session = object : ImsCallSessionImplBase() {
                @Volatile var mState = State.INITIATED
                private var listener: ImsCallSessionListener? = null
                private var connectedNotified = false
                private var terminalNotified = false
                private var terminalReason: ImsReasonInfo? = null

                fun notifyAccepted() {
                    if (mState == State.TERMINATED) return
                    mState = State.ESTABLISHED
                    val target = listener ?: return
                    if (!connectedNotified) {
                        connectedNotified = true
                        target.callSessionInitiated(callProfile)
                    }
                }
                fun notifyEnded(reason: ImsReasonInfo) {
                    mState = State.TERMINATED
                    terminalReason = reason
                    val target = listener ?: return
                    if (!terminalNotified) {
                        terminalNotified = true
                        target.callSessionTerminated(reason)
                    }
                }
                override fun getCallId(): String = callId
                override fun getState(): Int = mState
                override fun hold(profile: ImsStreamMediaProfile) {
                    sipHandler.myHandler.post { listener?.callSessionHoldFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS hold is not implemented")) }
                }
                override fun resume(profile: ImsStreamMediaProfile) {
                    sipHandler.myHandler.post { listener?.callSessionResumeFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS resume is not implemented")) }
                }
                override fun merge() {
                    sipHandler.myHandler.post { listener?.callSessionMergeFailed(
                        ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE, 0, "IMS conference is not implemented")) }
                }
                override fun isInCall(): Boolean = mState == State.ESTABLISHED
                override fun getCallProfile(): ImsCallProfile = callProfile
                override fun getLocalCallProfile(): ImsCallProfile = callProfile
                override fun getRemoteCallProfile(): ImsCallProfile = callProfile
                override fun setListener(target: ImsCallSessionListener) {
                    sipHandler.myHandler.post {
                        if (listener !== target) {
                            connectedNotified = false
                            terminalNotified = false
                        }
                        listener = target
                        if (mState == State.ESTABLISHED) notifyAccepted()
                        terminalReason?.let(::notifyEnded)
                    }
                }
                override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                    sipHandler.myHandler.post {
                        if (mState == State.TERMINATED || mState == State.ESTABLISHED || incomingCallId != callId) return@post
                        if (sipHandler.acceptCall(callId)) mState = State.ESTABLISHING
                    }
                }
                override fun reject(reason: Int) {
                    sipHandler.myHandler.post {
                        if (mState == State.TERMINATED || incomingCallId != callId) return@post
                        try { sipHandler.rejectCall(callId) }
                        catch (e: Exception) { Rlog.w(TAG, "Incoming rejection failed", e) }
                        finally { notifyEnded(ImsReasonInfo(ImsReasonInfo.CODE_USER_DECLINE, 0, "Declined")) }
                    }
                }
                override fun terminate(reason: Int) {
                    sipHandler.myHandler.post {
                        if (mState == State.TERMINATED || incomingCallId != callId) return@post
                        try { sipHandler.terminateCall(callId) }
                        catch (e: Exception) { Rlog.w(TAG, "Incoming termination failed", e) }
                        finally {
                            notifyEnded(ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, "Local ended call"))
                            if (incomingCallId == callId) {
                                incomingCallId = null
                                incomingAccepted = null
                                incomingEnded = null
                            }
                        }
                    }
                }
            }
            incomingCallId = callId
            incomingAccepted = session::notifyAccepted
            incomingEnded = session::notifyEnded
            notifyIncomingCall(session, Bundle())
        }
        sipHandler.onCancelledCall = { _: Object, _: String, extras: Map<String, String> ->
            sipHandler.myHandler.post {
                val status = extras["statusCode"]?.toIntOrNull() ?: -1
                val reason = if (status >= 400)
                    ImsReasonInfo(ImsReasonInfo.CODE_NETWORK_REJECT, status, extras["statusString"] ?: "SIP call failed")
                    else ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, 0, "Remote ended call")
                val id = extras["call-id"]
                if (incomingCallId != null) {
                    if (id != null && id != incomingCallId) return@post
                    incomingEnded?.invoke(reason)
                    incomingCallId = null
                    incomingAccepted = null
                    incomingEnded = null
                } else {
                    if (id != null && id != outgoingCallId) return@post
                    val outgoing = outgoingListener ?: return@post
                    outgoingStateChanged?.invoke(ImsCallSessionImplBase.State.TERMINATED)
                    outgoingListener = null
                    outgoingStateChanged = null
                    outgoing.callSessionTerminated(reason)
                }
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
