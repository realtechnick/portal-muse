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
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import dev.wobsoriano.muse.core.BleEvents
import dev.wobsoriano.muse.core.BlePeripheral
import dev.wobsoriano.muse.pairing.Advertisement
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The BLE side of gadget pairing, as [GattPeripheral] describes it, but through the standard
 * public APIs: [BluetoothManager.openGattServer] and the adapter's advertiser. Ordinary Android
 * devices (tablets, phones) grant apps GATT this way, so this is the pairing path everywhere
 * except the Portal, whose Bluetooth stack never answers the standard calls; the Portal keeps
 * [GattPeripheral] and its [dev.wobsoriano.museportal.ble.gatt.DirectGatt] binder path.
 * Everything runs on one handler thread; [events] hears what happened on the link, for the
 * pairing Controller.
 */
@SuppressLint("MissingPermission")
class StandardGattPeripheral(private val context: Context, private val events: BleEvents) :
    BlePeripheral {
  private val thread = HandlerThread("ble").apply { start() }
  private val handler = Handler(thread.looper)
  private val adapter: BluetoothAdapter? =
      context.getSystemService(BluetoothManager::class.java)?.adapter

  private var server: BluetoothGattServer? = null
  private var rxUuid: UUID? = null
  private var txUuid: UUID? = null
  private var txChar: BluetoothGattCharacteristic? = null
  private var txValue = ByteArray(0)
  private var cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
  private var central: BluetoothDevice? = null
  private var mtu = 0
  private var advertisingCallback: AdvertisingSetCallback? = null
  private var originalName: String? = null
  private val prepared = ByteArrayOutputStream()
  // A newer advertise or unadvertise cancels the steps still waiting from an older one.
  private var session = 0

  private val queue =
      NotifyQueue(
          link = ::notifyNow,
          scheduler = { delay, task -> handler.postDelayed(task, delay) },
      )

  override fun advertise(ad: Advertisement) {
    handler.post { start(ad) }
  }

  override fun unadvertise() {
    handler.post { teardown() }
  }

  override fun notify(packets: List<ByteArray>) {
    handler.post { queue.enqueue(packets) }
  }

  override fun disconnect() {
    handler.post { central?.let { device -> server?.cancelConnection(device) } }
  }

  fun close() {
    handler.post {
      teardown()
      thread.quitSafely()
    }
  }

  private fun start(spec: Advertisement) {
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

  private fun openServer(mySession: Int, spec: Advertisement) {
    val manager =
        context.getSystemService(BluetoothManager::class.java)
            ?: return fail("no Bluetooth service")
    val rx = UUID.fromString(spec.rx)
    val tx = UUID.fromString(spec.tx)
    val rxCharacteristic =
        BluetoothGattCharacteristic(
            rx,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
    val txCharacteristic =
        BluetoothGattCharacteristic(
                tx,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            .apply {
              addDescriptor(
                  BluetoothGattDescriptor(
                      CCCD,
                      BluetoothGattDescriptor.PERMISSION_READ or
                          BluetoothGattDescriptor.PERMISSION_WRITE))
            }
    val service =
        BluetoothGattService(UUID.fromString(spec.service), BluetoothGattService.SERVICE_TYPE_PRIMARY)
    service.addCharacteristic(rxCharacteristic)
    service.addCharacteristic(txCharacteristic)

    val callback =
        object : BluetoothGattServerCallback() {
          override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
              if (mySession != session) return@post
              if (status != BluetoothGatt.GATT_SUCCESS) {
                return@post fail("could not add the GATT service ($status)")
              }
              setNameThenAdvertise(mySession, spec)
            }
          }

          override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
              if (mySession != session) return@post
              if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "central connected: ${device.address}")
                central = device
                mtu = 0
                events.connected(device.address)
              } else if (newState == BluetoothProfile.STATE_DISCONNECTED &&
                  device.address == central?.address) {
                Log.i(TAG, "central disconnected: ${device.address}")
                central = null
                mtu = 0
                cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                queue.clear()
                prepared.reset()
                events.disconnected()
              }
            }
          }

          override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { if (mySession == session) this@StandardGattPeripheral.mtu = mtu }
          }

          override fun onCharacteristicReadRequest(
              device: BluetoothDevice,
              requestId: Int,
              offset: Int,
              characteristic: BluetoothGattCharacteristic,
          ) {
            handler.post {
              if (mySession != session) return@post
              val value = if (characteristic.uuid == txUuid) txValue else ByteArray(0)
              val slice =
                  if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
              respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
          }

          override fun onDescriptorReadRequest(
              device: BluetoothDevice,
              requestId: Int,
              offset: Int,
              descriptor: BluetoothGattDescriptor,
          ) {
            handler.post {
              if (mySession != session) return@post
              val value = if (descriptor.uuid == CCCD) cccdValue else ByteArray(0)
              val slice =
                  if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
              respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
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
            handler.post {
              if (mySession != session) return@post
              val data = value ?: ByteArray(0)
              val allowed = characteristic.uuid == rxUuid
              if (responseNeeded) {
                respond(
                    device,
                    requestId,
                    if (allowed) BluetoothGatt.GATT_SUCCESS
                    else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    data)
              }
              if (allowed) {
                // Prepared parts are appended in arrival order, as in GattPeripheral.
                if (preparedWrite) prepared.write(data) else events.wrote(data, mtu)
              }
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
              if (mySession != session) return@post
              val data = value ?: ByteArray(0)
              val allowed = descriptor.uuid == CCCD
              if (responseNeeded) {
                respond(
                    device,
                    requestId,
                    if (allowed) BluetoothGatt.GATT_SUCCESS
                    else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    data)
              }
              if (allowed) cccdValue = data
            }
          }

          override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
              if (mySession != session) return@post
              respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
              if (execute && prepared.size() > 0) events.wrote(prepared.toByteArray(), mtu)
              prepared.reset()
            }
          }

          override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
              if (mySession != session) return@post
              if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
              queue.onSent()
            }
          }
        }

    val server = manager.openGattServer(context, callback) ?: return fail("could not open the GATT server")
    if (mySession != session) return server.close()
    this.server = server
    rxUuid = rx
    txUuid = tx
    txChar = txCharacteristic
    if (!server.addService(service)) fail("could not add the GATT service")
  }

  // The scan response can only carry the adapter's own name, so the adapter is renamed while
  // pairing and restored afterwards.
  private fun setNameThenAdvertise(mySession: Int, spec: Advertisement) {
    val adapter = adapter ?: return
    if (adapter.name != spec.name) {
      if (originalName == null) originalName = adapter.name
      adapter.name = spec.name
    }
    awaitThen(mySession, { adapter.name == spec.name }, 3_000, null) {
      if (adapter.name != spec.name) Log.w(TAG, "adapter name is still ${adapter.name}")
      startAdvertising(mySession, spec)
    }
  }

  private fun startAdvertising(mySession: Int, spec: Advertisement) {
    val adapter = adapter ?: return fail("no Bluetooth adapter")
    val advertiser = adapter.bluetoothLeAdvertiser ?: return fail("no Bluetooth advertiser")
    val parameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
    val data =
        AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(spec.service))
            .addManufacturerData(spec.manufacturerId, spec.manufacturerData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
    val callback =
        object : AdvertisingSetCallback() {
          override fun onAdvertisingSetStarted(
              advertisingSet: AdvertisingSet?,
              txPower: Int,
              status: Int,
          ) {
            handler.post {
              if (mySession != session) return@post
              if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Log.i(TAG, "advertising as ${spec.name}")
                events.advertising(true)
              } else {
                advertisingCallback = null
                fail("advertising failed: ${GattPeripheral.advertiseError(status)}")
              }
            }
          }
        }
    advertisingCallback = callback
    runCatching { advertiser.startAdvertisingSet(parameters, data, scanResponse, null, null, callback) }
        .onFailure {
          advertisingCallback = null
          fail("advertising failed: ${it.message}")
        }
  }

  private fun respond(
      device: BluetoothDevice,
      requestId: Int,
      status: Int,
      offset: Int,
      value: ByteArray?,
  ) {
    runCatching { server?.sendResponse(device, requestId, status, offset, value) }
        .onFailure { Log.w(TAG, "sendResponse failed", it) }
  }

  private fun fail(error: String) {
    Log.w(TAG, error)
    teardown()
    events.advertising(false, error)
  }

  private fun teardown() {
    session++
    queue.clear()
    advertisingCallback?.let { cb ->
      runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
    }
    central?.let { device -> runCatching { server?.cancelConnection(device) } }
    server?.let { server -> runCatching { server.close() } }
    server = null
    advertisingCallback = null
    central = null
    rxUuid = null
    txUuid = null
    txChar = null
    txValue = ByteArray(0)
    cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
    mtu = 0
    prepared.reset()
    originalName?.let { name -> adapter?.name = name }
    originalName = null
  }

  private fun awaitThen(
      mySession: Int,
      ready: () -> Boolean,
      timeoutMs: Long,
      timeoutError: String?,
      then: () -> Unit,
  ) {
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

  @Suppress("DEPRECATION")
  private fun notifyNow(packet: ByteArray): Boolean {
    val server = server
    val device = central
    val characteristic = txChar
    if (server == null || device == null || characteristic == null) {
      Log.w(TAG, "notify with no central connected; dropping")
      return false
    }
    txValue = packet
    return runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The value travels with the call, and the result is a status code, not a boolean.
            server.notifyCharacteristicChanged(device, characteristic, false, packet) ==
                BluetoothGatt.GATT_SUCCESS
          } else {
            characteristic.value = packet
            server.notifyCharacteristicChanged(device, characteristic, false)
          }
        }
        .onFailure { Log.w(TAG, "notification of a ${packet.size} byte packet failed", it) }
        .getOrDefault(false)
  }

  companion object {
    private const val TAG = "MuseBle"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}
