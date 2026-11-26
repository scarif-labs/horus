package com.scariflabs.horus.terminal

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDnsForwarderTest {
  @Test
  fun udpReplyCarriesTheQueryIdAndTheAnswer() {
    val query = query(id = 0x1234)
    val answer = answer(query, records = 1).also { it[0] = 0; it[1] = 0 }

    val reply = DnsMessages.udpReply(query, answer)

    assertEquals(0x1234, short(reply, 0))
    assertArrayEquals(answer.copyOfRange(2, answer.size), reply.copyOfRange(2, reply.size))
  }

  @Test
  fun failedLookupIsServfailWithTheQuestion() {
    val query = query(id = 7, edns = 1232)

    val reply = DnsMessages.udpReply(query, null)

    assertEquals(7, short(reply, 0))
    assertEquals(0x81, reply[2].toInt() and 0xFF) // QR, RD kept
    assertEquals(0x82, reply[3].toInt() and 0xFF) // RA, SERVFAIL
    assertEquals(listOf(1, 0, 0, 0), listOf(short(reply, 4), short(reply, 6), short(reply, 8), short(reply, 10)))
    // The question, but not the query's OPT record.
    assertArrayEquals(query.copyOfRange(12, 12 + QUESTION_SIZE), reply.copyOfRange(12, reply.size))
  }

  @Test
  fun udpLimitFollowsTheClientsEdnsSize() {
    assertEquals(512, DnsMessages.udpLimit(query(id = 1)))
    assertEquals(1232, DnsMessages.udpLimit(query(id = 1, edns = 1232)))
    assertEquals(4096, DnsMessages.udpLimit(query(id = 1, edns = 65000)))
    assertEquals(512, DnsMessages.udpLimit(query(id = 1, edns = 100)))
  }

  @Test
  fun answerTooLongForUdpTellsTheClientToUseTcp() {
    val plain = query(id = 9)
    val long = answer(plain, records = 40)
    assertTrue(long.size > 512)

    val reply = DnsMessages.udpReply(plain, long)
    assertEquals(0x02, reply[2].toInt() and 0x02) // TC
    assertEquals(0, reply[3].toInt() and 0x0F) // NOERROR
    assertEquals(0, short(reply, 6))

    val edns = query(id = 9, edns = 4096)
    assertEquals(answer(edns, records = 40).size, DnsMessages.udpReply(edns, answer(edns, records = 40)).size)
  }

  @Test
  fun paddedAnswerLosesItsAdditionalSectionInsteadOfBeingTruncated() {
    val query = query(id = 5)
    val answer = answer(query, records = 2)
    val padded = ByteArrayOutputStream().apply {
      write(answer)
      // OPT record with 600 bytes of EDNS padding.
      write(0)
      writeShort(41)
      writeShort(1232)
      write(ByteArray(4))
      writeShort(604)
      writeShort(12)
      writeShort(600)
      write(ByteArray(600))
    }.toByteArray().also { it[11] = 1 }
    assertTrue(padded.size > 512)

    val reply = DnsMessages.udpReply(query, padded)

    assertEquals(0, reply[2].toInt() and 0x02) // not truncated
    assertEquals(2, short(reply, 6))
    assertEquals(0, short(reply, 10))
    assertArrayEquals(answer.copyOfRange(2, answer.size), reply.copyOfRange(2, reply.size))
  }

  @Test
  fun answersOverUdp() {
    GuestDnsForwarder { query, answer -> answer(answer(query, records = 1).also { it[0] = 0; it[1] = 0 }) }.start().use { forwarder ->
      DatagramSocket().use { client ->
        client.soTimeout = 5_000
        val query = query(id = 0x4242)
        client.send(DatagramPacket(query, query.size, InetSocketAddress(GuestDnsForwarder.LOOPBACK, forwarder.port)))
        val packet = DatagramPacket(ByteArray(4096), 4096)
        client.receive(packet)

        assertEquals(0x4242, short(packet.data, 0))
        assertEquals(1, short(packet.data, 6))
      }
    }
  }

  @Test
  fun failedLookupOverUdpIsServfail() {
    GuestDnsForwarder { _, answer -> answer(null) }.start().use { forwarder ->
      DatagramSocket().use { client ->
        client.soTimeout = 5_000
        val query = query(id = 3)
        client.send(DatagramPacket(query, query.size, InetSocketAddress(GuestDnsForwarder.LOOPBACK, forwarder.port)))
        val packet = DatagramPacket(ByteArray(4096), 4096)
        client.receive(packet)

        assertEquals(2, packet.data[3].toInt() and 0x0F)
      }
    }
  }

  @Test
  fun answersOverTcpOnTheSamePort() {
    GuestDnsForwarder { query, answer -> answer(answer(query, records = 40)) }.start().use { forwarder ->
      Socket(GuestDnsForwarder.LOOPBACK, forwarder.port).use { socket ->
        socket.soTimeout = 5_000
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        repeat(2) { index ->
          val query = query(id = 100 + index)
          output.writeShort(query.size)
          output.write(query)
          output.flush()
          val reply = ByteArray(input.readUnsignedShort()).also(input::readFully)

          assertEquals(100 + index, short(reply, 0))
          assertEquals(40, short(reply, 6))
        }
      }
    }
  }

  private fun query(id: Int, edns: Int? = null): ByteArray = ByteArrayOutputStream().apply {
    writeShort(id)
    writeShort(0x0100) // RD
    writeShort(1)
    writeShort(0)
    writeShort(0)
    writeShort(if (edns != null) 1 else 0)
    write(QUESTION)
    if (edns != null) {
      write(0)
      writeShort(41)
      writeShort(edns)
      write(ByteArray(4))
      writeShort(0)
    }
  }.toByteArray()

  /** [query]'s header and question with [records] A records. */
  private fun answer(query: ByteArray, records: Int): ByteArray = ByteArrayOutputStream().apply {
    write(query, 0, 2)
    writeShort(0x8180)
    writeShort(1)
    writeShort(records)
    writeShort(0)
    writeShort(0)
    write(QUESTION)
    repeat(records) { index ->
      writeShort(0xC00C)
      writeShort(1)
      writeShort(1)
      write(byteArrayOf(0, 0, 1, 0))
      writeShort(4)
      write(byteArrayOf(192.toByte(), 0, 2, index.toByte()))
    }
  }.toByteArray()

  private fun ByteArrayOutputStream.writeShort(value: Int) {
    write(value shr 8 and 0xFF)
    write(value and 0xFF)
  }

  private fun short(message: ByteArray, offset: Int): Int =
    (message[offset].toInt() and 0xFF shl 8) or (message[offset + 1].toInt() and 0xFF)

  private companion object {
    // example.com, type A, class IN
    val QUESTION = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
    val QUESTION_SIZE = QUESTION.size
  }
}
