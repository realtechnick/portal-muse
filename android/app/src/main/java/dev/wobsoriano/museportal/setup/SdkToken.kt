package dev.wobsoriano.museportal.setup

import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.Status

/** The Muse SDK token, as the user types it: `mgst_` and the rest, no spaces. */
object SdkToken {
  private const val PREFIX = "mgst_"

  fun parse(raw: String): String? {
    val t = raw.trim()
    if (!t.startsWith(PREFIX) || t.length < PREFIX.length + 8 || t.length > 512) return null
    return t.takeIf { s -> s.all { it in '!'..'~' } }
  }

  /** What the screen may show: `mgst_…` and the last four characters. */
  fun mask(token: String): String = if (token.length <= PREFIX.length + 4) "$PREFIX…" else "$PREFIX…${token.takeLast(4)}"
}

/** What "Save & verify" found, shown under the field. */
sealed interface TokenCheck {
  data object Malformed : TokenCheck

  /** Saved; the core is reconnecting with it. */
  data object Checking : TokenCheck

  /** The core is online with the saved token. */
  data object Accepted : TokenCheck

  /** Saved on a Portal that isn't paired yet: Muse first sees the token when the phone pairs. */
  data object UntilPairing : TokenCheck

  data class Offline(val reason: String) : TokenCheck
}

/** The verdict on a token that was well-formed and saved, from the connection since. */
fun savedTokenCheck(paired: Boolean?, connection: Status?): TokenCheck =
    when {
      paired != true -> TokenCheck.UntilPairing
      connection == null || connection.phase == Phase.Connecting -> TokenCheck.Checking
      connection.phase == Phase.Online -> TokenCheck.Accepted
      connection.phase == Phase.Unpaired -> TokenCheck.UntilPairing
      else -> TokenCheck.Offline(connection.error.ifEmpty { "no connection" })
    }
