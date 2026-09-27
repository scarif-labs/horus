/*
 * App-owned PTY helper for the Alpine terminal runtime.
 *
 * This helper is intentionally app-owned and built from the Android NDK. It
 * provides a real controlling terminal: the child calls setsid(),
 * opens the pty slave, and explicitly claims it as the controlling terminal,
 * so ISIG/VINTR, TIOCSWINSZ, TIOCSPGRP, and SIGWINCH behave like a real
 * terminal.
 *
 * The helper is pure C and returns errno codes instead of logging; the Kotlin
 * supervisor owns every policy decision (process-group signalling, escalation,
 * and reaping boundaries).
 */

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Matches the Kotlin reader's chunk size; stack-allocated per call. */
#define PTY_IO_CHUNK_BYTES 8192

static char *copy_jstring(JNIEnv *env, jobject array, jsize index);
static jint encode_wait_status(int status);
static void free_strings(char **values);

/*
 * ART may install SIGCHLD handlers (and, on some Android releases, set
 * SA_NOCLDWAIT) in the process that loads this library.  PRoot is itself a
 * ptrace supervisor and requires ordinary wait semantics for its traced
 * children; inheriting those dispositions can wedge a fork/ptrace pipeline
 * after the first child.  Reset every catchable disposition before exec so
 * the guest launcher starts from a normal POSIX signal environment.
 */
static void reset_signal_dispositions(void) {
  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_handler = SIG_DFL;
  sigemptyset(&action.sa_mask);
  action.sa_flags = 0;
  for (int signal_number = 1; signal_number < NSIG; signal_number++) {
    if (signal_number == SIGKILL || signal_number == SIGSTOP) continue;
    (void)sigaction(signal_number, &action, NULL);
  }
}

/*
 * Creates the subprocess on a fresh pty.
 *
 * argv[0] is the executable path; envp is the complete child environment
 * (callers build it from scratch — no Android or host-shell values leak in);
 * cwd may be NULL for the current directory. rows/cols seed the pty window
 * size before exec so the guest never observes a zero-sized terminal.
 *
 * Returns {pid, master_fd}; on failure returns {-(errno), -1}. The child
 * exits with 127 if execve fails and 126 if the pty slave cannot be opened,
 * matching shell conventions.
 */
