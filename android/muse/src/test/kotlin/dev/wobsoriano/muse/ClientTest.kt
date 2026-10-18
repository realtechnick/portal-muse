package dev.wobsoriano.muse

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val testIdentity = Identity("02:11:22:a1:b2:c3")

class Harness(tune: (Harness, State, ConfigBuilder) -> State = { _, st, _ -> st }) {
  val fake = FakeMuse()
  val store = MemStore(fake)
  val states = LinkedBlockingQueue<ConnState>()
  val logs = StringBuffer()
  /** Completes when run returns, which it does only when the device is unpaired. */
  val done = CompletableDeferred<Unit>()
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val client: Client
  val job: Job

  init {
    var st =
        State(
            identity = testIdentity,
            credentials =
                Credentials(
                    accessToken = "access-0",
                    refreshToken = "hatch_refresh:refresh-0",
                    tokenType = "device",
                    username = "someone",
                    apiUrlV2 = fake.url,
                    noiseHost = fake.url.removePrefix("https://"),
                    accessTokenSavedAt = System.currentTimeMillis() / 1000))
    val cfg =
        ConfigBuilder(
            store = store,
            displayName = "Kitchen",
            version = "1.2.3",
            commands = mutableListOf(echoCommand()),
            onState = { states.add(it) },
            logger = Logger { level, message -> logs.append("$level $message\n") },
            http = fake.http())
    st = tune(this, st, cfg)
    store.state = st
    client = Client(cfg.build())
    client.t =
        client.t.copy(
            settle = 40.milliseconds,
            final = 150.milliseconds,
            backoffBase = 10.milliseconds,
            backoffMax = 40.milliseconds,
            authFloor = 20.milliseconds,
            tokenRetry = 40.milliseconds)
    job =
        scope.launch {
          client.run()
          done.complete(Unit)
        }
  }

  fun close() {
    runBlocking { withTimeout(5.seconds) { job.cancel(); job.join() } }
    scope.cancel()
    fake.close()
    assertTrue("the fake VM failed: ${fake.failures}", fake.failures.isEmpty())
  }

  /** Waits for a state in the given phase and returns it, with every state seen on the way. */
  fun until(phase: Phase): Pair<ConnState, List<ConnState>> {
    val seen = mutableListOf<ConnState>()
    while (true) {
      val s = states.poll(5, TimeUnit.SECONDS) ?: fail("never reached $phase; saw $seen; events ${fake.log()}").let { error("") }
      seen.add(s)
      if (s.phase == phase) return s to seen
    }
  }

  fun vm(): FakeVm = fake.vms.poll(5, TimeUnit.SECONDS) ?: error("no VM connection; events ${fake.log()}")

  fun awaitDone() = runBlocking { withTimeout(5.seconds) { done.await() } }

  /** Fails if anything that must stay out of the log is in it. */
  fun noSecrets(vararg secrets: String) {
    val logged = logs.toString()
    for (secret in secrets.toList() + listOf("access-", "refresh-", "vmtok")) {
      assertFalse("the log contains $secret:\n$logged", logged.contains(secret))
    }
  }
}

fun echoCommand() =
    Command(
        name = "echo",
        description = "Say it back.",
        required = listOf(Param("text", ParamType.String, "What to say.")),
        optional = listOf(Param("times", ParamType.Integer, "How often.")),
        timeout = 5.seconds,
        handler = { args, _ -> buildJsonObject { put("text", rawString(stateJson.parseToJsonElement(args).jsonObject["text"])) } })

class ClientTest {
  private val harnesses = mutableListOf<Harness>()

  private fun start(tune: (Harness, State, ConfigBuilder) -> State = { _, st, _ -> st }) = Harness(tune).also { harnesses += it }

  /** Starts a client and waits for it to register. */
  private fun online(tune: (Harness, State, ConfigBuilder) -> State = { _, st, _ -> st }): Pair<Harness, FakeVm> {
    val h = start(tune)
    h.until(Phase.Online)
    return h to h.vm()
  }

  @After
  fun stop() {
    for (h in harnesses) h.close()
  }

  private fun json(text: String): JsonElement = stateJson.parseToJsonElement(text)

