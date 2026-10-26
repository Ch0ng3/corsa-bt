package dev.watchstrava.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the watch look like a standard Bluetooth LE heart rate strap
 * (Heart Rate Service 0x180D), so the Strava app on the iPhone can pair with it.
 * All state is touched on the main thread.
 */
@SuppressLint("MissingPermission") // permissions are granted before the service starts
class HrBroadcaster(private val context: Context, private val onChange: () -> Unit) {

    companion object {
        private val HR_SERVICE = uuid16(0x180D)
        private val HR_MEASUREMENT = uuid16(0x2A37)
        private val BODY_SENSOR_LOCATION = uuid16(0x2A38)
        private val CCCD = uuid16(0x2902)
        private const val LOCATION_WRIST: Byte = 2

        private fun uuid16(v: Int) =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
    }

    var connected = 0; private set
    var error: String? = null; private set

    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var measurement: BluetoothGattCharacteristic
    // Written on the main thread, also read from Binder threads (descriptor reads).
    private val subscribers: MutableSet<BluetoothDevice> = ConcurrentHashMap.newKeySet()

    fun start() {
        val bm = context.getSystemService(BluetoothManager::class.java)
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) return fail("Bluetooth spento")
        advertiser = adapter.bluetoothLeAdvertiser ?: return fail("Bluetooth sensore non supportato")
        // Advertising starts once the service is registered (onServiceAdded).
        server = bm.openGattServer(context, callback) ?: return fail("Bluetooth non disponibile")
        server!!.addService(buildService())
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        advertiser = null
        subscribers.clear()
        connected = 0
    }

    /** Sends one Heart Rate Measurement to every subscribed device. */
    fun send(bpm: Int?) {
        val s = server ?: return
        bpm ?: return
        // Flags 0x00: heart rate as uint8, no sensor-contact / energy / RR fields.
        val value = byteArrayOf(0, bpm.toByte())
        for (device in subscribers) {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, measurement, false, value)
            } else {
                @Suppress("DEPRECATION")
                measurement.value = value
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, measurement, false)
            }
        }
    }

    private fun fail(message: String) {
        error = message
        stop()
        onChange()
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(HR_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        measurement = BluetoothGattCharacteristic(
            HR_MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        service.addCharacteristic(measurement)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BODY_SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        return service
    }

    private fun startAdvertising(includeName: Boolean = true) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HR_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(includeName).build()
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            handler.post {
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Unit
                    // Watch name too long for the scan response: advertise without it.
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> startAdvertising(includeName = false)
                    else -> fail("Errore Bluetooth ($errorCode)")
                }
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
                else fail("Errore Bluetooth ($status)")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) return
            handler.post {
                subscribers -= device
                setConnected()
                // Some stacks stop advertising after a connection: restart so Strava can reconnect.
                if (server != null) {
                    runCatching { advertiser?.stopAdvertising(advertiseCallback) }
                    startAdvertising()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BODY_SENSOR_LOCATION && offset == 0) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, byteArrayOf(LOCATION_WRIST))
            } else {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = if (device in subscribers) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val status = if (descriptor.uuid == CCCD) {
                val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 1) != 0
                handler.post {
                    if (enable) subscribers += device else subscribers -= device
                    setConnected()
                }
                BluetoothGatt.GATT_SUCCESS
            } else {
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) respond(device, requestId, status, null)
        }
    }

    /** "Connected" = subscribed to HR notifications, i.e. Strava is actually listening. */
    private fun setConnected() {
        connected = subscribers.size
        onChange()
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, value: ByteArray?) {
        server?.sendResponse(device, requestId, status, 0, value)
    }
}