JNIEXPORT jintArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_createSubprocess(
    JNIEnv *env, jclass clazz, jobjectArray argv, jstring cwd,
    jobjectArray envp, jint rows, jint cols) {
  (void)clazz;
  jintArray result = (*env)->NewIntArray(env, 2);
  if (result == NULL) return NULL;

  jint failure[2] = {-1, -1};
  if (argv == NULL || envp == NULL || (*env)->GetArrayLength(env, argv) == 0 ||
      rows <= 0 || cols <= 0) {
    failure[0] = -(jint)EINVAL;
    (*env)->SetIntArrayRegion(env, result, 0, 2, failure);
    return result;
  }

  const jsize argc = (*env)->GetArrayLength(env, argv);
  const jsize envc = (*env)->GetArrayLength(env, envp);
  char **child_argv = calloc((size_t)argc + 1, sizeof(char *));
  char **child_envp = calloc((size_t)envc + 1, sizeof(char *));
  if (child_argv == NULL || child_envp == NULL) {
    free_strings(child_argv);
    free_strings(child_envp);
    failure[0] = -(jint)ENOMEM;
    (*env)->SetIntArrayRegion(env, result, 0, 2, failure);
    return result;
  }

  for (jsize i = 0; i < argc; i++) {
    child_argv[i] = copy_jstring(env, (jobject)argv, i);
    if (child_argv[i] == NULL) goto oom;
  }
  for (jsize i = 0; i < envc; i++) {
    child_envp[i] = copy_jstring(env, (jobject)envp, i);
    if (child_envp[i] == NULL) goto oom;
  }

  {
    const char *cwd_utf =
        cwd != NULL ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;
    struct winsize size = {.ws_row = (unsigned short)rows,
                           .ws_col = (unsigned short)cols,
                           .ws_xpixel = 0,
                           .ws_ypixel = 0};
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (master_fd < 0) {
      failure[0] = -(jint)errno;
    } else if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
      failure[0] = -(jint)errno;
      close(master_fd);
    } else {
      char slave_name[64];
      pid_t pid;
      if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        failure[0] = -(jint)errno;
        close(master_fd);
      } else if ((pid = fork()) < 0) {
        failure[0] = -(jint)errno;
        close(master_fd);
      } else if (pid == 0) {
        /* Child: only async-signal-suitable calls from here to execve. */
        int slave_fd;
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        /* ART handlers/ignores must not survive into PRoot. */
        reset_signal_dispositions();
        setsid();
        slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) _exit(126);
        /* Opening the slave after setsid(), followed by TIOCSCTTY below,
         * makes it the controlling terminal of this session. That is what
         * makes ISIG, job control, and SIGWINCH behave like a real terminal. */
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        close(master_fd);
        if (ioctl(STDIN_FILENO, TIOCSCTTY, 0) != 0) _exit(124);
        ioctl(STDIN_FILENO, TIOCSWINSZ, &size);
        if (cwd_utf != NULL && chdir(cwd_utf) != 0) _exit(125);
        /* Do not let Android/ART descriptors leak into PRoot. A leaked
         * writer can keep the master readable after the shell exits and a
         * leaked app descriptor can keep unrelated resources alive. The
         * launcher only needs stdio after the dup2 calls above. */
        for (int fd = STDERR_FILENO + 1; fd < 65536; fd++) close(fd);
        execve(child_argv[0], child_argv, child_envp);
        _exit(127);
      } else {
        jint ok[2];
        /* The master never leaves this process by a later exec. */
        fcntl(master_fd, F_SETFD, FD_CLOEXEC);
        /* Keep all bridge I/O deadline-driven. poll() below prevents the
         * usual wait for readiness; O_NONBLOCK closes the race where the
         * available bytes are consumed between poll and read/write. */
        int master_flags = fcntl(master_fd, F_GETFL, 0);
        if (master_flags >= 0) {
          (void)fcntl(master_fd, F_SETFL, master_flags | O_NONBLOCK);
        }
        /* Seed the size from the master side as well so the value is
         * observable even if the guest probes before its own ioctl lands. */
        ioctl(master_fd, TIOCSWINSZ, &size);
        ok[0] = (jint)pid;
        ok[1] = (jint)master_fd;
        (*env)->SetIntArrayRegion(env, result, 0, 2, ok);
        if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
        free_strings(child_argv);
        free_strings(child_envp);
        return result;
      }
    }
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
  }

  free_strings(child_argv);
  free_strings(child_envp);
  (*env)->SetIntArrayRegion(env, result, 0, 2, failure);
  return result;

oom:
  free_strings(child_argv);
  free_strings(child_envp);
  failure[0] = -(jint)ENOMEM;
  (*env)->SetIntArrayRegion(env, result, 0, 2, failure);
  return result;
}

/* Updates the pty window size (TIOCSWINSZ); returns 0 or -(errno). */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_setWindowSize(JNIEnv *env,
                                                        jclass clazz,
                                                        jint master_fd,
                                                        jint rows, jint cols) {
  (void)env;
  (void)clazz;
  struct winsize size = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = 0,
                         .ws_ypixel = 0};
  return ioctl(master_fd, TIOCSWINSZ, &size) == 0 ? 0 : -(jint)errno;
}

/*
 * Blocking waitpid for one direct child. The returned status is decoded by
 * the Kotlin contract: WIFEXITED → exit code 0..255, WIFSIGNALED → the
 * terminating signal as a negative value. Returns -(1000 + errno) on
 * failure, outside the signal range.
 */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_waitFor(JNIEnv *env, jclass clazz,
                                                  jint pid) {
  (void)env;
  (void)clazz;
  int status = 0;
  pid_t waited;
  do {
    waited = waitpid((pid_t)pid, &status, 0);
  } while (waited < 0 && errno == EINTR);
  /* Keep errno failures outside the negative signal range.  In particular,
   * ECHILD (-10) must never be decoded as SIGUSR1 by the Kotlin contract. */
  if (waited < 0) return -(jint)(1000 + errno);
  return encode_wait_status(status);
}

/* Closes a descriptor; returns 0 or -(errno). */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_closeFd(JNIEnv *env, jclass clazz,
                                                  jint fd) {
  (void)env;
  (void)clazz;
  return close(fd) == 0 ? 0 : -(jint)errno;
}

