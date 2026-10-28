package dev.wobsoriano.museportal.device

import android.content.Context
import java.io.File
import org.json.JSONObject

/** One saved announcement as the replay list shows it. */
data class AnnouncementInfo(val id: Long, val atMs: Long, val hasCard: Boolean, val hasAudio: Boolean)

/**
 * The last few announcements Muse pushed, kept on the device so the room can hear one again from
 * the home screen's replay button. Each entry is a directory under files/announcements holding the
 * card and audio bytes as they arrived (either may be absent) plus a meta.json with their source
 * URLs, which replay falls back to when the bytes are missing. Only the newest [MAX_ENTRIES] stay.
 */
class AnnouncementStore(context: Context) {
  private val root = File(context.filesDir, "announcements")

  /** A stored entry's parts: the list facts, the local files when saved, and the source URLs. */
  class Entry(val info: AnnouncementInfo, val cardFile: File?, val audioFile: File?, val cardUrl: String?, val audioUrl: String?)

  /** Every saved announcement, newest first. */
  @Synchronized
  fun loadAll(): List<AnnouncementInfo> = readEntries().map { it.info }

  /** One entry by id, or null when it has been evicted (or never existed). */
  @Synchronized
  fun entry(id: Long): Entry? = readEntries().find { it.info.id == id }

  /** Saves a pushed card as a new entry and returns its id. */
  @Synchronized
  fun saveCard(bytes: ByteArray, url: String): Long {
    val id = freshId()
    val dir = dirFor(id)
    dir.mkdirs()
    File(dir, CARD_FILE).writeBytes(bytes)
    writeMeta(dir, cardUrl = url, audioUrl = null)
    evict()
    return id
  }

  /**
   * Attaches audio to an existing entry. Returns false when the entry is gone or already has audio,
   * so the caller can save the audio as its own entry instead. [bytes] may be null when the download
   * failed: the URL alone is kept and replay streams it.
   */
  @Synchronized
  fun attachAudio(id: Long, bytes: ByteArray?, url: String): Boolean {
    val dir = dirFor(id)
    if (!dir.isDirectory) return false
    val meta = readMeta(dir)
    val hasAudio = File(dir, AUDIO_FILE).exists() || !meta.optString("audioUrl").isNullOrEmpty()
    if (hasAudio) return false
    if (bytes != null) File(dir, AUDIO_FILE).writeBytes(bytes)
    writeMeta(dir, cardUrl = meta.optString("cardUrl").takeIf { it.isNotEmpty() }, audioUrl = url)
    return true
  }

  /** Saves audio that belongs to no card as its own entry and returns its id. */
  @Synchronized
  fun saveAudioOnly(bytes: ByteArray?, url: String): Long {
    val id = freshId()
    val dir = dirFor(id)
    dir.mkdirs()
    if (bytes != null) File(dir, AUDIO_FILE).writeBytes(bytes)
    writeMeta(dir, cardUrl = null, audioUrl = url)
    evict()
    return id
  }

  private fun readEntries(): List<Entry> {
    val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) }.orEmpty()
    return dirs
        .mapNotNull { dir ->
          val id = dir.name.removePrefix(DIR_PREFIX).toLongOrNull() ?: return@mapNotNull null
          val meta = readMeta(dir)
          val card = File(dir, CARD_FILE).takeIf { it.exists() }
          val audio = File(dir, AUDIO_FILE).takeIf { it.exists() }
          val audioUrl = meta.optString("audioUrl").takeIf { it.isNotEmpty() }
          Entry(
              AnnouncementInfo(id, id, hasCard = card != null, hasAudio = audio != null || audioUrl != null),
              card,
              audio,
              meta.optString("cardUrl").takeIf { it.isNotEmpty() },
              audioUrl)
        }
        .sortedByDescending { it.info.id }
  }

  private fun evict() {
    val dirs =
        root.listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) }.orEmpty()
            .sortedByDescending { it.name.removePrefix(DIR_PREFIX).toLongOrNull() ?: 0L }
    dirs.drop(MAX_ENTRIES).forEach { it.deleteRecursively() }
  }

  private fun freshId(): Long {
    var id = System.currentTimeMillis()
    while (dirFor(id).exists()) id++
    return id
  }

  private fun dirFor(id: Long) = File(root, "$DIR_PREFIX$id")

  private fun readMeta(dir: File): JSONObject =
      runCatching { JSONObject(File(dir, META_FILE).readText()) }.getOrDefault(JSONObject())

  private fun writeMeta(dir: File, cardUrl: String?, audioUrl: String?) {
    val meta = JSONObject()
    if (cardUrl != null) meta.put("cardUrl", cardUrl)
    if (audioUrl != null) meta.put("audioUrl", audioUrl)
    File(dir, META_FILE).writeText(meta.toString())
  }

  private companion object {
    const val MAX_ENTRIES = 5
    const val DIR_PREFIX = "ann-"
    const val CARD_FILE = "card.bin"
    const val AUDIO_FILE = "audio.bin"
    const val META_FILE = "meta.json"
  }
}
