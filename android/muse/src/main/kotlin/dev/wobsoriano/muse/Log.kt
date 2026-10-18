package dev.wobsoriano.muse

enum class Level {
  Debug,
  Info,
  Warn,
  Error,
}

/** Where the library's log lines go. Nothing it logs carries a token or what was said. */
fun interface Logger {
  fun log(level: Level, message: String)

  companion object {
    val None = Logger { _, _ -> }
  }
}

internal fun Logger.line(level: Level, message: String, fields: Array<out Pair<String, Any?>>) {
  if (fields.isEmpty()) return log(level, message)
  log(level, fields.joinToString(" ", prefix = "$message ") { (k, v) -> "$k=$v" })
}

internal fun Logger.debug(message: String, vararg fields: Pair<String, Any?>) = line(Level.Debug, message, fields)

internal fun Logger.info(message: String, vararg fields: Pair<String, Any?>) = line(Level.Info, message, fields)

internal fun Logger.warn(message: String, vararg fields: Pair<String, Any?>) = line(Level.Warn, message, fields)

internal fun Logger.error(message: String, vararg fields: Pair<String, Any?>) = line(Level.Error, message, fields)
