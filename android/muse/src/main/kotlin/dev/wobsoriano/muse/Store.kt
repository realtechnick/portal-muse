package dev.wobsoriano.muse

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal val stateJson = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
  explicitNulls = false
  prettyPrint = true
  prettyPrintIndent = "  "
}

/**
 * Keeps the [State] in one JSON file, owner-only, written whole: the format musecore's FileStore
 * wrote, so a device it paired stays paired.
 *
 * [syncDir] makes the rename durable by syncing the directory. Plain Java cannot open a directory on
 * every platform, so the default tries and gives up quietly; Android passes one built on Os.fsync.
 */
class FileStore(val file: File, private val syncDir: (File) -> Unit = ::syncDirectory) : Store {
  /**
   * Reads the file. A missing file throws [java.nio.file.NoSuchFileException], since nothing
   * distinguishes a device that was never paired from one whose state has gone: the caller decides
   * what a missing file means.
   */
  override suspend fun load(): State = withContext(Dispatchers.IO) { read() }

  fun read(): State {
    val b = Files.readAllBytes(file.toPath())
    try {
      return stateJson.decodeFromString(State.serializer(), String(b, Charsets.UTF_8))
    } catch (e: SerializationException) {
      throw IOException("${file.path}: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
      throw IOException("${file.path}: ${e.message}", e)
    }
  }

  /**
   * Writes beside the file and renames over it, so a crash cannot leave half a pairing, and does not
   * return until the new file would survive a power cut.
   */
  override suspend fun save(state: State) = withContext(Dispatchers.IO) { write(state) }

  fun write(state: State) {
    val b = (stateJson.encodeToString(State.serializer(), state) + "\n").toByteArray(Charsets.UTF_8)
    val dir = file.absoluteFile.parentFile
    val tmp = File.createTempFile(file.name + ".", ".tmp", dir)
    try {
      tmp.setReadable(false, false)
      tmp.setWritable(false, false)
      tmp.setReadable(true, true)
      tmp.setWritable(true, true)
      FileOutputStream(tmp).use { out ->
        out.write(b)
        out.fd.sync()
      }
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
      tmp.delete()
      throw e
    }
    syncDir(dir)
  }
}

/** Syncs [dir] where the platform lets a directory be opened, which Linux's JVM does and Windows does not. */
fun syncDirectory(dir: File) {
  try {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
  } catch (_: IOException) {
  } catch (_: UnsupportedOperationException) {
  }
}
