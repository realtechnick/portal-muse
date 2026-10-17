package dev.wobsoriano.museportal.ble

/** Sends one notification on TX. False when it could not be queued with the stack. */
fun interface NotifyLink {
  fun notify(packet: ByteArray): Boolean
}

fun interface Scheduler {
  fun schedule(delayMs: Long, task: () -> Unit)
}

/**
 * Sends `ble.notify` packets one at a time: each waits for the previous notification to be
 * reported sent, then [staggerMs]. Not thread-safe; drive it from one thread.
 */
class NotifyQueue(
    private val link: NotifyLink,
    private val scheduler: Scheduler,
    private val staggerMs: Long = 50,
) {
  private val pending = ArrayDeque<ByteArray>()
  private var busy = false
  // A clear() must also cancel a stagger that is already scheduled.
  private var generation = 0

  fun enqueue(packets: List<ByteArray>) {
    pending.addAll(packets)
    if (!busy) sendNext()
  }

  fun onSent() {
    if (!busy) return
    val gen = generation
    scheduler.schedule(staggerMs) {
      if (gen == generation) {
        busy = false
        sendNext()
      }
    }
  }

  fun clear() {
    pending.clear()
    busy = false
    generation++
  }

  private fun sendNext() {
    val packet = pending.removeFirstOrNull() ?: return
    busy = true
    if (!link.notify(packet)) clear()
  }
}
