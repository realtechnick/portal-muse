package dev.wobsoriano.museportal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {
  private fun bytes(vararg b: Int) = Bytes(ByteArray(b.size) { b[it].toByte() })

  @Test
  fun encodesEveryCommandToTheProtocolJson() {
    val cases =
        listOf(
            Command.Config("Kitchen", "mgst_x", "home", listOf("192.168.1.1", "1.1.1.1:53")) to
                """{"type":"config","name":"Kitchen","sdkToken":"mgst_x","ssid":"home","dns":["192.168.1.1","1.1.1.1:53"]}""",
            Command.PairStart to """{"type":"pair.start"}""",
            Command.PairStop to """{"type":"pair.stop"}""",
            Command.BleAdvertising(ok = true) to """{"type":"ble.advertising","ok":true,"error":""}""",
            Command.BleAdvertising(ok = false, error = "data too large") to
                """{"type":"ble.advertising","ok":false,"error":"data too large"}""",
            Command.BleConnected("AA:BB:CC:DD:EE:FF") to
                """{"type":"ble.connected","device":"AA:BB:CC:DD:EE:FF"}""",
            Command.BleWrite(bytes(1, 2, 3, 0xff), 185) to
                """{"type":"ble.write","data":"AQID/w==","mtu":185}""",
            Command.BleDisconnected to """{"type":"ble.disconnected"}""",
            Command.Ask.ofText("a1", "what time is it") to
                """{"type":"ask","id":"a1","text":"what time is it"}""",
            Command.Ask.ofWav("a2", byteArrayOf(82, 73, 70, 70)) to
                """{"type":"ask","id":"a2","wav":"UklGRg=="}""",
            Command.Unpair to """{"type":"unpair"}""",
        )
    for ((command, json) in cases) assertEquals(json, Codec.encode(command))
  }

  @Test
  fun decodesEveryEventFromTheProtocolJson() {
    val cases =
        listOf(
            """{"type":"status","phase":"online","error":""}""" to Event.Status(Phase.Online),
            """{"type":"status","phase":"offline","error":"dial tcp: timeout"}""" to
                Event.Status(Phase.Offline, "dial tcp: timeout"),
            """{"type":"status","phase":"unpaired"}""" to Event.Status(Phase.Unpaired),
            """{"type":"pair.advertise","name":"MuseGadget1A2B3C","service":"0000fd5f-0000-1000-8000-00805f9b34fb","rx":"r","tx":"t","manufacturerId":1003,"manufacturerData":"AQID"}""" to
                Event.PairAdvertise(
                    "MuseGadget1A2B3C", "0000fd5f-0000-1000-8000-00805f9b34fb", "r", "t", 1003, bytes(1, 2, 3)),
            """{"type":"pair.unadvertise"}""" to Event.PairUnadvertise,
            """{"type":"ble.notify","packets":["AQ==","AgM="]}""" to
                Event.BleNotify(listOf(bytes(1), bytes(2, 3))),
            """{"type":"ble.disconnect"}""" to Event.BleDisconnect,
            """{"type":"pair.status","state":"open","name":"MuseGadget1A2B3C","error":""}""" to
                Event.PairStatus(PairState.Open, "MuseGadget1A2B3C"),
            """{"type":"pair.status","state":"failed","error":"no sdkToken"}""" to
                Event.PairStatus(PairState.Failed, error = "no sdkToken"),
            """{"type":"reply","id":"a1","kind":"heard","text":"what time is it"}""" to
                Event.Reply("a1", ReplyKind.Heard, "what time is it"),
            """{"type":"reply","id":"a1","kind":"text","text":"It is"}""" to
                Event.Reply("a1", ReplyKind.Text, "It is"),
            """{"type":"reply","id":"a1","kind":"settled","text":"It is noon."}""" to
                Event.Reply("a1", ReplyKind.Settled, "It is noon."),
            """{"type":"reply","id":"a1","kind":"done","text":"It is noon."}""" to
                Event.Reply("a1", ReplyKind.Done, "It is noon."),
            """{"type":"reply","id":"a1","kind":"error","error":"offline"}""" to
                Event.Reply("a1", ReplyKind.Error, error = "offline"),
        )
    for ((json, event) in cases) assertEquals(json, Decoded.Known(event), Codec.decode(json))
  }

  @Test
  fun keepsDecodingWhenTheCoreAddsFields() {
    assertEquals(
        Decoded.Known(Event.Status(Phase.Connecting)),
        Codec.decode("""{"type":"status","phase":"connecting","since":12}"""))
  }

  @Test
  fun dropsUnknownTypesByName() {
    assertEquals(Decoded.Unknown("wake"), Codec.decode("""{"type":"wake","score":0.9}"""))
  }

  @Test
  fun reportsLinesThatAreNotMessages() {
    assertEquals(Decoded.Malformed("no type"), Codec.decode("""{"phase":"online"}"""))
    assertEquals(Decoded.Malformed("not an object"), Codec.decode("""[1,2]"""))
    val badEnum = Codec.decode("""{"type":"status","phase":"sideways"}""")
    assertEquals(true, badEnum is Decoded.Malformed && badEnum.reason.startsWith("status: "))
  }

  @Test
  fun configToStringHidesTheToken() {
    assertEquals(
        "Config(name=P, sdkToken=<set>, ssid=, dns=[])",
        Command.Config("P", "mgst_secret", "", emptyList()).toString())
  }
}