  @Test
  fun sessionRegisters() {
    val (h, vm) =
        online { _, st, cfg ->
          cfg.commands += Command(name = "device.health", description = "Report status.", handler = { _, _ -> null })
          st
        }
    assertEquals("vm_id=vm%201", vm.query)
    val open = checkNotNull(vm.controlOpen)
    assertTrue("control stream opened as $open", open.verb == "POST" && open.path == "/link-control" && !open.endBody && open.data.isEmpty())
    val register = vm.registers.receive("link.register")
    assertTrue("register = $register", rawString(register["type"]) == "req" && rawString(register["method"]) == "link.register" && rawString(register["id"]) != "")
    // The shape of DeviceDescription.register_params and COMMAND_SPECS in the SDK.
    val want =
        json(
            """{
		"node_id": "homelink-a1b2c3",
		"display_name": "Kitchen",
		"platform": "linux",
		"version": "1.2.3",
		"device_family": "homehub",
		"model_id": "linux",
		"is_wakeup_supported": false,
		"commands_v2": {
			"echo": {
				"description": "Say it back.",
				"required": {"text": {"type": "string", "description": "What to say."}},
				"optional": {"times": {"type": "integer", "description": "How often."}},
				"timeout_ms": 5000
			},
			"device.health": {"description": "Report status.", "required": {}, "optional": {}}
		}
	}""")
    assertEquals(want, register["params"])
    assertEquals(ConnState(Phase.Online), h.client.connState)
    assertEquals(listOf("fetch Bearer access-0", "upgrade Bearer vmtok-0 -> 0"), h.fake.log())
    h.noSecrets()
  }

  @Test
  fun newRefusesBadCommands() {
    val handler = Handler { _, _ -> null }
    for ((name, commands) in
        mapOf(
            "ota" to listOf(Command(name = "device.ota", handler = handler)),
            "twice" to listOf(Command(name = "a", handler = handler), Command(name = "a", handler = handler)),
            "unhandled" to listOf(Command(name = "a", handler = null)),
            "param" to
                listOf(
                    Command(
                        name = "a",
                        handler = handler,
                        required = listOf(Param("p", ParamType.String)),
                        optional = listOf(Param("p", ParamType.String)))),
        )) {
      assertFails("$name: New accepted it") { Client(Config(store = MemStore(null), commands = commands)) }
    }
  }

  @Test
  fun invokeAnswered() {
    val (_, vm) =
        online { _, st, cfg ->
          cfg.commands += Command(name = "fail", handler = { _, _ -> throw CommandFailed("it broke") })
          cfg.commands += Command(name = "panic", handler = { _, _ -> error("boom") })
          cfg.commands += Command(name = "deadline", handler = { _, timeout -> buildJsonObject { put("soon", timeout < 1.seconds) } })
          st
        }
    vm.registers.receive("link.register")

    // 120 KB, so the invoke and its result each span two Noise chunks.
    val big = "badge ".repeat(20000)
    for ((invoke, want) in
        listOf(
            """{"id":"inv-1","command":"echo","params":{"text":"hello"},"timeout_ms":5000}""" to
                """{"method":"link.result","id":"inv-1","ok":true,"payload":{"text":"hello"}}""",
            """{"id":"inv-2","command":"echo","params":{"text":"$big"}}""" to """{"method":"link.result","id":"inv-2","ok":true,"payload":{"text":"$big"}}""",
            """{"id":"inv-3","command":"fail"}""" to """{"method":"link.result","id":"inv-3","ok":false,"error":"it broke"}""",
            """{"id":"inv-4","command":"nope"}""" to """{"method":"link.result","id":"inv-4","ok":false,"error":"unsupported command: nope"}""",
            """{"id":"inv-5","command":"panic"}""" to """{"method":"link.result","id":"inv-5","ok":false,"error":"panic: boom"}""",
            """{"id":"inv-6","command":"deadline","timeout_ms":200}""" to """{"method":"link.result","id":"inv-6","ok":true,"payload":{"soon":true}}""",
        )) {
      val m = json(invoke).jsonObject
      vm.message(JsonObject(m + ("method" to JsonPrimitive("link.invoke"))))
      val got = vm.results.receive("link.result")
      if (got != json(want)) {
        if (invoke.length > 200) fail("${m["id"]}: the large result did not match") else fail("${m["id"]}: result = $got, want $want")
      }
    }
  }

  private class Collect {
    private val events = mutableListOf<ReplyEvent>()

    val on: suspend (ReplyEvent) -> Unit = { synchronized(events) { events.add(it) } }

