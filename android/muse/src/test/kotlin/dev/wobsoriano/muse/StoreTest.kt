package dev.wobsoriano.muse

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun fileStoreSavesWholeAndPrivate() = runBlocking {
    val dir = tmp.newFolder()
    val store = FileStore(File(dir, "state.json"))
    assertFails("Load of a missing file succeeded") { store.read() }
    val first = State(identity = Identity("02:11:22:a1:b2:c3"), credentials = Credentials(accessToken = "one"))
    val second = first.copy(credentials = Credentials(accessToken = "two", refreshToken = "r"), sdkTokenReported = true)
    for (st in listOf(first, second)) {
      store.save(st)
      assertEquals(st, store.load())
    }
    val posix = runCatching { Files.getPosixFilePermissions(store.file.toPath()) }.getOrNull()
    if (posix != null) assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), posix)
    assertEquals("left behind: ${dir.list()?.toList()}", 1, dir.list()?.size)
    // A save that cannot land leaves the old file as it was.
    assertFails("Save into a missing directory succeeded") { FileStore(File(dir, "missing/state.json")).write(first) }
    assertEquals(second, store.load())
  }

  // The device was paired by musecore, whose FileStore wrote muse.json; after the update the Kotlin
  // store reads that file, so the device stays paired. The fixture is what Go wrote (fake values).
  @Test
  fun readsTheStateFileMusecoreWrote() = runBlocking {
    val dir = tmp.newFolder()
    val file = File(dir, "muse.json")
    file.writeText(Fixtures.text("muse-go-paired.json"))
    val want =
        State(
            identity = Identity("02:11:22:a1:b2:c3"),
            credentials =
                Credentials(
                    accessToken = "fake-access-token",
                    refreshToken = "hatch_refresh:fake-refresh-token",
                    tokenType = "device",
                    username = "someone@example.com",
                    apiUrl = "https://legacy.example",
                    apiUrlV2 = "https://api.example",
                    noiseHost = "noise.example",
                    accessTokenSavedAt = 1760000000),
            sdkTokenReported = true)
    val store = FileStore(file)
    assertEquals(want, store.load())
    // What the Kotlin store writes back is byte for byte what Go would have written.
    store.save(want)
    assertEquals(Fixtures.text("muse-go-paired.json"), file.readText())

    file.writeText(Fixtures.text("muse-go-unpaired.json"))
    val unpaired = store.load()
    assertNull(unpaired.credentials)
    assertEquals(Identity("02:11:22:a1:b2:c3"), unpaired.identity)
    store.save(unpaired)
    assertEquals(Fixtures.text("muse-go-unpaired.json"), file.readText())
  }

  @Test
  fun fieldsGoWouldIgnoreOrZeroDoNotBreakTheLoad() = runBlocking {
    val file = File(tmp.newFolder(), "muse.json")
    file.writeText("""{"identity":{"mac":"02:11:22:a1:b2:c3","extra":1},"credentials":{"access_token":"a","username":null},"future":true}""")
    assertEquals(
        State(identity = Identity("02:11:22:a1:b2:c3"), credentials = Credentials(accessToken = "a")),
        FileStore(file).load())
  }
}
