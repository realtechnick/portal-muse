package dev.wobsoriano.museportal

import android.app.Application
import dev.wobsoriano.museportal.avatar.AvatarStore

class MuseApp : Application() {
  val controller by lazy { MuseController(this) }
  val avatars by lazy { AvatarStore(this).also { it.reload() } }
}

val android.content.Context.muse: MuseController
  get() = (applicationContext as MuseApp).controller

val android.content.Context.avatars: AvatarStore
  get() = (applicationContext as MuseApp).avatars
