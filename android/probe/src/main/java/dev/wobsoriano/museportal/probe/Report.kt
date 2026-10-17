package dev.wobsoriano.museportal.probe

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** Every probe result, in order. Each one is logged to logcat (tag MuseProbe) as it is added. */
class Report(private val onLine: (String) -> Unit) {
  data class Entry(val section: String, val key: String, val value: String)

  private val entries = CopyOnWriteArrayList<Entry>()

  fun put(section: String, key: String, value: Any?) {
    val entry = Entry(section, key, value.toString())
    entries += entry
    val line = "${entry.section}.${entry.key} = ${entry.value}"
    Log.i(TAG, line)
    onLine(line)
  }

  /** Runs [block]; a throw becomes `<section>.error` and the report carries on. */
  fun section(name: String, block: Section.() -> Unit) {
    val section = Section(name)
    try {
      section.block()
    } catch (e: Throwable) {
      put(name, "error", e.toString())
    }
  }

  inner class Section(val name: String) {
    fun put(key: String, value: Any?) = this@Report.put(name, key, value)

    /** Puts the value of [value], or the exception it threw. */
    fun get(key: String, value: () -> Any?) =
        put(key, try { value() } catch (e: Throwable) { "error: $e" })
  }

  fun toJson(): String =
      JSONObject()
          .put("entries", JSONArray(entries.map { JSONObject().put("section", it.section).put("key", it.key).put("value", it.value) }))
          .toString(2)

  companion object {
    const val TAG = "MuseProbe"
  }
}