/*
 * Polls the pty master for at most 100ms, then reads. Returns the byte count
 * (>= 0), 0 on a poll timeout, -1 on EOF (EIO after every slave descriptor
 * closed), or -(errno) on failure.
 */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_readMaster(JNIEnv *env, jclass clazz,
                                                     jint fd,
                                                     jbyteArray buffer) {
  (void)clazz;
  const jsize capacity = buffer != NULL ? (*env)->GetArrayLength(env, buffer) : 0;
  if (capacity == 0) return -(jint)EINVAL;
  /* Poll before touching the Java array: an idle 100ms timeout then costs no
   * array copy, and a read copies back only the bytes it produced. */
  struct pollfd poll_fd = {.fd = fd, .events = POLLIN, .revents = 0};
  int ready;
  do {
    ready = poll(&poll_fd, 1, 100);
  } while (ready < 0 && errno == EINTR);
  if (ready == 0) return 0;
  if (ready < 0 || (poll_fd.revents & (POLLERR | POLLNVAL)) != 0) {
    const int error = ready < 0 ? errno : (poll_fd.revents & POLLNVAL) ? EBADF : EIO;
    return error == EIO ? -1 : -(jint)error;
  }
  char chunk[PTY_IO_CHUNK_BYTES];
  const size_t wanted = (size_t)capacity < sizeof(chunk) ? (size_t)capacity : sizeof(chunk);
  ssize_t got;
  do {
    got = read(fd, chunk, wanted);
  } while (got < 0 && errno == EINTR);
  if (got == 0) return -1; /* EOF: every writer on the slave side is gone */
  if (got < 0) {
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
    return errno == EIO ? -1 : -(jint)errno;
  }
  (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)got, (const jbyte *)chunk);
  return (jint)got;
}

/*
 * Write on the pty master. Returns the byte count accepted (>= 0) or
 * -(errno); the caller loops until every byte is accepted.
 */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_writeMaster(JNIEnv *env, jclass clazz,
                                                      jint fd,
                                                      jbyteArray buffer,
                                                      jint offset, jint count) {
  (void)clazz;
  /* Compare against the remaining length so offset + count cannot overflow. */
  if (buffer == NULL || offset < 0 || count < 0 ||
      offset > (*env)->GetArrayLength(env, buffer) ||
      count > (*env)->GetArrayLength(env, buffer) - offset) {
    return -(jint)EINVAL;
  }
  if (count == 0) return 0;
  struct pollfd poll_fd = {.fd = fd, .events = POLLOUT, .revents = 0};
  int ready;
  do {
    ready = poll(&poll_fd, 1, 100);
  } while (ready < 0 && errno == EINTR);
  if (ready == 0) return 0;
  if (ready < 0 || (poll_fd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
    const int error = ready < 0 ? errno : (poll_fd.revents & POLLNVAL) ? EBADF : EIO;
    return error == EIO ? -(jint)EIO : -(jint)error;
  }
  /* Copy only the slice being written; the caller loops on short writes. */
  char chunk[PTY_IO_CHUNK_BYTES];
  const jint slice = count < (jint)sizeof(chunk) ? count : (jint)sizeof(chunk);
  (*env)->GetByteArrayRegion(env, buffer, offset, slice, (jbyte *)chunk);
  ssize_t put;
  do {
    put = write(fd, chunk, (size_t)slice);
  } while (put < 0 && errno == EINTR);
  if (put < 0) {
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
    return -(jint)errno;
  }
  return (jint)put;
}

/* Copies element `index` of `array` into a fresh C string, or NULL. */
static char *copy_jstring(JNIEnv *env, jobject array, jsize index) {
  jstring element = (jstring)(*env)->GetObjectArrayElement(env, array, index);
  const char *utf = element != NULL
                        ? (*env)->GetStringUTFChars(env, element, NULL)
                        : NULL;
  char *copy = utf != NULL ? strdup(utf) : NULL;
  if (element != NULL) {
    if (utf != NULL) (*env)->ReleaseStringUTFChars(env, element, utf);
    (*env)->DeleteLocalRef(env, element);
  }
  return copy;
}

static jint encode_wait_status(int status) {
  if (WIFEXITED(status)) return WEXITSTATUS(status);
  if (WIFSIGNALED(status)) return -(jint)WTERMSIG(status);
  return -(jint)EINVAL;
}

static void free_strings(char **values) {
  if (values == NULL) return;
  for (size_t i = 0; values[i] != NULL; i++) free(values[i]);
  free(values);
}
