package com.scariflabs.horus.terminal

/**
 * Lets one toolchain install run at a time. A request for the target that is
 * already installing waits for that run and gets its result: after Android
 * restarts the UI mid-install, onboarding asks again while the service is
 * still working, and that used to fail with install_in_progress.
 */
internal class ProvisionGate<W> {
  enum class Admission { STARTED, JOINED, BUSY }

  private var target: String? = null
  private val waiters = mutableListOf<W>()

  val busy: Boolean
    @Synchronized get() = target != null

  @Synchronized
  fun admit(target: String, waiter: W): Admission {
    val running = this.target
    if (running != null && running != target) return Admission.BUSY
    waiters += waiter
    if (running != null) return Admission.JOINED
    this.target = target
    return Admission.STARTED
  }

  /** Ends the run and hands back everyone waiting on it, the first requester first. */
  @Synchronized
  fun finish(): List<W> {
    target = null
    return waiters.toList().also { waiters.clear() }
  }

  companion object {
    // Debian's first tools install under PRoot can pass five minutes on a
    // slow phone, and stopping it mid-dpkg left installs failing after.
    const val RUN_TIMEOUT_MS = 900_000L

    // The app must outwait the run, its bounded teardown, and a request that
    // joined just before the run ended. It gave up at 310 s before, so a
    // retry found the first install still running.
    const val REPLY_TIMEOUT_MS = RUN_TIMEOUT_MS + 60_000L
  }
}
