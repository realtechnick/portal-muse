// This file is a modified port of linux/src/musegadget/ble_setup.py and the pair command of cli.py
// from Meta's Muse Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the
// Apache License, Version 2.0. The SDK's threads and locks became one coroutine that owns the state.

package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.RandomSource
import dev.wobsoriano.muse.debug
import dev.wobsoriano.muse.error
import dev.wobsoriano.muse.info
import dev.wobsoriano.muse.warn
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Who this device says it is. The SDK derives all of it from one random, locally administered MAC that it keeps across pairings. */
data class Device(
    /** homelink-xxxxxx */
    val nodeId: String,
    /** MuseGadgetXXXXXX, the same six digits; what the GATT server advertises. */
    val bleName: String,
    /** aa:bb:cc:dd:ee:ff, lower case; an identity, not a network interface's address. */
    val mac: String,
    /** Firmware version; goes into the handshake transcript. */
    val version: String,
) {
  val deviceId
    get() = DEVICE_ID_PREFIX + mac

  internal fun validate() {
    val suffix = nodeId.removePrefix(NODE_ID_PREFIX)
    require(nodeId.startsWith(NODE_ID_PREFIX) && suffix.isNotEmpty()) { "pairing: node id \"$nodeId\" does not start with \"$NODE_ID_PREFIX\"" }
    // The apps match the two by what follows the prefixes and show nothing when they differ.
    val want = BLE_NAME_PREFIX + suffix.uppercase()
    require(bleName == want) { "pairing: BLE name \"$bleName\" does not go with node id \"$nodeId\" (want \"$want\")" }
    require(MAC_PATTERN.matches(mac)) { "pairing: \"$mac\" is not a lower-case MAC" }
  }

  private companion object {
    val MAC_PATTERN = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
  }
}

/** What a Controller needs. */
class SetupConfig(
    val device: Device,
    val transport: Transport,
    /**
     * Persists the tokens; it throws when it cannot. The phone is told auth_ok only after it returns,
     * and error_storage when it throws. It runs on the loop and must only write local state.
     */
    val save: suspend (Result) -> Unit,
    /** The mgst_ token from gadgets.muse.ai, handed to the app once the session is confirmed. Muse stops pairing gadgets without one. */
    val sdkToken: String = "",
    /** Answers the Wi-Fi half of setup. */
    val network: Network = AlreadyOnline(),
    /**
     * Checks the tokens with Muse before they are saved; throwing tells the phone auth_failed. It runs
     * off the loop and is canceled when setup ends. Null is [TokenCheck]'s verify.
     */
    val verify: (suspend (Result) -> Unit)? = null,
    /** Hears each change of state. It runs on the loop and must not block. */
    val progress: ((Progress) -> Unit)? = null,
    /** How long setup stays open. */
    val window: Duration = DEFAULT_WINDOW,
    /** Supplies the device's key and nonce. */
    val rand: RandomSource = RandomSource.Secure,
    val logger: Logger = Logger.None,
)

/** What run throws when nobody finished setup within the window. */
class WindowClosedException : Exception("pairing: setup window closed without pairing")

// How long the phone is given to read a last status before it is dropped.
private val DISCONNECT_AFTER_ERROR = 300.milliseconds
private val DISCONNECT_AFTER_FAILURE = 500.milliseconds

/** Bounds the writes waiting for the loop: two of the largest messages at the smallest MTU. */
private const val MAX_PENDING = 1024

private sealed interface Event {
  class Wrote(val packet: ByteArray) : Event

  data object Disconnected : Event

  // Scanned, Joined and Checked carry the generation their work began under.
  class Scanned(val gen: Long, val networks: List<WiFiNetwork>) : Event

  class Joined(val gen: Long, val error: Throwable?) : Event

  class Checked(val gen: Long, val result: Result, val error: Throwable?) : Event

  class HangUp(val link: Long) : Event
}

/**
 * Holds the setup conversation with one phone at a time.
 *
 * [run] owns all of its state on the coroutine that calls it. [onWrite] and [onDisconnect] only hand
 * an event to that coroutine, so a Transport may call them from any thread, before or after run. They
 * must be called in the order things happened on the link, and not from inside send or disconnect.
 * The Transport's methods are only ever called from run's coroutine.
 */
