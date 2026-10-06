package me.phh.ims

import android.telephony.Rlog
import android.telephony.SmsManager
import android.telephony.ims.stub.ImsSmsImplBase
import me.phh.sip.SipHandler

class PhhImsSms(
    val slotId: Int,
) : ImsSmsImplBase() {
    companion object {
        private const val TAG = "Phh ImsSms"
    }

    lateinit var sipHandler: SipHandler

    override fun sendSms(
        token: Int,
        messageRef: Int,
        format: String?,
        smsc: String?,
        isRetry: Boolean,
        pdu: ByteArray,
    ) {
        try {
            Rlog.d(TAG, "$slotId sendSms $token, $messageRef, $format, $smsc")
            if (format != "3gpp") {
                onSendSmsResultError(
                    token,
                    messageRef,
                    ImsSmsImplBase.SEND_STATUS_ERROR,
                    SmsManager.RESULT_INVALID_SMS_FORMAT,
                    RESULT_NO_NETWORK_ERROR,
                )
                return
            }
            if (::sipHandler.isInitialized == false) {
                onSendSmsResultError(
                    token,
                    messageRef,
                    ImsSmsImplBase.SEND_STATUS_ERROR_RETRY,
                    SmsManager.RESULT_ERROR_NO_SERVICE,
                    RESULT_NO_NETWORK_ERROR,
                )
                return
            }
            sipHandler.sendSms(
                smsc,
                pdu,
                messageRef,
                {
                    onSendSmsResultSuccess(token, messageRef)
                },
                {
                    onSendSmsResultError(
                        token,
                        messageRef,
                        ImsSmsImplBase.SEND_STATUS_ERROR,
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                        RESULT_NO_NETWORK_ERROR,
                    )
                },
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed sending sms", t)
        }
    }

    override fun acknowledgeSms(
        token: Int,
        messageRef: Int,
        result: Int,
    ) {
        Rlog.d(TAG, "$slotId acknowledgeSms $token, $messageRef, $result")

        val error = result != ImsSmsImplBase.DELIVER_STATUS_OK
        sipHandler.sendSmsAck(token, messageRef, error)
    }

    override fun acknowledgeSmsReport(
        token: Int,
        messageRef: Int,
        result: Int,
    ) {
        Rlog.d(TAG, "$slotId acknowledgeSmsReport $token, $messageRef, $result")
    }

    override fun onReady() {
        Rlog.d(TAG, "$slotId onReady")
    }
}
