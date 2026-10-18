package dev.wobsoriano.museportal.wake.vosk

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A phrase word and the confidence Vosk must give it for a wake. */
data class PhraseWord(val word: String, val minConf: Double)

/** A word in a Vosk result: `SetWords(true)` confidence, times in seconds since the recognizer started. */
data class Heard(val word: String, val conf: Double, val start: Double, val end: Double)

/** What one Vosk final result means for the wake phrase. */
sealed class Decision {
  abstract val heard: List<Heard>

  /** Exactly the phrase, every word at or above its floor. */
  data class Wake(override val heard: List<Heard>) : Decision()

  /** The keyword was heard but is not a wake. Worth logging when tuning. */
  data class NearMiss(override val heard: List<Heard>, val reason: Reason) : Decision()

  /** Nothing like the phrase, including an empty result. */
  data class Ignore(override val heard: List<Heard>) : Decision()
}

sealed class Reason {
  /** Exactly the phrase, but these words fell below their floors. */
  data class Unsure(val below: List<Pair<Heard, PhraseWord>>) : Reason() {
    override fun toString() =
        "unsure: " + below.joinToString(", ") { (h, p) -> String.format(Locale.ROOT, "%s %.2f < %.2f", p.word, h.conf, p.minConf) }
  }

  /** The keyword among other words. */
  object NotAlone : Reason() {
    override fun toString() = "not alone"
  }
}

/**
 * The "Hey Muse" rule for Vosk in restricted-grammar mode: the [grammar] to build the recognizer with,
 * and the [phrase] a final result must be, word for word and over each word's floor, to wake.
 *
 * The decoys in the grammar give the decoder somewhere to put speech that is not the phrase, so random
 * speech does not come out as "hey muse" unless it sounds like it; the floors reject what still does.
 * `tools/wakeeval/eval.py` applies the same rule from the same file to a WAV on the PC.
 */
class VoskWake(val phrase: List<PhraseWord>, val grammar: List<String>) {
  init {
    require(phrase.isNotEmpty()) { "empty phrase" }
    require(phrase.joinToString(" ") { it.word } in grammar) { "the grammar lacks the phrase" }
  }

  /** The grammar as Vosk's `Recognizer(model, rate, grammar)` takes it. */
  val grammarJson: String = JsonArray(grammar.map(::JsonPrimitive)).toString()

  /** Decides a Vosk final result (`Recognizer.getResult()` / `getFinalResult()` JSON). */
  fun decide(resultJson: String): Decision {
    val heard = parseResult(resultJson)
    if (heard.map { it.word } == phrase.map { it.word }) {
      val below = heard.zip(phrase).filter { (h, p) -> h.conf < p.minConf }
      return if (below.isEmpty()) Decision.Wake(heard) else Decision.NearMiss(heard, Reason.Unsure(below))
    }
    if (heard.any { it.word == phrase.last().word }) return Decision.NearMiss(heard, Reason.NotAlone)
    return Decision.Ignore(heard)
  }

  companion object {
    fun parse(json: String): VoskWake {
      val root = Json.parseToJsonElement(json).jsonObject
      return VoskWake(
          phrase = root.getValue("phrase").jsonArray.map {
            val o = it.jsonObject
            PhraseWord(o.getValue("word").jsonPrimitive.content, o.getValue("minConf").jsonPrimitive.double)
          },
          grammar = root.getValue("grammar").jsonArray.map { it.jsonPrimitive.content })
    }

    /** The rule in this library's `vosk/hey_muse.json`, which `tools/wakeeval` reads too. */
    fun heyMuse(): VoskWake =
        parse(checkNotNull(VoskWake::class.java.getResourceAsStream("/vosk/hey_muse.json")) { "vosk/hey_muse.json missing" }
            .bufferedReader().use { it.readText() })

    /** The words of a result. A word without a confidence (no `SetWords(true)`) counts as 0. */
    fun parseResult(json: String): List<Heard> {
      val words = Json.parseToJsonElement(json).jsonObject["result"]?.jsonArray ?: return emptyList()
      return words.map {
        val o = it.jsonObject
        Heard(o.getValue("word").jsonPrimitive.content, o.number("conf"), o.number("start"), o.number("end"))
      }
    }

    private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
  }
}