class Controller(config: SetupConfig) {
  private val cfg: SetupConfig
  private val log = config.logger
  private val inbox = Channel<Event>(MAX_PENDING)
  private val started = AtomicBoolean()

  // Everything below belongs to run's coroutine.
  private val sess: PairingSession
  private val asm = Assembler()
  /** Counts the connections seen, so a disconnect scheduled for one phone cannot drop the next. */
  private var link = 0L
  /**
   * Set once a handshake has begun on this connection: from then on a plaintext command other than a
   * new hello or get_device_info is ignored without an answer.
   */
  private var plaintextBlocked = false
  /** Set while a join and token check are in flight. Only one runs at a time, even across sessions. */
  private var provisioning = false
  private var progress = Progress(SetupState.Waiting)
  private lateinit var jobs: CoroutineScope
  private val verify: suspend (Result) -> Unit

  init {
    config.device.validate()
    // An empty version would leave a transcript line empty, which the protocol does not allow.
    val device = if (config.device.version.isEmpty()) config.device.copy(version = "unknown") else config.device
    cfg =
        SetupConfig(device, config.transport, config.save, config.sdkToken, config.network, config.verify, config.progress, config.window.takeIf { it > Duration.ZERO } ?: DEFAULT_WINDOW, config.rand, config.logger)
    val ua = "echod/${device.version} (${System.getProperty("os.name").orEmpty().lowercase()} ${System.getProperty("os.arch").orEmpty()})"
    verify = config.verify ?: TokenCheck(userAgent = ua)::verify
    sess = PairingSession(device, config.sdkToken, config.rand)
  }

  /** Takes one write to the RX characteristic. It copies [data]. */
  fun onWrite(data: ByteArray) = post(Event.Wrote(data.copyOf()))

  /** Tells the Controller the phone is gone. */
  fun onDisconnect() = post(Event.Disconnected)

  /** Blocks only when MAX_PENDING events are waiting, and gives up once run has returned. */
  private fun post(ev: Event) {
    inbox.trySendBlocking(ev)
  }

  private suspend fun postFromJob(ev: Event) {
    try {
      inbox.send(ev)
    } catch (_: ClosedSendChannelException) {}
  }

  /**
   * Holds setup open until a phone finishes it, the window closes, or the coroutine is canceled. It
   * returns once the tokens are saved and the phone was told; the caller should keep the Transport up
   * for [SHUTDOWN_GRACE] after that. It throws [WindowClosedException] when the window closed. A
   * Controller runs once.
   */
  suspend fun run() {
    check(started.compareAndSet(false, true)) { "pairing: a Controller runs once" }
    coroutineScope {
      jobs = this
      val deadline = System.nanoTime() + cfg.window.inWholeNanoseconds
      try {
        report()
        while (true) {
          val left = (deadline - System.nanoTime()).coerceAtLeast(0)
          val ev = withTimeoutOrNull(left / 1_000_000 + 1) { inbox.receive() }
          if (ev == null) {
            val err = WindowClosedException()
            sess.reset()
            advance(Trigger.WindowClosed, Reason.WindowClosed, err)
            throw err
          }
          if (handleEvent(ev)) return@coroutineScope
        }
      } catch (e: CancellationException) {
        sess.reset()
        advance(Trigger.WindowClosed, Reason.Canceled, e)
        throw e
      } finally {
        inbox.close()
        coroutineContext.cancelChildren()
      }
    }
  }

  /** Reports whether setup is finished. */
  private suspend fun handleEvent(ev: Event): Boolean {
    when (ev) {
      is Event.Wrote -> {
        advance(Trigger.PhoneWrote)
        asm.feed(ev.packet)?.let { handleMessage(it, false) }
      }
      Event.Disconnected -> {
        log.info("muse pairing: phone disconnected")
        asm.reset()
        plaintextBlocked = false
        sess.reset()
        link++
        advance(Trigger.PhoneLeft)
      }
      is Event.Scanned -> sendEncrypted(scanResult(ev.networks), ev.gen)
      is Event.Joined -> onJoined(ev)
      is Event.Checked -> return onChecked(ev)
      is Event.HangUp ->
          if (ev.link == link) {
            try {
              cfg.transport.disconnect()
            } catch (e: Exception) {
              log.warn("muse pairing: could not drop the phone", "err" to e.message)
            }
          }
    }
    return false
  }

