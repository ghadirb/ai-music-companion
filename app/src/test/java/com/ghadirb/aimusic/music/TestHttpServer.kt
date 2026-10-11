package com.ghadirb.aimusic.music

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny real HTTP/1.1 server for JVM unit tests (the JDK's com.sun HttpServer is not on the Android unit-test
 * compile classpath). Same shape as the JDK one for the parts the tests use.
 */
class TestHttpServer {
    class Exchange(private val socket: Socket) {
        val responseHeaders = LinkedHashMap<String, MutableList<String>>().let { HeaderMap(it) }
        private var out: OutputStream? = null
        val responseBody: OutputStream get() = out ?: error("sendResponseHeaders first")

        /** [length] = -1 means no body; any other value is sent as Content-Length (it may deliberately lie). */
        fun sendResponseHeaders(status: Int, length: Long) {
            val o = socket.getOutputStream()
            val head = StringBuilder("HTTP/1.1 $status X\r\n")
            responseHeaders.map.forEach { (k, v) -> v.forEach { head.append(k).append(": ").append(it).append("\r\n") } }
            head.append("Content-Length: ").append(if (length < 0) 0 else length).append("\r\nConnection: close\r\n\r\n")
            o.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            o.flush()
            out = o
        }

        fun close() { runCatching { socket.getOutputStream().flush() }; runCatching { socket.close() } }
    }

    class HeaderMap(val map: LinkedHashMap<String, MutableList<String>>) {
        fun add(name: String, value: String) { map.getOrPut(name) { ArrayList() }.add(value) }
    }

    private val handlers = HashMap<String, (Exchange) -> Unit>()
    private lateinit var socket: ServerSocket
    @Volatile private var running = false
    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getByName("127.0.0.1"), socket.localPort)

    init { socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) }

    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }

    fun start() {
        running = true
        thread(isDaemon = true, name = "test-http-accept") {
            while (running) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true, name = "test-http-conn") { handle(s) }
            }
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int = 0) { running = false; runCatching { socket.close() } }

    private fun handle(s: Socket) {
        try {
            val input = s.getInputStream()
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (true) { // read up to the blank line that ends the request head
                val b = input.read()
                if (b < 0) break
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            val first = buf.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
            val path = first.split(' ').getOrNull(1).orEmpty().substringBefore('?')
            val handler = handlers[path]
            val ex = Exchange(s)
            if (handler == null) { ex.sendResponseHeaders(404, -1); ex.close() } else handler(ex)
        } catch (_: Exception) {
            runCatching { s.close() }
        }
    }
}
