package dev.wobsoriano.museportal.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyQueueTest {
  private val sent = mutableListOf<String>()
  private val timers = mutableListOf<Pair<Long, () -> Unit>>()
  private var linkUp = true
  private val queue =
      NotifyQueue(
          link = { packet -> if (linkUp) sent += String(packet) else Unit; linkUp },
          scheduler = { delay, task -> timers += delay to task })

  private fun packets(vararg s: String) = s.map { it.toByteArray() }

  private fun fireTimers() {
    val due = timers.toList()
    timers.clear()
    due.forEach { it.second() }
  }

  @Test
  fun sendsOnePacketAtATimeAfterTheStackReportsItSent() {
    queue.enqueue(packets("a", "b", "c"))
    assertEquals(listOf("a"), sent)

    queue.onSent()
    assertEquals(listOf("a"), sent)
    assertEquals(listOf(50L), timers.map { it.first })

    fireTimers()
    assertEquals(listOf("a", "b"), sent)

    queue.onSent()
    fireTimers()
    queue.onSent()
    fireTimers()
    assertEquals(listOf("a", "b", "c"), sent)
  }

  @Test
  fun packetsQueuedWhileBusyWaitTheirTurn() {
    queue.enqueue(packets("a"))
    queue.enqueue(packets("b"))
    assertEquals(listOf("a"), sent)

    queue.onSent()
    fireTimers()
    assertEquals(listOf("a", "b"), sent)
  }

  @Test
  fun clearCancelsTheStaggerAlreadyScheduled() {
    queue.enqueue(packets("a", "b"))
    queue.onSent()
    queue.clear()
    fireTimers()
    assertEquals(listOf("a"), sent)

    queue.enqueue(packets("c"))
    assertEquals(listOf("a", "c"), sent)
  }

  @Test
  fun aRefusedNotificationDropsTheRest() {
    linkUp = false
    queue.enqueue(packets("a", "b"))
    linkUp = true
    queue.onSent()
    fireTimers()
    assertEquals(emptyList<String>(), sent)

    queue.enqueue(packets("c"))
    assertEquals(listOf("c"), sent)
  }
}
