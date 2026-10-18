package dev.wobsoriano.museportal.wake.vosk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Results are literal Vosk output for wake1.wav (tools/wakeeval), except where noted. */
class VoskWakeTest {
  private val rule = VoskWake.heyMuse()

  @Test
  fun confidentPhraseWakes() {
    val d = rule.decide("""{ "result" : [{ "conf" : 1.000000, "end" : 11.040000, "start" : 10.830000, "word" : "hey" },
        { "conf" : 0.962910, "end" : 11.580000, "start" : 11.040000, "word" : "muse" }], "text" : "hey muse" }""")
    assertEquals(Decision.Wake(listOf(Heard("hey", 1.0, 10.83, 11.04), Heard("muse", 0.96291, 11.04, 11.58))), d)
  }

  @Test
  fun speechDecodedAsThePhraseIsANearMiss() {
    val d = rule.decide("""{ "result" : [{ "conf" : 0.935972, "end" : 1.223694, "start" : 1.050000, "word" : "hey" },
        { "conf" : 0.717330, "end" : 1.650000, "start" : 1.230000, "word" : "muse" }], "text" : "hey muse" }""")
    assertTrue(d is Decision.NearMiss)
    assertEquals("unsure: hey 0.94 < 0.95, muse 0.72 < 0.85", (d as Decision.NearMiss).reason.toString())
  }

  @Test
  fun otherWordsAreIgnored() {
    assertTrue(rule.decide("""{ "result" : [{ "conf" : 0.549814, "end" : 52.650000, "start" : 52.440000, "word" : "why" },
        { "conf" : 0.956743, "end" : 52.980000, "start" : 52.650601, "word" : "not" }], "text" : "why not" }""") is Decision.Ignore)
    assertTrue(rule.decide("""{ "result" : [{ "conf" : 0.936936, "end" : 50.518484, "start" : 50.010000, "word" : "[unk]" }],
        "text" : "[unk]" }""") is Decision.Ignore)
    assertEquals(Decision.Ignore(emptyList()), rule.decide("""{ "text" : "" }"""))
  }

  @Test
  fun keywordAmongOtherWordsIsANearMiss() {
    // Constructed: wake1.wav has no such result.
    val d = rule.decide("""{ "result" : [{ "conf" : 1.0, "end" : 1.2, "start" : 1.0, "word" : "[unk]" },
        { "conf" : 1.0, "end" : 1.4, "start" : 1.2, "word" : "hey" }, { "conf" : 1.0, "end" : 1.8, "start" : 1.4, "word" : "muse" }],
        "text" : "[unk] hey muse" }""")
    assertEquals(Reason.NotAlone, (d as Decision.NearMiss).reason)
  }

  @Test
  fun agreesWithTheEvalScriptOnWake1() {
    val lines = javaClass.getResource("/vosk/wake1.results.jsonl")!!.readText().lines().filter { it.isNotBlank() }
    assertEquals(14, lines.size)
    val kinds = lines.map { line ->
      val o = Json.parseToJsonElement(line).jsonObject
      val want = o.getValue("decision").jsonPrimitive.content
      val got = when (rule.decide(o.getValue("result").toString())) {
        is Decision.Wake -> "wake"
        is Decision.NearMiss -> "near_miss"
        is Decision.Ignore -> "ignore"
      }
      assertEquals(line, want, got)
      got
    }
    assertEquals(9, kinds.count { it == "wake" })
    assertEquals(3, kinds.count { it == "near_miss" })
  }

  @Test
  fun grammarIsAJsonArrayOfTheRuleWords() {
    assertTrue(rule.grammarJson.startsWith("[\"hey muse\","))
    assertTrue(rule.grammarJson.endsWith(",\"[unk]\"]"))
  }
}
