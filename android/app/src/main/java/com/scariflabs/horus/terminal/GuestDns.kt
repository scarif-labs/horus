package com.scariflabs.horus.terminal

import android.net.DnsResolver
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The guest's DNS server, shared by every session in the `:terminal`
 * process. Queries go through Android's resolver on the default network, so
 * Private DNS and a VPN's DNS apply inside Linux as they do to apps, and a
 * switch from Wi-Fi to mobile data needs no resolver rewrite.
 */
internal object GuestDns {
  private var forwarder: GuestDnsForwarder? = null
  private var unavailable = false

  /**
   * The forwarder's port, starting it on first use, or null below Android 10
   * or when it couldn't start. A failure sticks for the process's lifetime so
   * every session agrees on where DNS goes.
   */
  @Synchronized
  fun port(onFailure: (Throwable) -> Unit = {}): Int? {
    forwarder?.let { return it.port }
    if (unavailable) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      unavailable = true
      return null
    }
    return try {
      GuestDnsForwarder(AndroidDnsUpstream()).start().also { forwarder = it }.port
    } catch (error: Exception) {
      unavailable = true
      onFailure(error)
      null
    }
  }

  /** Whether sessions resolve through the forwarder, without starting it. */
  @Synchronized
  fun isActive(): Boolean = forwarder != null
}

@RequiresApi(Build.VERSION_CODES.Q)
private class AndroidDnsUpstream : DnsUpstream {
  private val callbacks: Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "horus-dns-answer").apply { isDaemon = true }
  }

  override fun query(query: ByteArray, answer: (ByteArray?) -> Unit) {
    try {
      // A null network is the app's default network, a VPN included.
      DnsResolver.getInstance().rawQuery(
        null,
        query,
        DnsResolver.FLAG_EMPTY,
        callbacks,
        null,
        object : DnsResolver.Callback<ByteArray> {
          override fun onAnswer(response: ByteArray, rcode: Int) = answer(response)
          override fun onError(error: DnsResolver.DnsException) = answer(null)
        },
      )
    } catch (_: RuntimeException) {
      answer(null)
    }
  }
}
