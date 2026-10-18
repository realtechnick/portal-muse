// This file is a modified port of linux/src/musegadget/ble_server.py and identity.py from Meta's Muse
// Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License,
// Version 2.0. Only the GATT shape is kept; the BlueZ server itself is not here.

/**
 * The device side of Muse gadget Bluetooth setup: community pairing version 5 and the conversation the
 * Muse app holds over two GATT characteristics until the device has its tokens.
 *
 * The phone writes commands to RX and reads answers as notifications on TX. A message longer than one
 * packet travels in chunks ([encodeChunks], [Assembler]). The phone reads get_device_info, opens an
 * encrypted session (pairing_client_hello, pairing_client_finished), asks for a Wi-Fi scan, and sends
 * provision_v2 with the device tokens. The [Controller] checks them, hands them to the caller to save,
 * and tells the phone auth_ok only once they are saved.
 *
 * Community pairing keeps setup secrets from someone listening. It does not prove who the phone is, so
 * it cannot stop someone in the middle; the device is only open to it while [Controller.run] is
 * running.
 *
 * A Controller is driven by a [Transport], which owns the GATT server. This package holds no Bluetooth
 * code and runs anywhere.
 */
package dev.wobsoriano.muse.pairing

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The setup service. The phone writes commands to RX and subscribes to TX for the answers. */
const val SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
const val RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"
const val TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"

/**
 * The advertisement carries the service UUID, the BLE name, and this manufacturer data, which the apps
 * read as the device's paired flag. 0xFFFF is the unassigned company id.
 */
const val MANUFACTURER_ID = 0xFFFF
const val MANUFACTURER_DATA: Byte = 0x00

/** The names the apps expect. They compare what follows BLE_NAME_PREFIX with what follows NODE_ID_PREFIX, so the BLE name has no separator. */
const val NODE_ID_PREFIX = "homelink-"
const val BLE_NAME_PREFIX = "MuseGadget"

/**
 * Stands in until the stack reports the negotiated MTU: phones running the Muse app negotiate at least
 * enough for full packets.
 */
const val ASSUMED_MTU = MAX_PACKET + 3

/** The pause the SDK's server leaves between the packets of one send. */
val CHUNK_STAGGER = 50.milliseconds

/** How long to keep the GATT server up after run returns, so the final auth_ok reaches the phone. */
val SHUTDOWN_GRACE = 1500.milliseconds

/** How long setup stays open. */
val DEFAULT_WINDOW = 10.minutes

/**
 * What the GATT server advertises: [service] in the advertisement with the manufacturer data, [name]
 * in the scan response. RX takes write and write-without-response, TX is read and notify.
 */
class Advertisement(
    val name: String,
    val service: String = SERVICE_UUID,
    val rx: String = RX_UUID,
    val tx: String = TX_UUID,
    val manufacturerId: Int = MANUFACTURER_ID,
    val manufacturerData: ByteArray = byteArrayOf(MANUFACTURER_DATA),
) {
  override fun equals(other: Any?) =
      other is Advertisement &&
          name == other.name &&
          service == other.service &&
          rx == other.rx &&
          tx == other.tx &&
          manufacturerId == other.manufacturerId &&
          manufacturerData.contentEquals(other.manufacturerData)

  override fun hashCode() = name.hashCode()

  override fun toString() = "Advertisement(name=$name, service=$service)"
}

/** The GATT link to the phone. Implementations: the app's GATT server, a fake in tests. */
interface Transport {
  /** Notifies each packet on the TX characteristic, in order. */
  fun send(packets: List<ByteArray>)

  /** The negotiated ATT MTU of the current connection. */
  fun mtu(): Int

  /** Drops the phone. */
  fun disconnect()
}