  private suspend fun handleMessage(raw: ByteArray, decrypted: Boolean) {
    val f = Fields.parse(raw)
    if (f == null) {
      log.warn("muse pairing: command is not a JSON object", "bytes" to raw.size)
      sendStatus(Status.InvalidCommand, 0)
      return
    }
    val action = f.text("action")
    log.debug("muse pairing: command", "action" to action, "encrypted" to decrypted)

    when {
      !decrypted && action == ACTION_HELLO -> onHello(f)
      !decrypted && action == ACTION_ENCRYPTED -> onRecord(f)
      // Public, so answered in plaintext at any point. Android reads it again when it restarts a
      // handshake on the same connection.
      action == ACTION_DEVICE_INFO -> send(deviceInfo(cfg.device, cfg.network.online()))
      !decrypted && plaintextBlocked -> log.warn("muse pairing: plaintext command ignored after the handshake began", "action" to action)
      !decrypted && action in sensitiveActions -> sendStatus(Status.EncryptionRequired, 0)
      decrypted && action == ACTION_CLIENT_FINISHED -> onClientFinished(f)
      decrypted && action in sensitiveActions && !sess.confirmed() -> sendStatus(Status.ConfirmRequired, 0)
      decrypted && action == ACTION_WIFI_SCAN -> onWiFiScan()
      decrypted && action == ACTION_PROVISION -> onProvision(f)
      else -> sendStatus(Status.UnknownAction, 0)
    }
  }

  private fun onHello(f: Fields) {
    val h =
        try {
          parseHello(f)
        } catch (e: HelloKeyException) {
          sess.reset()
          advance(Trigger.AttemptFailed, Reason.Handshake)
          sendStatus(Status.InvalidHello, 0)
          return
        } catch (e: HelloOfferException) {
          sendStatus(Status.InvalidHello, 0)
          return
        }
    val ready =
        try {
          sess.hello(h)
        } catch (e: Exception) {
          log.error("muse pairing: could not start a session", "err" to e.message)
          advance(Trigger.AttemptFailed, Reason.Handshake, e)
          sendStatus(Status.Unavailable, 0)
          return
        }
    plaintextBlocked = true
    advance(Trigger.HandshakeBegan)
    send(ready.toJson())
  }

  private suspend fun onRecord(f: Fields) {
    val r = parseRecord(f)
    val plaintext =
        if (r != null) {
          sess.open(r)
        } else {
          sess.reset()
          null
        }
    if (plaintext == null) return dropSession()
    handleMessage(plaintext, true)
  }

  private fun onClientFinished(f: Fields) {
    val gen = sess.clientFinished(parseClientFinished(f))
    if (gen == 0L) return dropSession()
    log.info("muse pairing: session confirmed by the app")
    advance(Trigger.PhoneConfirmed)
    sendStatus(Status.Confirmed, gen)
  }

  /**
   * Follows a record that did not open or did not belong. The session is already gone, so the status
   * only reaches a phone that never began a handshake; the others just see the link drop.
   */
  private fun dropSession() {
    sendStatus(Status.Decrypt, 0)
    hangUpAfter(DISCONNECT_AFTER_ERROR)
    advance(Trigger.AttemptFailed, Reason.Handshake)
  }

  private fun onWiFiScan() {
    val gen = sess.gen
    jobs.launch {
      val networks =
          try {
            cfg.network.scan()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.warn("muse pairing: Wi-Fi scan failed", "err" to e.message)
            emptyList()
          }
      postFromJob(Event.Scanned(gen, networks))
    }
  }

