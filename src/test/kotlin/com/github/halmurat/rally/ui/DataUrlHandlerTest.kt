package com.github.halmurat.rally.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URL
import java.util.Base64

class DataUrlHandlerTest {

    private val bytes = ByteArray(300) { (it * 7).toByte() }
    private val base64 = Base64.getEncoder().encodeToString(bytes)

    private fun open(spec: String) = URL(null, spec, DataUrlHandler).openConnection()

    @Test
    fun `serves the decoded payload with the media type as content type`() {
        val connection = open("data:image/PNG;base64,$base64")
        assertEquals("image/png", connection.contentType)
        assertArrayEquals(bytes, connection.getInputStream().readBytes())
    }

    @Test
    fun `media type parameters before base64 are allowed`() {
        val connection = open("data:image/svg+xml;charset=utf-8;base64,$base64")
        assertEquals("image/svg+xml", connection.contentType)
        assertArrayEquals(bytes, connection.getInputStream().readBytes())
    }

    @Test
    fun `wrapped base64 with line breaks and spaces decodes`() {
        val wrapped = base64.chunked(20).joinToString("\r\n ")
        assertArrayEquals(bytes, open("data:image/png;base64,$wrapped").getInputStream().readBytes())
    }

    @Test
    fun `a missing media type reads as text-plain`() {
        assertEquals("text/plain", open("data:;base64,$base64").contentType)
    }

    @Test
    fun `non-base64 payloads are refused`() {
        assertThrowsIo { open("data:image/png,%89PNG").getInputStream() }
        assertThrowsIo { open("data:text/plain;charset=utf-8,hello").getInputStream() }
    }

    @Test
    fun `malformed data URLs are refused`() {
        assertThrowsIo { open("data:image/png;base64").getInputStream() }
        assertThrowsIo { open("data:image/png;base64,@@@=x").getInputStream() }
    }

    @Test
    fun `the URL has no host, so hashing or comparing it resolves nothing`() {
        val url = URL(null, "data:image/png;base64,$base64", DataUrlHandler)
        assertTrue(url.host.isNullOrEmpty())
        assertNull(url.query)
        assertEquals("data:image/png;base64,$base64", url.toExternalForm())
        assertEquals(url, URL(null, "data:image/png;base64,$base64", DataUrlHandler))
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
