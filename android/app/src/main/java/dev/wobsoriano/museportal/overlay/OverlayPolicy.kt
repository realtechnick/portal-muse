package dev.wobsoriano.museportal.overlay

import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.inFlight

/**
 * Whether the bar over other apps is wanted: while an ask is recorded, answered or spoken and our own
 * screen isn't in front to show it. Once the answer has been spoken it goes, after [LINGER_MS].
 */
fun overlayWanted(ask: AskState, speaking: Boolean, inFront: Boolean): Boolean = !inFront && (ask.inFlight || speaking)

/** How long the bar stays after it is no longer wanted, so the end of an answer or a failure can be read. */
const val LINGER_MS = 3_000L
