package dev.wobsoriano.museportal.heymuse

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      for ((name, body) in entries) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray())
        z.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private val model =
      zip("m/" to "", "m/am/final.mdl" to "acoustic", "m/conf/model.conf" to "conf", "m/graph/Gr.fst" to "graph")

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun store(bytes: ByteArray, sha256: String = sha(bytes)) =
      ModelStore(tmp.root, ModelSpec("m", "https://example.test/m.zip", sha256))

  private class Server(private val bytes: ByteArray, private val length: Long = bytes.size.toLong()) {
    var opened = 0

    fun open(url: String): Download {
      opened++
      return Download(ByteArrayInputStream(bytes), length)
    }
  }

  private fun leftovers() = tmp.root.list()!!.filter { it != "m" }

  @Test
  fun installsAVerifiedModelAndReportsProgress() {
    val server = Server(model)
    val progress = ArrayList<Float?>()
    val s = store(model)
    s.install(server::open, { false }) { progress += it }
    assertTrue(s.installed())
    assertEquals("acoustic", File(s.dir, "am/final.mdl").readText())
    assertEquals("graph", File(s.dir, "graph/Gr.fst").readText())
    assertEquals(0f, progress.first())
    assertEquals(1f, progress.last())
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test
  fun anInstalledModelIsNotDownloadedAgain() {
    val server = Server(model)
    val s = store(model)
    s.install(server::open, { false }) {}
    s.install(server::open, { false }) {}
    assertEquals(1, server.opened)
  }

  @Test
  fun aDamagedDownloadInstallsNothing() {
    val s = store(model, sha256 = "0".repeat(64))
    try {
      s.install(Server(model)::open, { false }) {}
      fail("installed a model with the wrong checksum")
    } catch (e: InstallFailed) {
      assertTrue(e.message!!, e.message!!.contains("checksum"))
    }
    assertFalse(s.installed())
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test
  fun anUnknownSizeReportsNullProgress() {
    val progress = ArrayList<Float?>()
    store(model).install(Server(model, length = -1)::open, { false }) { progress += it }
    assertEquals(listOf<Float?>(null), progress)
  }

  @Test
  fun aBrokenConnectionFailsCleanly() {
    val broken = object : InputStream() {
      var n = 0

      override fun read(): Int = if (n++ < 10) 0 else throw IOException("connection reset")
    }
    val s = store(model)
    try {
      s.install({ Download(broken, model.size.toLong()) }, { false }) {}
      fail("installed from a broken connection")
    } catch (e: InstallFailed) {
      assertTrue(e.message!!, e.message!!.contains("connection reset"))
    }
    assertFalse(s.installed())
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test
  fun leftoversOfACrashedRunAreReplaced() {
    File(tmp.root, "m.zip.part").writeText("half a zip")
    File(tmp.root, "m.tmp/am").mkdirs()
    File(tmp.root, "m.tmp/am/final.mdl").writeText("stale")
    val s = store(model)
    s.install(Server(model)::open, { false }) {}
    assertEquals("acoustic", File(s.dir, "am/final.mdl").readText())
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test
  fun cancellingInstallsNothing() {
    val s = store(model)
    try {
      s.install(Server(model)::open, { true }) {}
      fail("not cancelled")
    } catch (e: InstallCancelled) {}
    assertFalse(s.installed())
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test
  fun anEntryOutsideTheModelIsRefused() {
    val evil = zip("m/am/final.mdl" to "a", "m/conf/model.conf" to "c", "m/../../escape" to "x")
    val s = store(evil)
    try {
      s.install(Server(evil)::open, { false }) {}
      fail("unpacked outside the model")
    } catch (e: InstallFailed) {}
    assertFalse(s.installed())
    assertFalse(File(tmp.root.parentFile, "escape").exists())
  }

  @Test
  fun anArchiveThatIsNotAModelIsRefused() {
    val notModel = zip("m/README" to "hello")
    val s = store(notModel)
    try {
      s.install(Server(notModel)::open, { false }) {}
      fail("installed a zip without a model")
    } catch (e: InstallFailed) {}
    assertFalse(s.installed())
  }
}
