package com.scariflabs.horus.terminal

/** Bindings retain an instance, but only durable work justifies sticky/foreground life. */
object TerminalServiceLifecycle {
  data class Work(
    val sessions: Boolean = false,
    val provisioning: Boolean = false,
    val remoteAccess: Boolean = false,
  ) {
    val durable: Boolean get() = sessions || provisioning || remoteAccess
  }
}
