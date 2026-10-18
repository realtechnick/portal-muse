package dev.wobsoriano.museportal.wake.vosk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Results are literal vosk-model-small-en-us-0.15 output (tools/wakeeval), except where noted. */
class VoskWakeTest {
  private val rule = VoskWake.heyMuse()

  @Test
  fun confidentPhraseWakes() {
    val d = rule.decide("""{ "result" : [{ "conf" : 1.0, "end" : 7.02, "start" : 6.81, "word" : "hey" },
        { "conf" : 1.0, "end" : 7.65, "start" : 7.02, "word" : "muse" }], "text" : "hey muse" }""")
    val phrase = listOf(Heard("hey", 1.0, 6.81, 7.02), Heard("muse", 1.0, 7.02, 7.65))
    assertEquals(Decision.Wake(phrase, phrase), d)
  }

  @Test
  fun phraseWrappedInOtherSpeechWakes() {
    // vosk3.wav, a real attempt from the probe.
    val d = rule.decide("""{"result": [{"conf": 1.0, "end": 50.022594, "start": 49.44, "word": "[unk]"},
        {"conf": 0.70769, "end": 50.16, "start": 50.04, "word": "the"}, {"conf": 1.0, "end": 50.37, "start": 50.16, "word": "hey"},
        {"conf": 1.0, "end": 50.82, "start": 50.37, "word": "muse"}, {"conf": 1.0, "end": 51.6, "start": 50.94, "word": "[unk]"}],
        "text": "[unk] the hey muse [unk]"}""")
    assertEquals(listOf(Heard("hey", 1.0, 50.16, 50.37), Heard("muse", 1.0, 50.37, 50.82)), (d as Decision.Wake).phrase)
  }

  @Test
  fun aWeakPhraseIsANearMiss() {
    val d = rule.decide("""{"result": [{"conf": 0.55575, "end": 33.99, "start": 33.78, "word": "hey"},
        {"conf": 0.844997, "end": 34.65, "start": 33.99, "word": "muse"}], "text": "hey muse"}""")
    assertEquals("unsure: hey 0.56 < 0.95, muse 0.84 < 0.85", (d as Decision.NearMiss).reason.toString())
  }

  @Test
  fun aConfidentRepeatAfterAWeakOneWakes() {
    // Constructed: the second "hey muse" in one result clears its floors.
    val d = rule.decide("""{"result": [{"conf": 0.5, "end": 1.2, "start": 1.0, "word": "hey"}, {"conf": 0.5, "end": 1.6, "start": 1.2, "word": "muse"},
        {"conf": 1.0, "end": 2.2, "start": 2.0, "word": "hey"}, {"conf": 1.0, "end": 2.6, "start": 2.2, "word": "muse"}], "text": "hey muse hey muse"}""")
    assertEquals(2.0, (d as Decision.Wake).phrase.first().start, 0.0)
  }

  @Test
  fun theWordsMustBeAdjacent() {
    // Constructed.
    val d = rule.decide("""{"result": [{"conf": 1.0, "end": 1.2, "start": 1.0, "word": "hey"}, {"conf": 1.0, "end": 1.4, "start": 1.2, "word": "the"},
        {"conf": 1.0, "end": 1.8, "start": 1.4, "word": "muse"}], "text": "hey the muse"}""")
    assertEquals(Reason.Partial, (d as Decision.NearMiss).reason)
  }

  @Test
  fun otherWordsAreIgnored() {
    assertTrue(rule.decide("""{"result": [{"conf": 0.582454, "end": 52.609017, "start": 52.38, "word": "why"},
        {"conf": 1.0, "end": 53.01, "start": 52.609017, "word": "not"}], "text": "why not"}""") is Decision.Ignore)
    assertTrue(rule.decide("""{ "result" : [{ "conf" : 1.0, "end" : 1.4, "start" : 1.0, "word" : "hey" }], "text" : "hey" }""") is Decision.Ignore)
    assertEquals(Decision.Ignore(emptyList()), rule.decide("""{ "text" : "" }"""))
  }

  @Test
  fun agreesWithTheEvalScriptOnWake1() {
    val lines = javaClass.getResource("/vosk/wake1.results.jsonl")!!.readText().lines().filter { it.isNotBlank() }
    assertEquals(13, lines.size)
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
    assertEquals(10, kinds.count { it == "wake" })
    assertEquals(2, kinds.count { it == "near_miss" })
  }

  @Test
  fun grammarIsAJsonArrayOfTheRuleWords() {
    assertTrue(rule.grammarJson.startsWith("[\"hey muse\","))
    assertTrue(rule.grammarJson.endsWith(",\"[unk]\"]"))
  }
}
