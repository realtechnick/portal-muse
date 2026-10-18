package dev.wobsoriano.muse.pairing

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VerifyTest {
  private class Tls {
    val root = HeldCertificate.Builder().certificateAuthority(0).build()
    val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("127.0.0.1").build()
    val server = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
    val client = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()

    fun http() = OkHttpClient.Builder().sslSocketFactory(client.sslSocketFactory(), client.trustManager).build()
  }

  private val tls = Tls()

  private fun verify(srv: MockWebServer, apiUrlV2: String, check: TokenCheck): Throwable? =
      runCatching { runBlocking { check.verify(Result("device-access", "", "", "", "", apiUrlV2, "")) } }.exceptionOrNull()

  @Test
  fun tokenCheck() {
    for ((name, tc) in
        mapOf(
            "a computer" to Triple(200, """{"vm_list":[{"vm_ws_url":"wss://vm.example","vm_auth_token":"t"}]}""", true),
            "older url field" to Triple(200, """{"vm_list":[{"vm_url":"wss://vm.example","vm_auth_token":"t"}]}""", true),
            "junk entries first" to Triple(200, """{"vm_list":[7,{"vm_url":3},{"vm_url":"wss://vm.example","vm_auth_token":"t"}]}""", true),
            "no computers" to Triple(200, """{"vm_list":[]}""", false),
            "computer, no token" to Triple(200, """{"vm_list":[{"vm_ws_url":"wss://vm.example"}]}""", false),
            "error in a 200" to Triple(200, """{"error_title":"Nope","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", false),
            "backend error code" to Triple(200, """{"backend_error_code":42,"vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", false),
            "rejected" to Triple(401, "{}", false),
            "not json" to Triple(200, "<html>", false),
            "not an object" to Triple(200, "[]", false),
            "vm_list not a list" to Triple(200, """{"vm_list":"x"}""", false),
            "server error" to Triple(503, """{"vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", false),
            "empty error is fine" to Triple(200, """{"error_title":"","vm_list":[{"vm_url":"u","vm_auth_token":"t"}]}""", true),
        )) {
      val srv = MockWebServer()
      srv.useHttps(tls.server.sslSocketFactory(), false)
      srv.enqueue(MockResponse().setResponseCode(tc.first).setBody(tc.second))
      srv.start(InetAddress.getByName("127.0.0.1"), 0)
      try {
        val err = verify(srv, "https://127.0.0.1:${srv.port}/", TokenCheck(tls.http(), "echod/test"))
        assertEquals("$name: Verify = $err", tc.third, err == null)
        if (err != null) assertFalse("$name: the error carries the token: $err", err.message.orEmpty().contains("device-access"))
        val got = srv.takeRequest()
        assertEquals(name, "GET", got.method)
        assertEquals(name, "/fetch_vms", got.requestUrl?.encodedPath)
        assertEquals(name, "Bearer device-access", got.getHeader("Authorization"))
        assertEquals(name, "1.0.0", got.getHeader("X-API-Version"))
        assertEquals(name, "echod/test", got.getHeader("User-Agent"))
      } finally {
        srv.shutdown()
      }
    }
  }

  @Test
  fun tokenCheckWhenMuseCannotBeReached() {
    val srv = MockWebServer()
    srv.useHttps(tls.server.sslSocketFactory(), false)
    srv.start(InetAddress.getByName("127.0.0.1"), 0)
    val url = "https://127.0.0.1:${srv.port}"
    srv.shutdown()
    val err = verify(srv, url, TokenCheck(tls.http()))
    assertEquals("Verify passed with nobody listening", true, err != null)
  }
}
