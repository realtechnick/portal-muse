package dev.wobsoriano.museportal.ble.gatt

import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelUuid
import android.os.Parcelable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android 9's Bluetooth GATT service, reached by binding it directly.
 *
 * The first-gen Portal does not declare `android.hardware.bluetooth_le`, so the system never hands
 * GATT to apps: `openGattServer` returns null and advertising throws. The service itself runs and is
 * exported, so this binds it and speaks its binder interface, `IBluetoothGatt`, by hand. The
 * transaction codes are Android 9's (read from the Portal's own framework), so this is for API 28
 * only.
 */
class DirectGatt private constructor(
    private val binder: IBinder,
    private val context: Context,
    private val connection: ServiceConnection,
) {
  fun registerServer(appId: UUID, callback: IBinder) =
      call(REGISTER_SERVER) {
        writeTyped(ParcelUuid(appId))
        writeStrongBinder(callback)
      }

  fun unregisterServer(serverIf: Int) = call(UNREGISTER_SERVER) { writeInt(serverIf) }

  fun serverDisconnect(serverIf: Int, address: String) =
      call(SERVER_DISCONNECT) {
        writeInt(serverIf)
        writeString(address)
      }

  fun addService(serverIf: Int, service: BluetoothGattService) =
      call(ADD_SERVICE) {
        writeInt(serverIf)
        writeTyped(service)
      }

  fun sendResponse(serverIf: Int, address: String, requestId: Int, status: Int, offset: Int, value: ByteArray?) =
      call(SEND_RESPONSE) {
        writeInt(serverIf)
        writeString(address)
        writeInt(requestId)
        writeInt(status)
        writeInt(offset)
        writeByteArray(value)
      }

  fun sendNotification(serverIf: Int, address: String, handle: Int, confirm: Boolean, value: ByteArray) =
      call(SEND_NOTIFICATION) {
        writeInt(serverIf)
        writeString(address)
        writeInt(handle)
        writeInt(if (confirm) 1 else 0)
        writeByteArray(value)
      }

  fun startAdvertisingSet(
      parameters: AdvertisingSetParameters,
      data: AdvertiseData,
      scanResponse: AdvertiseData?,
      callback: IBinder,
  ) =
      call(START_ADVERTISING_SET) {
        writeTyped(parameters)
        writeTyped(data)
        writeTyped(scanResponse)
        writeTyped(null)
        writeTyped(null)
        writeInt(0)
        writeInt(0)
        writeStrongBinder(callback)
      }

  fun stopAdvertisingSet(callback: IBinder) = call(STOP_ADVERTISING_SET) { writeStrongBinder(callback) }

  fun close() {
    runCatching { context.unbindService(connection) }
  }

  private fun call(code: Int, args: Parcel.() -> Unit) {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
      data.writeInterfaceToken(GATT)
      data.args()
      binder.transact(code, data, reply, 0)
      reply.readException()
    } finally {
      data.recycle()
      reply.recycle()
    }
  }

  /** `IBluetoothGattServerCallback`. Calls arrive on binder threads. */
  interface ServerEvents {
    fun onServerRegistered(status: Int, serverIf: Int) {}

    fun onConnectionState(status: Int, serverIf: Int, connected: Boolean, address: String) {}

    fun onServiceAdded(status: Int, service: BluetoothGattService?) {}

    fun onReadRequest(address: String, requestId: Int, offset: Int, handle: Int) {}

    fun onWriteRequest(
        address: String,
        requestId: Int,
        offset: Int,
        prepared: Boolean,
        responseNeeded: Boolean,
        handle: Int,
        value: ByteArray,
    ) {}

    fun onExecuteWrite(address: String, requestId: Int, execute: Boolean) {}

    fun onNotificationSent(address: String, status: Int) {}

    fun onMtuChanged(address: String, mtu: Int) {}
  }

  /** `IAdvertisingSetCallback`. Calls arrive on binder threads. */
  interface AdvertisingEvents {
    fun onStarted(advertiserId: Int, txPower: Int, status: Int) {}

    fun onStopped(advertiserId: Int) {}
  }

  companion object {
    private const val GATT = "android.bluetooth.IBluetoothGatt"
    private const val SERVER_CALLBACK = "android.bluetooth.IBluetoothGattServerCallback"
    private const val ADVERTISING_CALLBACK = "android.bluetooth.le.IAdvertisingSetCallback"

    private const val START_ADVERTISING_SET = 9
    private const val STOP_ADVERTISING_SET = 10
    private const val REGISTER_SERVER = 42
    private const val UNREGISTER_SERVER = 43
    private const val SERVER_DISCONNECT = 45
    private const val ADD_SERVICE = 48
    private const val SEND_RESPONSE = 51
    private const val SEND_NOTIFICATION = 52

    /** Binds the GATT service and waits for it. Blocks, so never call it on the main thread. */
    fun connect(context: Context, timeoutMs: Long = 5_000): DirectGatt? {
      val app = context.applicationContext
      val bound = CountDownLatch(1)
      var service: IBinder? = null
      val connection =
          object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
              service = binder
              bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
          }
      val intent =
          Intent(GATT).setComponent(ComponentName("com.android.bluetooth", "com.android.bluetooth.gatt.GattService"))
      if (!app.bindService(intent, connection, 0)) {
        runCatching { app.unbindService(connection) }
        return null
      }
      bound.await(timeoutMs, TimeUnit.MILLISECONDS)
      val binder = service ?: return null.also { runCatching { app.unbindService(connection) } }
      return DirectGatt(binder, app, connection)
    }

    fun serverCallback(events: ServerEvents): IBinder =
        callback(SERVER_CALLBACK) { code, p ->
          when (code) {
            1 -> events.onServerRegistered(p.readInt(), p.readInt())
            2 -> events.onConnectionState(p.readInt(), p.readInt(), p.readInt() != 0, p.readString()!!)
            3 -> events.onServiceAdded(p.readInt(), p.readTyped(BluetoothGattService.CREATOR))
            4, 5 -> {
              val address = p.readString()!!
              val requestId = p.readInt()
              val offset = p.readInt()
              p.readInt()
              events.onReadRequest(address, requestId, offset, p.readInt())
            }
            6, 7 -> {
              val address = p.readString()!!
              val requestId = p.readInt()
              val offset = p.readInt()
              p.readInt()
              val prepared = p.readInt() != 0
              val responseNeeded = p.readInt() != 0
              val handle = p.readInt()
              val value = p.createByteArray() ?: ByteArray(0)
              events.onWriteRequest(address, requestId, offset, prepared, responseNeeded, handle, value)
            }
            8 -> events.onExecuteWrite(p.readString()!!, p.readInt(), p.readInt() != 0)
            9 -> events.onNotificationSent(p.readString()!!, p.readInt())
            10 -> events.onMtuChanged(p.readString()!!, p.readInt())
          }
        }

    fun advertisingCallback(events: AdvertisingEvents): IBinder =
        callback(ADVERTISING_CALLBACK) { code, p ->
          when (code) {
            1 -> events.onStarted(p.readInt(), p.readInt(), p.readInt())
            3 -> events.onStopped(p.readInt())
          }
        }

    private fun callback(descriptor: String, handle: (Int, Parcel) -> Unit): IBinder =
        object : Binder() {
          override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
              reply?.writeString(descriptor)
              return true
            }
            if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            handle(code, data)
            return true
          }
        }

    private fun Parcel.writeTyped(value: Parcelable?) {
      if (value == null) {
        writeInt(0)
      } else {
        writeInt(1)
        value.writeToParcel(this, 0)
      }
    }

    private fun <T> Parcel.readTyped(creator: Parcelable.Creator<T>): T? =
        if (readInt() != 0) creator.createFromParcel(this) else null
  }
}
