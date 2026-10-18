package dev.wobsoriano.museportal.device

import android.content.Context

/** The user's settings, in app-private preferences. The token never leaves this file except into the core's config. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("muse", Context.MODE_PRIVATE)

  var name: String
    get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

  var sdkToken: String
    get() = prefs.getString(KEY_TOKEN, null).orEmpty()
    set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

  /** The "Hey Muse" switch. */
  var wakeEnabled: Boolean
    get() = prefs.getBoolean(KEY_WAKE, false)
    set(value) = prefs.edit().putBoolean(KEY_WAKE, value).apply()

  private companion object {
    const val DEFAULT_NAME = "Portal"
    const val KEY_NAME = "name"
    const val KEY_TOKEN = "sdkToken"
    const val KEY_WAKE = "wakeEnabled"
  }
}