    fun events() = synchronized(events) { events.toList() }
  }

  @Test
  fun askText() {
    val (h, vm) = online()
    val c = Collect()
    val reply = runBlocking { h.client.ask(AskInput.Text("hello"), c.on) }
    assertEquals(Reply("you said: hello"), reply)
    // The earlier message on the stream is not part of the answer, and a typed message has no
    // transcript to report. The fake sends the whole answer ahead of the acknowledgment, as the real
    // service can, so it is all there by the time the device knows which message was its own.
    assertEquals(listOf(ReplyEvent.Text("you said: hello"), ReplyEvent.Settled("you said: hello")), c.events())

    val seen = vm.chats.receive("the chat")
    assertEquals(json("""{"message":"hello","output_modality":"text","device_id":"homelink-a1b2c3"}"""), seen.body)
    assertTrue(seen.inRequest)
    assertTrue("chat headers = ${seen.headers}", seen.headers["x-app-id"] == "musegadget" && seen.headers["Content-Type"] == "application/json" && !seen.headers["x-request-id"].isNullOrEmpty())

    // The second question's answer is its own, and the chat stream is opened once for the session.
    assertEquals("you said: again", runBlocking { h.client.ask(AskInput.Text("again")) }.text)
    assertEquals(1, h.fake.subscribes)
    assertFalse("a message body reached the log:\n${h.logs}", h.logs.contains("hello"))
  }

  @Test
  fun replyStreams() {
    val (h, _) =
        online { h, st, _ ->
          h.fake.hold = CountDownLatch(1)
          st
        }
    val c = Collect()
    val reply =
        runBlocking {
          h.client.ask(AskInput.Text("hello")) { e ->
            c.on(e)
            h.fake.hold?.countDown()
          }
        }
    assertEquals("you said: hello", reply.text)
    // The first half is shown while Muse is still working, and is not mistaken for the answer.
    assertEquals(
        listOf(ReplyEvent.Text("you sai"), ReplyEvent.Text("you said: hello"), ReplyEvent.Settled("you said: hello")), c.events())
  }

  private fun testWav(size: Int): ByteArray {
    val wav = ByteArray(size) { i -> (i * 7).toByte() }
    "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1).copyInto(wav)
    return wav
  }

  @Test
  fun askVoiceNote() {
    val (h, vm) = online()
    val wav = testWav(40_000)
    val c = Collect()
    val reply = runBlocking { h.client.ask(AskInput.VoiceNote(wav), c.on) }
    assertEquals(Reply("you said: a voice note", heard = "a voice note"), reply)
    val got = c.events()
    assertEquals(ReplyEvent.Heard("a voice note"), got.first())
    assertEquals(ReplyEvent.Settled("you said: a voice note"), got.last())

    val seen = vm.chats.receive("the chat")
    assertEquals("the recording did not arrive intact", MessageDigest.getInstance("SHA-256").digest(wav).hex(), seen.wavSha)
    // As the firmware sends it, the recording as one attachment and the body in 16 KB pieces after a
    // request frame that carries none of it, and with the device named, as a typed message is.
    assertEquals(
        json(
            """{"message":"","output_modality":"text","device_id":"homelink-a1b2c3","items":[{"type":"file","mime_type":"audio/wav","filename":"voice_note.wav"}]}"""),
        seen.body)
    val bodyLen =
        """{"message":"","output_modality":"text","device_id":"homelink-a1b2c3","items":[{"type":"file","mime_type":"audio/wav","filename":"voice_note.wav","data_base64":""}]}"""
            .length + (wav.size + 2) / 3 * 4
    val wantChunks = (bodyLen + BODY_CHUNK - 1) / BODY_CHUNK
    assertTrue("sent in ${seen.chunks} body chunks (whole in the request: ${seen.inRequest}), want $wantChunks", !seen.inRequest && seen.chunks == wantChunks)
  }

