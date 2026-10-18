package dev.wobsoriano.museportal.heymuse

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A Vosk model zip as alphacephei publishes it: one top-level folder named [name]. */
data class ModelSpec(val name: String, val url: String, val sha256: String)

/** vosk-model-small-en-us-0.15: on a Portal, 1.2 s to load, ~158 ms CPU per audio second, ~182 MB PSS. */
val SMALL_EN_US =
    ModelSpec(
        name = "vosk-model-small-en-us-0.15",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")

/** An open download: its body, and its length in bytes or -1 when the server did not say. */
class Download(val body: InputStream, val length: Long)

/** Why a model could not be installed, in words for the screen. */
class InstallFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

class InstallCancelled : Exception()

/**
 * The Vosk model in [root] (the app's files dir). [dir] exists only once a model is completely
 * unpacked: the zip goes to a `.part` file and is unpacked into a `.tmp` folder that is renamed into
 * place, so a run that died anywhere leaves only leftovers the next run deletes.
 */
class ModelStore(private val root: File, private val spec: ModelSpec = SMALL_EN_US) {
  val dir = File(root, spec.name)
  private val part = File(root, "${spec.name}.zip.part")
  private val tmp = File(root, "${spec.name}.tmp")

  fun installed() = dir.isDirectory

  /** Throws away an unloadable model so the next [install] fetches it again. */
  fun discard() {
    dir.deleteRecursively()
  }

  /**
   * Downloads, verifies and unpacks the model unless it is already [installed]. [progress] gets the
   * fraction downloaded, or null when the size is unknown. Throws [InstallFailed] or [InstallCancelled].
   */
  fun install(open: (String) -> Download, cancelled: () -> Boolean, progress: (Float?) -> Unit) {
    if (installed()) return
    part.delete()
    tmp.deleteRecursively()
    try {
      fetch(open, cancelled, progress)
      unpack(cancelled)
      if (!tmp.renameTo(dir)) throw InstallFailed("Could not move the speech model into place")
    } finally {
      part.delete()
      tmp.deleteRecursively()
    }
  }

  private fun fetch(open: (String) -> Download, cancelled: () -> Boolean, progress: (Float?) -> Unit) {
    val sha = MessageDigest.getInstance("SHA-256")
    val download =
        try {
          open(spec.url)
        } catch (e: IOException) {
          throw InstallFailed("Could not download the speech model: ${e.message ?: e}", e)
        }
    try {
      download.body.use { body ->
        part.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var total = 0L
          var lastPercent = -1
          progress(if (download.length > 0) 0f else null)
          while (true) {
            if (cancelled()) throw InstallCancelled()
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            sha.update(buf, 0, n)
            total += n
            if (download.length > 0) {
              val percent = (total * 100 / download.length).toInt()
              if (percent != lastPercent) {
                lastPercent = percent
                progress(minOf(1f, total.toFloat() / download.length))
              }
            }
          }
        }
      }
    } catch (e: IOException) {
      throw InstallFailed("The speech model download broke off: ${e.message ?: e}", e)
    }
    val got = sha.digest().joinToString("") { "%02x".format(it) }
    if (got != spec.sha256) throw InstallFailed("The speech model download was damaged (checksum mismatch)")
  }

  /** Unpacks the zip's [ModelSpec.name] folder into [tmp]. */
  private fun unpack(cancelled: () -> Boolean) {
    val prefix = "${spec.name}/"
    val base = tmp.canonicalFile
    try {
      ZipInputStream(part.inputStream().buffered()).use { zip ->
        while (true) {
          if (cancelled()) throw InstallCancelled()
          val entry = zip.nextEntry ?: break
          val name = entry.name.replace('\\', '/')
          if (!name.startsWith(prefix)) continue
          val out = File(base, name.removePrefix(prefix)).canonicalFile
          if (out != base && !out.path.startsWith(base.path + File.separator)) {
            throw InstallFailed("The speech model archive has a bad path: $name")
          }
          if (entry.isDirectory) {
            out.mkdirs()
          } else {
            out.parentFile?.mkdirs()
            out.outputStream().use { zip.copyTo(it) }
          }
        }
      }
    } catch (e: IOException) {
      throw InstallFailed("Could not unpack the speech model: ${e.message ?: e}", e)
    }
    if (!File(tmp, "am").isDirectory || !File(tmp, "conf").isDirectory) {
      throw InstallFailed("The speech model archive is not a Vosk model")
    }
  }
}
