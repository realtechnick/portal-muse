package dev.wobsoriano.museportal

import android.app.Application

class MuseApp : Application() {
  val controller by lazy { MuseController(this) }
}

val android.content.Context.muse: MuseController
  get() = (applicationContext as MuseApp).controller