  // A command for a turn the device began comes as an event on the chat stream, not on the control
  // stream, and is answered the way the other kind is: run, and a link.result with the event's id.
  @Test
  fun aCommandOnTheChatStreamIsRunAndAnswered() {
    val ran = LinkedBlockingQueue<String>()
    val (h, vm) =
        online { _, st, cfg ->
          cfg.commands +=
              Command(
                  name = "volume",
                  handler = { args, _ ->
                    ran.add(args)
                    buildJsonObject { put("result", "volume is 10 of 30") }
                  })
          st
        }
    // The chat stream opens with the first question.
    runBlocking { h.client.ask(AskInput.Text("hello")) }
    vm.streamBytes(
        ("""{"type":"event","event":"client.invoke","ts_ms":1,"payload":{"command_id":"volume",""" +
                """"invoke_id":"inv-chat","params_json":"{\"level\":10}","timeout_ms":30000}}""" + "\n")
            .toByteArray())
    assertEquals("""{"level":10}""", ran.receive("the command"))
    assertEquals(json("""{"method":"link.result","id":"inv-chat","ok":true,"payload":{"result":"volume is 10 of 30"}}"""), vm.results.receive("link.result"))
  }

  @Test
  fun askRefusesBadInput() {
    val (h, vm) = online()
    for ((name, input) in
        mapOf(
            "blank" to AskInput.Text("  "),
            "long" to AskInput.Text("a".repeat(MAX_ASK_TEXT + 1)),
            "not a wav" to AskInput.VoiceNote("definitely not audio".toByteArray()),
            "huge" to AskInput.VoiceNote(testWav(MAX_VOICE_NOTE + 1)),
        )) {
      assertFails("$name: Ask took it") { runBlocking { h.client.ask(input) } }
    }
    assertNull("something was sent", vm.chats.poll())
  }

  @Test
  fun askWhileOffline() {
    val client = Client(Config(store = MemStore(null)))
    val err = runCatching { runBlocking { client.ask(AskInput.Text("hello")) } }.exceptionOrNull()
    assertTrue("err = $err", err is OfflineException)
  }

  @Test
  fun tokenRotationIsSavedBeforeUse() {
    val (h, _) =
        online { _, st, cfg ->
          cfg.sdkToken = "sdk-secret"
          st.copy(credentials = st.credentials!!.copy(accessTokenSavedAt = System.currentTimeMillis() / 1000 - 4 * 3600))
        }
    assertEquals(
        listOf("refresh Bearer hatch_refresh:refresh-0", "save access-1", "fetch Bearer access-1", "upgrade Bearer vmtok-0 -> 0"),
        h.fake.log())
    val body = h.fake.refreshBodies[0]
    assertTrue("refresh body = $body", rawString(body["device_id"]) == "homelink-a1b2c3" && rawString(body["sdk_token"]) == "sdk-secret")
    val saved = h.store.state
    val creds = saved.credentials!!
    assertTrue(
        "saved credentials = $creds",
        creds.accessToken == "access-1" && creds.refreshToken == "refresh-1" && System.currentTimeMillis() / 1000 - creds.accessTokenSavedAt < 60)
    assertTrue("the rest of the state was not kept: $saved", creds.username == "someone" && creds.tokenType == "device" && saved.identity == testIdentity)
    assertTrue("the SDK token was sent but not marked as reported", saved.sdkTokenReported)
    h.noSecrets("sdk-secret")
  }

  @Test
  fun sdkTokenIsReportedOnce() {
    var (h, _) =
        online { _, st, cfg ->
          cfg.sdkToken = "sdk-secret"
          st
        }
    var events = h.fake.log()
    assertTrue("a fresh token was not rotated to report the SDK token: $events", events[0] == "refresh Bearer hatch_refresh:refresh-0" && events[1] == "save access-1")

    h =
        online { _, st, cfg ->
              cfg.sdkToken = "sdk-secret"
              st.copy(sdkTokenReported = true)
            }
            .first
    events = h.fake.log()
    assertEquals("a reported SDK token was reported again: $events", "fetch Bearer access-0", events[0])
  }

  @Test
  fun saveFailureHoldsTheRotatedTokens() {
    val h =
        start { h, st, _ ->
          h.store.failSaves = 3
          st.copy(credentials = st.credentials!!.copy(accessTokenSavedAt = 0))
        }
    val (offline, _) = h.until(Phase.Offline)
    assertTrue("offline for ${offline.error}", offline.error?.message?.contains("could not save the rotated tokens") == true)
    // The pair in the store is dead on the server, but it is all the store has, so it stays.
    assertEquals("access-0", h.store.state.credentials!!.accessToken)
    h.until(Phase.Online)
    // One refresh only: the dead refresh token is never presented again, and the new access token
    // goes nowhere until the save that follows the three failures.
    assertEquals(
        listOf(
            "refresh Bearer hatch_refresh:refresh-0",
            "save failed access-1",
            "save failed access-1",
            "save failed access-1",
            "save access-1",
            "fetch Bearer access-1",
            "upgrade Bearer vmtok-0 -> 0"),
        h.fake.log())
    val got = h.store.state.credentials!!
    assertTrue("saved credentials = $got", got.accessToken == "access-1" && got.refreshToken == "refresh-1")
  }

