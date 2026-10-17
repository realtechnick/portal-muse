package dev.wobsoriano.museportal.core

import android.util.Log
import dev.wobsoriano.museportal.protocol.Codec
import dev.wobsoriano.museportal.protocol.Command
import dev.wobsoriano.museportal.protocol.Decoded
import dev.wobsoriano.museportal.protocol.Event
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

sealed interface CoreState {
  data object Starting : CoreState

  data object Running : CoreState

  /** libmusecore.so is not in this build. */
  data class Missing(val path: String) : CoreState

  data class Restarting(val exitCode: Int, val inMs: Long) : CoreState

  data object Stopped : CoreState
}

/**
 * Runs musecore as a child process and restarts it with backoff when it dies. Events arrive on the
 * supervisor thread. [firstCommand] is queued on each new process before anything else can be sent.
 */
class CoreProcess(
    private val binary: File,
    private val dataDir: File,
    private val onState: (CoreState) -> Unit,
    private val firstCommand: () -> Command,
    private val onEvent: (Event) -> Unit,
) {
  private class Running(val process: Process, val stdin: LinkedBlockingQueue<String>)

  @Volatile private var running: Running? = null
  @Volatile private var stopped = false
  private var supervisor: Thread? = null

  fun start() {
    if (supervisor != null) return
    stopped = false
    supervisor = thread(name = "musecore-supervisor") { supervise() }
  }

  fun stop() {
    stopped = true
    supervisor?.interrupt()
    supervisor = null
    running?.process?.destroy()
    onState(CoreState.Stopped)
  }

  /** False when musecore is not running; the command is dropped. */
  fun send(command: Command): Boolean {
    val r = running ?: return false
    Log.d(TAG, "-> $command")
    return r.stdin.offer(Codec.encode(command))
  }

  private fun supervise() {
    var failures = 0
    while (!stopped) {
      if (!binary.exists()) {
        Log.e(TAG, "missing ${binary.path}")
        onState(CoreState.Missing(binary.path))
        return
      }
      onState(CoreState.Starting)
      val startedAt = System.currentTimeMillis()
      val exit =
          try {
            runOnce()
          } catch (e: InterruptedException) {
            return
          } catch (e: Exception) {
            Log.e(TAG, "musecore failed to run", e)
            -1
          }
      if (stopped) return
      if (System.currentTimeMillis() - startedAt > HEALTHY_MS) failures = 0
      val delay = minOf(MAX_BACKOFF_MS, 1000L shl minOf(failures, 5))
      failures++
      Log.w(TAG, "musecore exited with $exit; restarting in $delay ms")
      onState(CoreState.Restarting(exit, delay))
      try {
        Thread.sleep(delay)
      } catch (e: InterruptedException) {
        return
      }
    }
  }

  private fun runOnce(): Int {
    val process = ProcessBuilder(binary.path, "--data", dataDir.path).start()
    val stdin = LinkedBlockingQueue<String>()
    val writer =
        thread(name = "musecore-stdin") {
          try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { out ->
              while (true) {
                out.write(stdin.take())
                out.write("\n")
                out.flush()
              }
            }
          } catch (e: InterruptedException) {
          } catch (e: java.io.IOException) {
            Log.w(TAG, "stdin closed: ${e.message}")
          }
        }
    val stderr =
        thread(name = "musecore-stderr") {
          process.errorStream.bufferedReader(Charsets.UTF_8).forEachLineSafely { Log.i(CORE_TAG, it) }
        }
    try {
      val first = firstCommand()
      Log.d(TAG, "-> $first")
      stdin.offer(Codec.encode(first))
      running = Running(process, stdin)
      onState(CoreState.Running)
      process.inputStream.bufferedReader(Charsets.UTF_8).forEachLineSafely(::dispatch)
      return process.waitFor()
    } catch (e: InterruptedException) {
      process.destroy()
      throw e
    } finally {
      running = null
      writer.interrupt()
      stderr.join(1000)
    }
  }

  private fun dispatch(line: String) {
    when (val decoded = Codec.decode(line)) {
      is Decoded.Known -> {
        Log.d(TAG, "<- ${decoded.event}")
        onEvent(decoded.event)
      }
      is Decoded.Unknown -> Log.w(TAG, "ignoring unknown message type ${decoded.type}")
      is Decoded.Malformed -> Log.w(TAG, "ignoring malformed line (${decoded.reason}): ${line.take(200)}")
    }
  }

  private fun java.io.BufferedReader.forEachLineSafely(block: (String) -> Unit) {
    try {
      useLines { lines -> lines.forEach(block) }
    } catch (e: java.io.IOException) {
      Log.w(TAG, "stream closed: ${e.message}")
    }
  }

  companion object {
    private const val TAG = "MuseApp"
    private const val CORE_TAG = "MuseCore"
    private const val HEALTHY_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30_000L
  }
}
