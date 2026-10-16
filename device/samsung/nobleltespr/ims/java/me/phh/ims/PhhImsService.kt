// Maintained by Haldexx (https://github.com/Haldexx)

package me.phh.ims

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.telephony.Rlog
import android.telephony.ims.ImsService
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.feature.RcsFeature
import android.telephony.ims.stub.ImsConfigImplBase
import android.telephony.ims.stub.ImsFeatureConfiguration
import android.telephony.ims.stub.ImsRegistrationImplBase

class PhhImsService : ImsService() {
    companion object {
        private const val TAG = "PHH ImsService"
        var instance: PhhImsService? = null
    }

    val receiver: PhhImsBroadcastReceiver = PhhImsBroadcastReceiver()

    override fun onCreate() {
        super.onCreate()
        Rlog.d(TAG, "onCreate")

        val intentFilter = IntentFilter()
        intentFilter.addAction(receiver.ALARM_PERIODIC_REGISTER)
        intentFilter.addAction(receiver.SELECT_PLMN)
        intentFilter.addAction(receiver.SCAN_PLMN)
        this.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)

        this.armPeriodicRegisterAlarm()
    }

    fun armPeriodicRegisterAlarm() {
        val alarmManager = this.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(receiver.ALARM_PERIODIC_REGISTER).setPackage(packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 3_000_000,
            pendingIntent,
        )
        Rlog.d(TAG, "Alarm set")
    }

    private val features = mutableMapOf<Int, PhhMmTelFeature>()
    private val configs = mutableMapOf<Int, PhhImsConfig>()
    private val registrations = mutableMapOf<Int, ImsRegistrationImplBase>()

    override fun querySupportedImsFeatures(): ImsFeatureConfiguration {
        val count = getSystemService(TelephonyManager::class.java)
            ?.activeModemCount?.coerceIn(1, 2) ?: 1
        val builder = ImsFeatureConfiguration.Builder()
        for (slotId in 0 until count) builder.addFeature(slotId, ImsFeature.FEATURE_MMTEL)
        return builder.build()
    }

    @Synchronized
    fun featuresSnapshot(): List<PhhMmTelFeature> = features.values.toList()

    @Synchronized
    override fun createMmTelFeature(slotId: Int): MmTelFeature {
        require(slotId in 0..1) { "Unsupported IMS slot" }
        features[slotId]?.let { return it }
        val feature = PhhMmTelFeature(slotId)
        features[slotId] = feature
        try {
            feature.prepare(this)
        } catch (error: Throwable) {
            feature.onFeatureRemoved()
            throw error
        }
        return feature
    }

    @Synchronized
    fun releaseMmTelFeature(slotId: Int, feature: PhhMmTelFeature) {
        if (features[slotId] !== feature) return
        features.remove(slotId)
        configs.remove(slotId)
        registrations.remove(slotId)
    }

    override fun createRcsFeature(slotId: Int): RcsFeature? {
        Rlog.d(TAG, "createRcsFeature")
        return null
    }

    @Synchronized
    override fun getConfig(slotId: Int): ImsConfigImplBase {
        require(slotId in 0..1) { "Unsupported IMS slot" }
        return configs.getOrPut(slotId) { PhhImsConfig() }
    }

    class LocalBinder : Binder() {
        fun getService(): PhhImsService {
            Rlog.d(TAG, "LocalBinder getService")
            return PhhImsService()
        }
    }

    @Synchronized
    override fun getRegistration(slotId: Int): ImsRegistrationImplBase {
        require(slotId in 0..1) { "Unsupported IMS slot" }
        return registrations.getOrPut(slotId) { ImsRegistrationImplBase() }
    }

    override fun onDestroy() {
        Rlog.d(TAG, "onDestroy")
        featuresSnapshot().forEach { it.onFeatureRemoved() }
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(receiver.ALARM_PERIODIC_REGISTER).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        unregisterReceiver(receiver)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun readyForFeatureCreation() {
        Rlog.d(TAG, "readyForFeatureCreation")
        if (instance != null && instance !== this) {
            throw RuntimeException()
        }
        instance = this
    }
}
