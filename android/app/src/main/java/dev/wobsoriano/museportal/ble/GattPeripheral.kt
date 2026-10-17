package dev.wobsoriano.museportal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import dev.wobsoriano.museportal.protocol.Bytes
import dev.wobsoriano.museportal.protocol.Command
import dev.wobsoriano.museportal.protocol.Event
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The BLE side of gadget pairing: a GATT server with RX and TX and a connectable advertisement, as
 * `pair.advertise` describes. Everything runs on one handler thread; [emit] gets the commands to
 * send back to musecore.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class GattPeripheral(private val context: Context, private val emit: (Command) -> Unit) {
  private val thread = HandlerThread("ble").apply { start() }
  private val handler = Handler(thread.looper)
  private val manager = context.getSystemService(BluetoothManager::class.java)
  private val adapter: BluetoothAdapter? = manager?.adapter

  private var server: BluetoothGattServer? = null
  private var tx: BluetoothGattCharacteristic? = null
  private var rxUuid: UUID? = null
  private var central: BluetoothDevice? = null
  private var mtu = 0
  private var advertising: AdvertiseCallback? = null
  private var originalName: String? = null
  private val prepared = ByteArrayOutputStream()
  private var serviceAdded: ((Boolean) -> Unit)? = null
  // A newer pair.advertise or pair.unadvertise cancels the steps still waiting from an older one.
  private var session = 0

  private val queue =
      NotifyQueue(
          link = ::notifyNow,
          scheduler = { delay, task -> handler.postDelayed(task, delay) },
      )

  fun advertise(spec: Event.PairAdvertise) = handler.post { start(spec) }

  fun unadvertise() = handler.post { teardown() }

  fun notify(packets: List<Bytes>) = handler.post { queue.enqueue(packets.map { it.array }) }

  fun disconnect() = handler.post { central?.let { server?.cancelConnection(it) } }

  fun close() {
    handler.post {
      teardown()
      thread.quitSafely()
    }
  }

  private fun start(spec: Event.PairAdvertise) {
    teardown()
    val mySession = ++session
    val adapter = adapter ?: return fail("no Bluetooth adapter")
    if (!adapter.isEnabled) {
      Log.i(TAG, "enabling Bluetooth")
      adapter.enable()
    }
    awaitThen(mySession, { adapter.isEnabled }, 10_000, "Bluetooth did not turn on") {
      openServer(mySession, spec)
    }
  }

  private fun openServer(mySession: Int, spec: Event.PairAdvertise) {
    val service = UUID.fromString(spec.service)
    val rx = UUID.fromString(spec.rx)
    val txUuid = UUID.fromString(spec.tx)
    val server =
        manager?.openGattServer(context, callback) ?: return fail("could not open the GATT server")
    this.server = server
    rxUuid = rx
    val gattService = BluetoothGattService(service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    gattService.addCharacteristic(
        BluetoothGattCharacteristic(
            rx,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
    val txChar =
        BluetoothGattCharacteristic(
                txUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            .apply {
              addDescriptor(
                  BluetoothGattDescriptor(
                      CCCD,
                      BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            }
    gattService.addCharacteristic(txChar)
    tx = txChar
    serviceAdded = { ok ->
      if (mySession != session) Unit
      else if (!ok) fail("could not add the GATT service")
      else setNameThenAdvertise(mySession, spec)
    }
    if (!server.addService(gattService)) fail("could not add the GATT service")
  }


  // The scan response can only carry the adapter's own name, so the adapter is renamed while
  // pairing and restored afterwards.
  private fun setNameThenAdvertise(mySession: Int, spec: Event.PairAdvertise) {
    val adapter = adapter ?: return
    if (adapter.name != spec.name) {
      if (originalName == null) originalName = adapter.name
      adapter.name = spec.name
    }
    awaitThen(mySession, { adapter.name == spec.name }, 3_000, null) {
      if (adapter.name != spec.name) Log.w(TAG, "adapter name is still ${adapter.name}")
      startAdvertising(spec)
    }
  }

  private fun startAdvertising(spec: Event.PairAdvertise) {
    val advertiser = adapter?.bluetoothLeAdvertiser ?: return fail("this device cannot advertise")
    val settings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
    val data =
        AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(spec.service))
            .addManufacturerData(spec.manufacturerId, spec.manufacturerData.array)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
    val cb =
        object : AdvertiseCallback() {
          override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising as ${spec.name}")
            emit(Command.BleAdvertising(ok = true))
          }

          override fun onStartFailure(errorCode: Int) {
            handler.post { fail("advertising failed: ${advertiseError(errorCode)}") }
          }
        }
    advertising = cb
    advertiser.startAdvertising(settings, data, scanResponse, cb)
  }

  private fun fail(error: String) {
    Log.w(TAG, error)
    teardown()
    emit(Command.BleAdvertising(ok = false, error = error))
  }

  private fun teardown() {
    session++
    serviceAdded = null
    queue.clear()
    advertising?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) } }
    advertising = null
    central?.let { device -> server?.cancelConnection(device) }
    central = null
    server?.close()
    server = null
    tx = null
    rxUuid = null
    mtu = 0
    prepared.reset()
    originalName?.let { name -> adapter?.name = name }
    originalName = null
  }

  private fun awaitThen(mySession: Int, ready: () -> Boolean, timeoutMs: Long, timeoutError: String?, then: () -> Unit) {
    val deadline = System.currentTimeMillis() + timeoutMs
    fun check() {
      if (mySession != session) return
      when {
        ready() -> then()
        System.currentTimeMillis() < deadline -> handler.postDelayed(::check, 100)
        timeoutError != null -> fail(timeoutError)
        else -> then()
      }
    }
    check()
  }

  private fun notifyNow(packet: ByteArray): Boolean {
    val server = server
    val device = central
    val tx = tx
    if (server == null || device == null || tx == null) {
      Log.w(TAG, "notify with no central connected; dropping")
      return false
    }
    tx.value = packet
    val ok = server.notifyCharacteristicChanged(device, tx, false)
    if (!ok) Log.w(TAG, "notifyCharacteristicChanged refused a ${packet.size} byte packet")
    return ok
  }

  private val callback =
      object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
          handler.post { serviceAdded?.invoke(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
          handler.post {
            when (newState) {
              BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "central connected: ${device.address}")
                central = device
                mtu = 0
                emit(Command.BleConnected(device.address))
              }
              BluetoothProfile.STATE_DISCONNECTED ->
                  if (device == central) {
                    Log.i(TAG, "central disconnected: ${device.address}")
                    central = null
                    mtu = 0
                    queue.clear()
                    prepared.reset()
                    emit(Command.BleDisconnected)
                  }
            }
          }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
          handler.post { this@GattPeripheral.mtu = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
          val bytes = value ?: ByteArray(0)
          handler.post {
            val isRx = characteristic.uuid == rxUuid
            if (responseNeeded) {
              server?.sendResponse(
                  device,
                  requestId,
                  if (isRx) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                  offset,
                  bytes)
            }
            if (!isRx) return@post
            if (preparedWrite) prepared.write(bytes)
            else emit(Command.BleWrite(Bytes(bytes), mtu))
          }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
          handler.post {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && prepared.size() > 0) emit(Command.BleWrite(Bytes(prepared.toByteArray()), mtu))
            prepared.reset()
          }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
          handler.post {
            val value = characteristic.value ?: ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
          }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
          handler.post {
            descriptor.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
          }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
          handler.post {
            val value = descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
          }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
          handler.post {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
            queue.onSent()
          }
        }
      }

  companion object {
    private const val TAG = "MuseBle"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun advertiseError(code: Int) =
        when (code) {
          AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
          AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
          AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
          AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
          AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
          else -> "error $code"
        }
  }
}
