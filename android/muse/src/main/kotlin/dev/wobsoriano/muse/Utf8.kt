package dev.wobsoriano.muse

/** Whether [b] is well-formed UTF-8 as Go's utf8.Valid has it: no overlong forms, surrogates or values past U+10FFFF. */
fun isValidUtf8(b: ByteArray, from: Int = 0, to: Int = b.size): Boolean {
  var i = from
  while (i < to) {
    val c = b[i].toInt() and 0xFF
    if (c < 0x80) {
      i++
      continue
    }
    val (n, min) =
        when {
          c in 0xC2..0xDF -> 1 to 0x80
          c in 0xE0..0xEF -> 2 to 0x800
          c in 0xF0..0xF4 -> 3 to 0x10000
          else -> return false
        }
    if (i + n >= to) return false
    var cp = c and (0x3F shr n)
    for (k in 1..n) {
      val cc = b[i + k].toInt() and 0xFF
      if (cc and 0xC0 != 0x80) return false
      cp = (cp shl 6) or (cc and 0x3F)
    }
    if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
    i += n + 1
  }
  return true
}