  @Test
  fun rejectedTokenIsRefreshed() {
    val (h, _) =
        online { h, st, _ ->
          // The server has moved on from the token the device holds, though the refresh token is
          // still good.
          h.fake.access = "access-elsewhere"
          st
        }
    assertEquals(
        listOf("fetch Bearer access-0", "refresh Bearer hatch_refresh:refresh-0", "save access-1", "fetch Bearer access-1", "upgrade Bearer vmtok-0 -> 0"),
        h.fake.log())
  }

  @Test
  fun revokedPairingUnpairs() {
    val h =
        start { h, st, _ ->
          h.fake.refresh = "refresh-elsewhere"
          st.copy(credentials = st.credentials!!.copy(accessTokenSavedAt = 0))
        }
    h.until(Phase.Unpaired)
    h.awaitDone()
    val saved = h.store.state
    assertTrue("state after revocation = $saved", saved.credentials == null && saved.identity == testIdentity)
  }

  // A VM that is not behind its front door yet is asked again with the same bearer, as often as the
  // API said, and the device is never reported offline for it: asked once and then left for a minute,
  // the real one stayed shut for half an hour.
  @Test
  fun aColdVmIsAskedAgainUntilItOpens() {
    val h =
        start { h, st, _ ->
          h.fake.coldUpgrades = 5
          h.fake.knockEveryMs = 100
          st
        }
    val (_, seen) = h.until(Phase.Online)
    for (s in seen) assertTrue("reported offline before the VM opened: ${s.error}", s.phase != Phase.Offline)
    assertEquals(listOf("fetch Bearer access-0") + List(5) { "upgrade Bearer vmtok-0 -> 403" } + "upgrade Bearer vmtok-0 -> 0", h.fake.log())
  }

  // A door that stays shut through a spell of asking is not asked that way again for a while: the
  // rounds after it try once each, at the backoff's pace, however often the API says to ask.
  @Test
  fun aDoorThatStaysShutIsNotAskedAgainEveryRound() {
    val h =
        start { h, st, _ ->
          h.fake.coldUpgrades = 6
          h.fake.knockEveryMs = 100
          h.fake.knockTries = 3
          st
        }
    h.until(Phase.Online)
    val want = mutableListOf("fetch Bearer access-0")
    repeat(4) { want += "upgrade Bearer vmtok-0 -> 403" } // the first try and the three more the API allowed
    repeat(2) { want += listOf("fetch Bearer access-0", "upgrade Bearer vmtok-0 -> 403") } // then one try a round
    want += listOf("fetch Bearer access-0", "upgrade Bearer vmtok-0 -> 0")
    assertEquals(want, h.fake.log())
  }

  @Test
  fun upgradeRefusalFetchesFreshCredentials() {
    for (status in listOf(401, 403)) {
      val h =
          start { h, st, _ ->
            h.fake.rejectUpgrades = 1
            h.fake.rejectStatus = status
            st
          }
      val (offline, _) = h.until(Phase.Offline)
      assertTrue("$status: offline for ${offline.error}", offline.error?.message?.contains("refused the connection") == true)
      h.until(Phase.Online)
      assertEquals(
          listOf("fetch Bearer access-0", "upgrade Bearer vmtok-0 -> $status", "fetch Bearer access-0", "upgrade Bearer vmtok-0+ -> 0"),
          h.fake.log())
    }
  }

  @Test
  fun reconnectsAfterTheServerDrops() {
    val (h, vm) = online()
    vm.registers.receive("link.register")
    vm.drop()

    val (_, seen) = h.until(Phase.Online)
    assertTrue(
        "states after the drop = $seen", seen.size == 3 && seen[0].phase == Phase.Offline && seen[0].error != null && seen[1].phase == Phase.Connecting)
    val again = h.vm()
    again.registers.receive("the second link.register")

    // The new session is a whole one: it carries an ask.
    assertEquals("you said: back", runBlocking { h.client.ask(AskInput.Text("back")) }.text)
  }

