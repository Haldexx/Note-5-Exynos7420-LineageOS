package me.phh.ims

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ServiceManager
import android.telephony.AccessNetworkConstants
import android.telephony.Rlog
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.internal.telephony.ITelephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.concurrent.thread

class PhhImsBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PHH ImsBroadcastReceiver"
        private const val LAB_PACKAGE = "me.phh.ims.lab"
    }

    val ALARM_PERIODIC_REGISTER = "me.phh.ims.ALARM_PERIODIC_REGISTER"

    val SELECT_PLMN = "me.phh.ims.SELECT_PLMN"

    val SCAN_PLMN = "me.phh.ims.SCAN_PLMN"

    override fun onReceive(
        ctxt: Context,
        intent: Intent,
    ) {
        Rlog.d(TAG, "Alarm fired with ${intent.action}")
        if (intent.action == ALARM_PERIODIC_REGISTER) {
            val imsService = PhhImsService.Companion.instance!!
            imsService.armPeriodicRegisterAlarm()
            CoroutineScope(Dispatchers.IO).launch {
                val sipHandler = imsService.mmTelFeature?.getSipHandlerOrNull()
                try {
                    sipHandler?.register()
                } catch (e: IOException) {
                    Rlog.w(TAG, "Periodic REGISTER failed (stale socket), reconnecting", e)
                    try {
                        sipHandler?.connect()
                    } catch (e2: Throwable) {
                        Rlog.e(TAG, "Reconnect after failed REGISTER also failed", e2)
                        sipHandler?.imsFailureCallback?.invoke()
                    }
                }
            }
            return
        }
        if (intent.action == SELECT_PLMN) {
            selectPlmn(ctxt, intent.getStringExtra("plmn"))
            return
        }
        if (intent.action == SCAN_PLMN) {
            val pending = goAsync()
            thread {
                try {
                    scanPlmn(ctxt)
                } finally {
                    pending.finish()
                }
            }
            return
        }
    }

    private fun scanPlmn(ctxt: Context) {
        if (ctxt.packageName != LAB_PACKAGE) {
            Rlog.w(TAG, "Ignoring network scan request outside the lab package")
            return
        }
        try {
            val telephony =
                ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE))
            if (telephony == null) {
                Rlog.e(TAG, "No telephony service to scan with")
                return
            }
            val subId = SubscriptionManager.getDefaultSubscriptionId()
            Rlog.i(TAG, "Starting operator scan on subscription $subId")
            val result = telephony.getCellNetworkScanResults(subId, ctxt.packageName, null)
            if (result == null) {
                Rlog.w(TAG, "Operator scan returned nothing")
                return
            }
            val operators = result.operators
            Rlog.i(TAG, "Operator scan status=${result.status} count=${operators?.size ?: 0}")
            operators?.forEach { operator ->
                Rlog.i(
                    TAG,
                    "Operator scan found ${operator.operatorNumeric} " +
                        "(${operator.operatorAlphaLong}) state=${operator.state} ran=${operator.ran}",
                )
            }
        } catch (e: Throwable) {
            Rlog.e(TAG, "Operator scan failed", e)
        }
    }

    private fun selectPlmn(
        ctxt: Context,
        requested: String?,
    ) {
        if (ctxt.packageName != LAB_PACKAGE) {
            Rlog.w(TAG, "Ignoring network selection request outside the lab package")
            return
        }
        val request = parseNetworkSelection(requested)
        if (request == null) {
            Rlog.w(TAG, "Ignoring unusable network selection request")
            return
        }
        val telephonyManager = ctxt.getSystemService(TelephonyManager::class.java)
        if (telephonyManager == null) {
            Rlog.e(TAG, "No TelephonyManager to select a network with")
            return
        }
        try {
            when (request) {
                is NetworkSelectionRequest.Automatic -> {
                    telephonyManager.setNetworkSelectionModeAutomatic()
                    Rlog.i(TAG, "Network selection set to automatic")
                }
                is NetworkSelectionRequest.Manual -> {
                    val accepted =
                        telephonyManager.setNetworkSelectionModeManual(
                            request.plmn,
                            false,
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        )
                    Rlog.i(TAG, "Manual network selection of ${request.plmn} accepted=$accepted")
                }
            }
        } catch (e: Throwable) {
            Rlog.e(TAG, "Network selection request failed", e)
        }
    }
}