  private fun onProvision(f: Fields) {
    val p = parseProvision(f) ?: return sendStatus(Status.MissingCredentials, 0)
    if (provisioning) return sendStatus(Status.InProgress, 0)
    val gen = sess.markProvisioning()
    if (gen == 0L) return sendStatus(Status.ConfirmRequired, 0)
    provisioning = true
    advance(Trigger.ProvisionBegan)
    sendStatus(Status.WiFiConnecting, gen)
    jobs.launch {
      val joined = attempt { cfg.network.join(p.ssid, p.password) }
      postFromJob(Event.Joined(gen, joined))
      if (joined != null) return@launch
      postFromJob(Event.Checked(gen, p.result, attempt { verify(p.result) }))
    }
  }

  private suspend fun attempt(block: suspend () -> Unit): Throwable? =
      try {
        block()
        null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        e
      }

  private fun onJoined(ev: Event.Joined) {
    if (ev.error == null) return sendStatus(Status.WiFiConnected, ev.gen)
    provisioning = false
    log.warn("muse pairing: not online", "err" to ev.error.message)
    // The session stays in provisioning so the app can retry, as the firmware does.
    if (sess.extendProvisioning(ev.gen)) {
      sendStatus(Status.WiFiFailed, ev.gen)
      advance(Trigger.AttemptFailed, Reason.Offline, ev.error)
    }
  }

  /** Reports whether setup is finished. */
  private suspend fun onChecked(ev: Event.Checked): Boolean {
    provisioning = false
    if (!sess.provisioningValid(ev.gen)) {
      log.info("muse pairing: token check finished for a session that is gone")
      return false
    }
    if (ev.error != null) {
      log.warn("muse pairing: device token check failed", "err" to ev.error.message)
      failAttempt(Status.AuthFailed, Reason.AuthRejected, ev.gen, ev.error)
      return false
    }
    try {
      cfg.save(ev.result)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("muse pairing: could not save the pairing", "err" to e.message)
      failAttempt(Status.Storage, Reason.Storage, ev.gen, e)
      return false
    }
    sendStatus(Status.AuthOk, ev.gen)
    log.info("muse pairing: setup complete")
    advance(Trigger.Paired)
    return true
  }

  private fun failAttempt(st: Status, reason: Reason, gen: Long, err: Throwable) {
    sendStatus(st, gen)
    hangUpAfter(DISCONNECT_AFTER_FAILURE)
    advance(Trigger.AttemptFailed, reason, err)
  }

  private fun hangUpAfter(d: Duration) {
    val link = link
    jobs.launch {
      delay(d)
      postFromJob(Event.HangUp(link))
    }
  }

  /** Moves the state along [transitions] and tells the progress callback. */
  private fun advance(t: Trigger, reason: Reason = Reason.None, err: Throwable? = null) {
    val next = transitions.getValue(progress.state)[t] ?: return
    progress = if (next == SetupState.Failed) Progress(next, reason, err) else Progress(next)
    report()
  }

  private fun report() {
    cfg.progress?.invoke(progress)
  }

  /**
   * Tells the phone [st], encrypted whenever a session is open. A nonzero [gen] ties it to the work it
   * reports on, and it is dropped once that work is stale.
   */
  private fun sendStatus(st: Status, gen: Long) {
    val env = sess.sealStatus(st, gen)
    if (env != null) {
      send(env.toJson())
      log.debug("muse pairing: status", "status" to st.wire, "counter" to env.counter)
      return
    }
    if (gen != 0L || plaintextBlocked || st !in plaintextStatuses) {
      log.debug("muse pairing: status not sent", "status" to st.wire)
      return
    }
    // Bare and unframed, as the firmware sends it.
    transmit(listOf(st.wire.toByteArray()))
  }

  private fun sendEncrypted(v: JsonObject, gen: Long) {
    sess.seal(v.toString().toByteArray(), gen)?.let { send(it.toJson()) }
  }

  private fun send(v: JsonObject) {
    val packets =
        try {
          encodeChunks(v.toString().toByteArray(), cfg.transport.mtu())
        } catch (e: FramingException) {
          log.error("muse pairing: message too long to send", "err" to e.message)
          return
        }
    transmit(packets)
  }

  private fun transmit(packets: List<ByteArray>) {
    try {
      cfg.transport.send(packets)
    } catch (e: Exception) {
      log.warn("muse pairing: could not notify the phone", "err" to e.message)
    }
  }
}