  @Test
  fun dropMidAskFailsTheAsk() {
    val (h, vm) =
        online { h, st, _ ->
          h.fake.hold = CountDownLatch(1)
          st
        }
    try {
      val err =
          runCatching { runBlocking { h.client.ask(AskInput.Text("hello")) { e -> if (e is ReplyEvent.Text) vm.drop() } } }.exceptionOrNull()
      assertTrue("err = $err", err is OfflineException)
    } finally {
      h.fake.hold?.countDown()
    }
  }

  @Test
  fun unpairedByMuse() {
    val (h, vm) = online()
    vm.registers.receive("link.register")
    vm.message(buildJsonObject {
      put("type", "evt")
      put("event", "link.unpaired")
    })

    h.until(Phase.Unpaired)
    h.awaitDone()
    val saved = h.store.state
    assertTrue("state after unpairing = $saved", saved.credentials == null && saved.identity == testIdentity)
    assertTrue(runCatching { runBlocking { h.client.ask(AskInput.Text("hello")) } }.exceptionOrNull() is OfflineException)
    // With nothing to connect with, run says so at once and touches nothing.
    val before = h.fake.log().size
    runBlocking { withTimeout(5.seconds) { h.client.run() } }
    assertEquals("an unpaired run reached the server: ${h.fake.log().drop(before)}", before, h.fake.log().size)
  }

  @Test
  fun cancelMidAskDeliversNothingAfter() {
    val (h, vm) =
        online { h, st, _ ->
          h.fake.hold = CountDownLatch(1)
          st
        }
    val hold = h.fake.hold!!
    val c = Collect()
    val busy = CompletableDeferred<Throwable?>()
    val asking = CompletableDeferred<Job>()
    val err = runCatching {
      runBlocking {
        val ask =
            h.scope.async {
              h.client.ask(AskInput.Text("hello")) { e ->
                c.on(e)
                if (e is ReplyEvent.Text) {
                  // While one answer is open, a second question is refused.
                  busy.complete(runCatching { h.client.ask(AskInput.Text("me too")) }.exceptionOrNull())
                  asking.await().cancel()
                }
              }
            }
        asking.complete(ask)
        ask.await()
      }
    }.exceptionOrNull()
    assertTrue("ask returned $err", err is CancellationException)
    val overlapping = runBlocking { busy.await() }
    assertTrue("the overlapping ask returned $overlapping", overlapping is BusyException)
    val delivered = c.events()
    assertEquals("delivered before the cancel", listOf(ReplyEvent.Text("you sai")), delivered)

    // The rest of the answer arrives after the cancel, and long enough passes for it to have settled
    // and finished.
    h.fake.hold = null
    hold.countDown()
    vm.released.receive("the rest of the answer")
    Thread.sleep(3 * h.client.t.final.inWholeMilliseconds)
    assertEquals("delivered after the cancel", delivered, c.events())

    // The abandoned answer does not leak into the next one.
    assertEquals("you said: again", runBlocking { h.client.ask(AskInput.Text("again")) }.text)
  }

  @Test
  fun cancelMidUploadWithdrawsTheVoiceNote() {
    val (h, vm) = online()
    val err = runCatching {
      runBlocking {
        h.scope
            .async {
              currentCoroutineContext().job.cancel()
              h.client.ask(AskInput.VoiceNote(testWav(40_000)))
            }
            .await()
      }
    }.exceptionOrNull()
    assertTrue("ask returned $err", err is CancellationException)
    // The next ask is answered, so the reset has been read by then.
    runBlocking { h.client.ask(AskInput.Text("after")) }
    assertTrue("the upload was not reset: ${h.fake.log()}", h.fake.log().any { it.startsWith("reset canceled") })
    assertEquals("the abandoned voice note was taken", JsonPrimitive("after"), vm.chats.receive("the chat").body["message"])
  }
}

/** A Config the tests can change before the client is made, as the Go tests' tune functions do. */
class ConfigBuilder(
    var store: Store,
    var displayName: String = "",
    var version: String = "",
    var commands: MutableList<Command> = mutableListOf(),
    var sdkToken: String = "",
    var onState: ((ConnState) -> Unit)? = null,
    var logger: Logger = Logger.None,
    var http: okhttp3.OkHttpClient? = null,
) {
  fun build() = Config(store, commands.toList(), displayName, version, sdkToken, onState, logger, http)
}
