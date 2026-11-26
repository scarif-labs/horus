package com.scariflabs.horus.terminal

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resolves one raw DNS query; [answer] runs once, with null when the lookup failed. */
internal fun interface DnsUpstream {
  fun query(query: ByteArray, answer: (ByteArray?) -> Unit)
}

/**
 * A DNS server on 127.0.0.1 that answers the guest's queries through
 * [upstream]. An app can't listen on port 53, so it takes a free port for
 * both UDP and TCP, and PRoot's `--dns-redirect` sends the guest's queries
 * for 127.0.0.1:53 there.
 */
internal class GuestDnsForwarder(private val upstream: DnsUpstream) : Closeable {
  private val udp: DatagramSocket
  private val tcp: ServerSocket
  private val connections: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "horus-dns-tcp").apply { isDaemon = true }
  }
  @Volatile private var closed = false

  val port: Int

  init {
    var bound: Pair<DatagramSocket, ServerSocket>? = null
    for (attempt in 1..BIND_ATTEMPTS) {
      val datagram = DatagramSocket(InetSocketAddress(LOOPBACK, 0))
      try {
        bound = datagram to ServerSocket(datagram.localPort, TCP_BACKLOG, LOOPBACK)
        break
      } catch (error: IOException) {
        datagram.close()
        if (attempt == BIND_ATTEMPTS) throw error
      }
    }
    udp = bound!!.first
    tcp = bound.second
    port = udp.localPort
  }

  fun start(): GuestDnsForwarder {
    thread("horus-dns-udp", ::serveUdp)
    thread("horus-dns-accept", ::acceptTcp)
    return this
  }

  override fun close() {
    closed = true
    runCatching { udp.close() }
    runCatching { tcp.close() }
    connections.shutdownNow()
  }

  private fun serveUdp() {
    val buffer = ByteArray(MAX_MESSAGE)
    while (!closed) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        udp.receive(packet)
      } catch (_: IOException) {
        continue
      }
      val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
      val client = packet.socketAddress
      if (!DnsMessages.isQuery(query)) continue
      upstream.query(query) { answer ->
        val reply = DnsMessages.udpReply(query, answer)
        runCatching { udp.send(DatagramPacket(reply, reply.size, client)) }
      }
    }
  }

  private fun acceptTcp() {
    while (!closed) {
      val socket = try {
        tcp.accept()
      } catch (_: IOException) {
        continue
      }
      runCatching { connections.execute { serveTcp(socket) } }.onFailure { socket.close() }
    }
  }

  /** DNS over TCP: each message is preceded by its length. */
  private fun serveTcp(socket: Socket) = socket.use {
    socket.soTimeout = TCP_IDLE_MS
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    while (!closed) {
      val query = try {
        ByteArray(input.readUnsignedShort()).also(input::readFully)
      } catch (_: IOException) {
        return@use
      }
      if (!DnsMessages.isQuery(query)) return@use
      val answer = CompletableFuture<ByteArray?>()
      upstream.query(query) { answer.complete(it) }
      val reply = DnsMessages.tcpReply(query, runCatching { answer.get(UPSTREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull())
      try {
        output.writeShort(reply.size)
        output.write(reply)
        output.flush()
      } catch (_: IOException) {
        return@use
      }
    }
  }

  private fun thread(name: String, body: () -> Unit) {
    Thread(body, name).apply { isDaemon = true }.start()
  }

  companion object {
    val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private const val BIND_ATTEMPTS = 8
    private const val TCP_BACKLOG = 16
    private const val TCP_IDLE_MS = 10_000
    private const val UPSTREAM_TIMEOUT_MS = 15_000L
    private const val MAX_MESSAGE = 65_535
  }
}

/** Just enough of the DNS wire format to answer queries on the guest's behalf. */
internal object DnsMessages {
  private const val HEADER = 12
  private const val MIN_UDP = 512
  private const val MAX_UDP = 4096
  private const val TYPE_OPT = 41
  private const val RCODE_SERVFAIL = 2

  fun isQuery(message: ByteArray): Boolean = message.size >= HEADER && message[2].toInt() and 0x80 == 0

  /**
   * [answer] with the query's ID. One too long for the client loses its
   * additional section first (resolvers like Cloudflare's pad answers
   * there); if it still doesn't fit, the client is told to retry over TCP.
   */
  fun udpReply(query: ByteArray, answer: ByteArray?): ByteArray {
    if (answer == null || answer.size < HEADER) return failure(query)
    val limit = udpLimit(query)
    val fitting = if (answer.size <= limit) answer else withoutAdditional(answer)?.takeIf { it.size <= limit }
    return if (fitting != null) withId(fitting, query) else truncated(query)
  }

  fun tcpReply(query: ByteArray, answer: ByteArray?): ByteArray = when {
    answer == null || answer.size < HEADER || answer.size > 0xFFFF -> failure(query)
    else -> withId(answer, query)
  }

  /** SERVFAIL for [query], with its question. */
  fun failure(query: ByteArray): ByteArray = replyHeaderAndQuestion(query, truncated = false, rcode = RCODE_SERVFAIL)

  /** An empty answer with TC set, which makes the client ask again over TCP. */
  fun truncated(query: ByteArray): ByteArray = replyHeaderAndQuestion(query, truncated = true, rcode = 0)

  /** The largest UDP reply the client accepts: 512 bytes, or what its EDNS OPT record allows. */
  fun udpLimit(query: ByteArray): Int {
    if (count(query, 6) != 0 || count(query, 8) != 0 || count(query, 10) == 0) return MIN_UDP
    var offset = questionsEnd(query) ?: return MIN_UDP
    offset = skipName(query, offset) ?: return MIN_UDP
    if (offset + 4 > query.size || count(query, offset) != TYPE_OPT) return MIN_UDP
    return count(query, offset + 2).coerceIn(MIN_UDP, MAX_UDP)
  }

  /** [message] without its additional section, or null when it doesn't parse. */
  fun withoutAdditional(message: ByteArray): ByteArray? {
    var offset = questionsEnd(message) ?: return null
    repeat(count(message, 6) + count(message, 8)) {
      offset = skipName(message, offset) ?: return null
      // Type, class, TTL, then the data's length.
      if (offset + 10 > message.size) return null
      offset += 10 + count(message, offset + 8)
      if (offset > message.size) return null
    }
    return message.copyOf(offset).also {
      it[10] = 0
      it[11] = 0
    }
  }

  private fun replyHeaderAndQuestion(query: ByteArray, truncated: Boolean, rcode: Int): ByteArray {
    val end = questionsEnd(query)
    val reply = query.copyOf(end ?: HEADER)
    val opcodeAndRd = query[2].toInt() and 0x79
    reply[2] = (0x80 or opcodeAndRd or (if (truncated) 0x02 else 0)).toByte()
    reply[3] = (0x80 or rcode).toByte()
    if (end == null) {
      reply[4] = 0
      reply[5] = 0
    }
    for (index in 6 until HEADER) reply[index] = 0
    return reply
  }

  private fun withId(answer: ByteArray, query: ByteArray): ByteArray = answer.copyOf().also {
    it[0] = query[0]
    it[1] = query[1]
  }

  /** The offset just past the question section, or null when it doesn't parse. */
  private fun questionsEnd(message: ByteArray): Int? {
    var offset = HEADER
    repeat(count(message, 4)) {
      offset = skipName(message, offset) ?: return null
      offset += 4
      if (offset > message.size) return null
    }
    return offset
  }

  private fun skipName(message: ByteArray, start: Int): Int? {
    var offset = start
    while (offset < message.size) {
      val length = message[offset].toInt() and 0xFF
      when {
        length == 0 -> return offset + 1
        length and 0xC0 == 0xC0 -> return (offset + 2).takeIf { it <= message.size }
        length and 0xC0 != 0 -> return null
        else -> offset += length + 1
      }
    }
    return null
  }

  private fun count(message: ByteArray, offset: Int): Int =
    if (offset + 1 < message.size) (message[offset].toInt() and 0xFF shl 8) or (message[offset + 1].toInt() and 0xFF) else 0
}
