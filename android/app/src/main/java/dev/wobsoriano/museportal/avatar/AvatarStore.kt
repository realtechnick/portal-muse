package dev.wobsoriano.museportal.avatar

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a set came from: the user's own folder wins over the one built into the app. */
enum class AvatarSource {
  Yours,
  BuiltIn,
}

class AvatarSet(val manifest: AvatarManifest, val sheets: Map<String, ImageBitmap>, val source: AvatarSource)

sealed interface AvatarLoad {
  data object Loading : AvatarLoad

  /** No set could be read, and why; the orb stands in. */
  data class Unavailable(val reason: String) : AvatarLoad

  class Loaded(val set: AvatarSet) : AvatarLoad
}

/**
 * The character set: a folder made with hey-muse's tools/muse-avatar in the app's external files
 * (Android/data/<package>/files/muse-avatar), or else the one in the app's assets.
 */
class AvatarStore(private val context: Context) {
  val dir: File? = context.getExternalFilesDir(null)?.let { File(it, DIR) }

  private val _load = MutableStateFlow<AvatarLoad>(AvatarLoad.Loading)
  val load: StateFlow<AvatarLoad> = _load.asStateFlow()

  /** For screenshots of the stand-in: no set is loaded until the next [reload]. */
  fun hide() {
    _load.value = AvatarLoad.Unavailable("hidden for a preview")
  }

  fun reload() {
    thread(name = "muse-avatar") {
      val yours = dir?.takeIf { File(it, MANIFEST).isFile }
      val result =
          if (yours != null) {
            read(AvatarSource.Yours) { name -> File(yours, name).inputStream() }
          } else {
            read(AvatarSource.BuiltIn) { name -> context.assets.open("$DIR/$name") }
          }
      when (result) {
        is AvatarLoad.Loaded -> Log.i(TAG, "Muse avatar loaded (${result.set.source}): ${result.set.manifest.cell} px at ${result.set.manifest.fps} fps")
        is AvatarLoad.Unavailable -> Log.w(TAG, "no Muse avatar: ${result.reason}")
        AvatarLoad.Loading -> Unit
      }
      _load.value = result
    }
  }

  private fun read(source: AvatarSource, open: (String) -> InputStream): AvatarLoad {
    val text =
        try {
          open(MANIFEST).use { it.readBytes() }
        } catch (e: IOException) {
          return AvatarLoad.Unavailable("no $MANIFEST")
        }
    if (text.size > 64 * 1024) return AvatarLoad.Unavailable("$MANIFEST is too large")
    val manifest = AvatarManifest.parse(String(text)).getOrElse { return AvatarLoad.Unavailable(it.message ?: "unreadable $MANIFEST") }
    val sheets =
        manifest.animations.mapValues { (name, a) ->
          val bitmap =
              try {
                open("$name.png").use { BitmapFactory.decodeStream(it) }
              } catch (e: IOException) {
                null
              } catch (e: OutOfMemoryError) {
                null
              } ?: return AvatarLoad.Unavailable("$name.png would not load")
          val w = a.width(manifest.cell)
          val h = a.height(manifest.cell)
          if (bitmap.width != w || bitmap.height != h) return AvatarLoad.Unavailable("$name.png is ${bitmap.width}x${bitmap.height}, not ${w}x$h")
          bitmap.asImageBitmap()
        }
    return AvatarLoad.Loaded(AvatarSet(manifest, sheets, source))
  }

  private companion object {
    const val TAG = "MuseAvatar"
    const val DIR = "muse-avatar"
    const val MANIFEST = "manifest.json"
  }
}
