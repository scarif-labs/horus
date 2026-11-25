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

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#if defined(__APPLE__)
#include <util.h>
#else
#include <pty.h>
#endif
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include <ghostty/vt.h>

/* Matches the Kotlin reader's chunk size; stack-allocated per call. */
#define PTY_IO_CHUNK_BYTES 8192
/* Coalescing window for bursts of small writes; see linger_read. */
#define PTY_LINGER_GAP_MS 1
#define PTY_LINGER_MAX_MS 4

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
 * Closes every descriptor above stderr in the forked child.
 *
 * This bypasses bionic's close(): on Android 13, fdsan's lookup for a
 * descriptor exactly one past its overflow table writes past the table's
 * last page. When the next mapping is read-only the child dies with
 * SIGSEGV before execve, so the shell never starts (#89, #205 and other
 * Android 13 reports). The raw syscall never touches fdsan, and no
 * descriptor can exceed the hard RLIMIT_NOFILE, so the loop stops there.
 */
static void close_inherited_descriptors(void) {
  int limit = 65536;
  struct rlimit files;
  if (getrlimit(RLIMIT_NOFILE, &files) == 0 && files.rlim_max != RLIM_INFINITY &&
      files.rlim_max < (rlim_t)limit) {
    limit = (int)files.rlim_max;
  }
#if defined(__APPLE__)
  /* Host unit-test builds only: macOS has no fdsan and no raw close syscall number. */
  for (int fd = STDERR_FILENO + 1; fd < limit; fd++) (void)close(fd);
#else
  for (int fd = STDERR_FILENO + 1; fd < limit; fd++) (void)syscall(__NR_close, fd);
#endif
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
        close_inherited_descriptors();
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

static long elapsed_ms(const struct timespec *since) {
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (now.tv_sec - since->tv_sec) * 1000L + (now.tv_nsec - since->tv_nsec) / 1000000L;
}

/*
 * Keeps reading while more output follows within PTY_LINGER_GAP_MS, for at
 * most PTY_LINGER_MAX_MS or until [space] is full. Programs on a tty write a
 * line at a time, and under PRoot each write is slow enough that a single
 * read() returns one line; every chunk then costs a service hop, a binder
 * transaction, and an acknowledgement, which capped a flood at ~45 KB/s.
 * An interactive echo pays at most one gap of extra latency. Errors and EOF
 * end the linger; the next read reports them.
 */
static size_t linger_read(int fd, char *into, size_t space) {
  struct timespec started;
  clock_gettime(CLOCK_MONOTONIC, &started);
  size_t total = 0;
  while (total < space && elapsed_ms(&started) < PTY_LINGER_MAX_MS) {
    struct pollfd poll_fd = {.fd = fd, .events = POLLIN, .revents = 0};
    const int ready = poll(&poll_fd, 1, PTY_LINGER_GAP_MS);
    if (ready <= 0 || (poll_fd.revents & POLLIN) == 0) break;
    const ssize_t more = read(fd, into + total, space - total);
    if (more <= 0) break;
    total += (size_t)more;
  }
  return total;
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
  got += linger_read(fd, chunk + got, wanted - (size_t)got);
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

/* ========================================================================= */
/* libghostty-vt Native Terminal Engine Integration                          */
/* ========================================================================= */

#define HORUS_FLAG_BOLD (1 << 0)
#define HORUS_FLAG_ITALIC (1 << 1)
#define HORUS_FLAG_DIM (1 << 2)
#define HORUS_FLAG_UNDERLINE (1 << 3)
#define HORUS_FLAG_STRIKETHROUGH (1 << 4)
#define HORUS_FLAG_INVISIBLE (1 << 5)

#define HORUS_DEFAULT_FG 0xFFF2F4F5
#define HORUS_DEFAULT_BG 0xFF090C0D

typedef struct {
  atomic_size_t current_allocated_bytes;
  atomic_size_t current_allocated_blocks;
  atomic_size_t peak_allocated_bytes;
  atomic_size_t total_allocations;
  atomic_size_t total_frees;
  atomic_size_t failure_cap_bytes;
} GhosttyTrackingStats;

static GhosttyTrackingStats g_tracking_stats = {0};

static void *tracking_alloc(void *ctx, size_t len, uint8_t alignment, uintptr_t ret_addr) {
  (void)ctx;
  (void)ret_addr;
  if (len == 0) return NULL;

  size_t cap = atomic_load(&g_tracking_stats.failure_cap_bytes);
  if (cap > 0) {
    size_t current = atomic_load(&g_tracking_stats.current_allocated_bytes);
    if (current + len > cap) {
      return NULL;
    }
  }

  size_t align = (size_t)1 << alignment;
  if (align < sizeof(void *)) align = sizeof(void *);

  void *ptr = NULL;
  if (posix_memalign(&ptr, align, len) != 0) {
    return NULL;
  }

  atomic_fetch_add(&g_tracking_stats.current_allocated_bytes, len);
  atomic_fetch_add(&g_tracking_stats.current_allocated_blocks, 1);
  atomic_fetch_add(&g_tracking_stats.total_allocations, 1);

  size_t curr = atomic_load(&g_tracking_stats.current_allocated_bytes);
  size_t peak = atomic_load(&g_tracking_stats.peak_allocated_bytes);
  while (curr > peak && !atomic_compare_exchange_weak(&g_tracking_stats.peak_allocated_bytes, &peak, curr)) {
  }

  return ptr;
}

static void tracking_free(void *ctx, void *memory, size_t memory_len, uint8_t alignment, uintptr_t ret_addr) {
  (void)ctx;
  (void)alignment;
  (void)ret_addr;
  if (!memory) return;

  free(memory);
  atomic_fetch_sub(&g_tracking_stats.current_allocated_bytes, memory_len);
  atomic_fetch_sub(&g_tracking_stats.current_allocated_blocks, 1);
  atomic_fetch_add(&g_tracking_stats.total_frees, 1);
}

static const GhosttyAllocatorVtable g_tracking_vtable = {
    .alloc = tracking_alloc,
    .resize = NULL,
    .remap = NULL,
    .free = tracking_free,
    .reserved = {NULL, NULL, NULL, NULL},
};

static const GhosttyAllocator g_tracking_allocator = {
    .ctx = NULL,
    .vtable = &g_tracking_vtable,
};

typedef struct {
  pthread_mutex_t lock;
  int master_fd;
  GhosttyTerminal terminal;
  GhosttyRenderState render_state;
  GhosttyRenderStateRowCells row_cells;
  GhosttyRenderStateRowIterator row_it;
  GhosttyKeyEncoder key_encoder;
  GhosttyMouseEncoder mouse_encoder;
  uint16_t cols;
  uint16_t rows;
  uint8_t reply_buf[16384];
  size_t reply_len;
  char *clipboard_data;
  size_t clipboard_len;
} SessionEngine;

/* Longest grapheme text stored for one cell; the frame keeps the length in a byte. */
#define HORUS_MAX_CELL_TEXT 255

/*
 * JNI's GetStringUTFChars returns modified UTF-8, which encodes characters
 * outside the BMP as two 3-byte surrogates. libghostty expects standard
 * UTF-8, so encode from the UTF-16 chars instead. The caller frees the result.
 */
static uint8_t *jstring_to_utf8(JNIEnv *env, jstring text, size_t *out_len) {
  jsize len = (*env)->GetStringLength(env, text);
  const jchar *chars = (*env)->GetStringChars(env, text, NULL);
  if (!chars) return NULL;
  uint8_t *out = (uint8_t *)malloc((size_t)len * 3 + 1);
  if (!out) {
    (*env)->ReleaseStringChars(env, text, chars);
    return NULL;
  }
  size_t n = 0;
  for (jsize i = 0; i < len; i++) {
    uint32_t cp = chars[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + ((uint32_t)chars[i + 1] - 0xDC00);
      i++;
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }
    if (cp < 0x80) {
      out[n++] = (uint8_t)cp;
    } else if (cp < 0x800) {
      out[n++] = (uint8_t)(0xC0 | (cp >> 6));
      out[n++] = (uint8_t)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out[n++] = (uint8_t)(0xE0 | (cp >> 12));
      out[n++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
      out[n++] = (uint8_t)(0x80 | (cp & 0x3F));
    } else {
      out[n++] = (uint8_t)(0xF0 | (cp >> 18));
      out[n++] = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
      out[n++] = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
      out[n++] = (uint8_t)(0x80 | (cp & 0x3F));
    }
  }
  (*env)->ReleaseStringChars(env, text, chars);
  out[n] = '\0';
  *out_len = n;
  return out;
}

/*
 * Builds a Java string from standard UTF-8 that need not be NUL-terminated.
 * NewStringUTF needs modified UTF-8 and a terminator, so decode to UTF-16
 * here. Invalid sequences become U+FFFD.
 */
static jstring utf8_to_jstring(JNIEnv *env, const uint8_t *s, size_t len) {
  jchar *out = (jchar *)malloc((len > 0 ? len : 1) * sizeof(jchar));
  if (!out) return NULL;
  size_t n = 0;
  size_t i = 0;
  while (i < len) {
    uint8_t b = s[i];
    uint32_t cp;
    size_t need;
    uint32_t min;
    if (b < 0x80) {
      out[n++] = b;
      i++;
      continue;
    } else if ((b & 0xE0) == 0xC0) {
      cp = b & 0x1F; need = 1; min = 0x80;
    } else if ((b & 0xF0) == 0xE0) {
      cp = b & 0x0F; need = 2; min = 0x800;
    } else if ((b & 0xF8) == 0xF0) {
      cp = b & 0x07; need = 3; min = 0x10000;
    } else {
      out[n++] = 0xFFFD;
      i++;
      continue;
    }
    bool valid = len - i > need;
    for (size_t k = 1; valid && k <= need; k++) {
      if ((s[i + k] & 0xC0) != 0x80) valid = false;
      else cp = (cp << 6) | (s[i + k] & 0x3F);
    }
    if (!valid || cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      out[n++] = 0xFFFD;
      i++;
      continue;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out[n++] = (jchar)(0xD800 + (cp >> 10));
      out[n++] = (jchar)(0xDC00 + (cp & 0x3FF));
    } else {
      out[n++] = (jchar)cp;
    }
    i += need + 1;
  }
  jstring result = (*env)->NewString(env, out, (jsize)n);
  free(out);
  return result;
}

static void on_write_pty(GhosttyTerminal term, void *userdata, const uint8_t *data, size_t len) {
  (void)term;
  SessionEngine *engine = (SessionEngine *)userdata;
  if (!engine || !data || len == 0) return;

  // Security allowlist: Terminal query replies only forward valid VT response sequences:
  // - Starts with ESC (0x1B) [CSI, OSC, DCS, SS3, etc.] or standard C0 controls (e.g. BEL, ENQ reply)
  // - Bounded in length (max 4096 bytes per reply payload) to prevent reply injection attacks
  if (len > 4096) return;
  if (data[0] != 0x1B && data[0] != 0x05 && data[0] != 0x07) {
    return;
  }

  if (engine->master_fd >= 0) {
    size_t written = 0;
    while (written < len) {
      ssize_t put = write(engine->master_fd, data + written, len - written);
      if (put < 0) {
        if (errno == EINTR) continue;
        break;
      }
      written += (size_t)put;
    }
  }
  if (engine->reply_len + len <= sizeof(engine->reply_buf)) {
    memcpy(engine->reply_buf + engine->reply_len, data, len);
    engine->reply_len += len;
  }
}

static void on_clipboard_read(GhosttyTerminal term, void *userdata, const GhosttyClipboardRead *read) {
  (void)term;
  (void)userdata;
  if (!read || !read->reply) return;
  // Strictly deny OSC 52 ? clipboard reads for security
  GhosttyClipboardReadReply reply;
  ghostty_sized_init(&reply, sizeof(reply));
  reply.result = GHOSTTY_CLIPBOARD_READ_RESULT_DENIED;
  read->reply(read, &reply);
}

static const uint32_t ANSI_COLORS[16] = {
  0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
  0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
  0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
  0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
};

static GhosttyColorRgb HORUS_PALETTE[256];
static void init_horus_palette(void) {
  static bool initialized = false;
  if (initialized) return;
  for (int i = 0; i < 16; i++) {
    uint32_t c = ANSI_COLORS[i];
    HORUS_PALETTE[i].r = (uint8_t)((c >> 16) & 0xFF);
    HORUS_PALETTE[i].g = (uint8_t)((c >> 8) & 0xFF);
    HORUS_PALETTE[i].b = (uint8_t)(c & 0xFF);
  }
  for (int i = 16; i < 232; i++) {
    int color_index = i - 16;
    static const uint8_t levels[6] = {0, 95, 135, 175, 215, 255};
    HORUS_PALETTE[i].r = levels[color_index / 36];
    HORUS_PALETTE[i].g = levels[(color_index / 6) % 6];
    HORUS_PALETTE[i].b = levels[color_index % 6];
  }
  for (int i = 232; i < 256; i++) {
    uint8_t gray = (uint8_t)(8 + (i - 232) * 10);
    HORUS_PALETTE[i].r = gray;
    HORUS_PALETTE[i].g = gray;
    HORUS_PALETTE[i].b = gray;
  }
  initialized = true;
}

static char *base64_encode(const uint8_t *data, size_t len, size_t *out_length) {
  static const char tbl[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  size_t out_len = 4 * ((len + 2) / 3);
  char *out = (char *)malloc(out_len + 1);
  if (!out) return NULL;
  size_t i = 0, j = 0;
  while (i < len) {
    size_t rem = len - i;
    if (rem >= 3) {
      uint32_t v = ((uint32_t)data[i] << 16) | ((uint32_t)data[i + 1] << 8) | (uint32_t)data[i + 2];
      out[j++] = tbl[(v >> 18) & 0x3F];
      out[j++] = tbl[(v >> 12) & 0x3F];
      out[j++] = tbl[(v >> 6) & 0x3F];
      out[j++] = tbl[v & 0x3F];
      i += 3;
    } else if (rem == 2) {
      uint32_t v = ((uint32_t)data[i] << 16) | ((uint32_t)data[i + 1] << 8);
      out[j++] = tbl[(v >> 18) & 0x3F];
      out[j++] = tbl[(v >> 12) & 0x3F];
      out[j++] = tbl[(v >> 6) & 0x3F];
      out[j++] = '=';
      i += 2;
    } else {
      uint32_t v = (uint32_t)data[i] << 16;
      out[j++] = tbl[(v >> 18) & 0x3F];
      out[j++] = tbl[(v >> 12) & 0x3F];
      out[j++] = '=';
      out[j++] = '=';
      i += 1;
    }
  }
  out[out_len] = '\0';
  if (out_length) *out_length = out_len;
  return out;
}

static void on_clipboard_write(GhosttyTerminal term, void *userdata, const GhosttyClipboardWrite *write) {
  (void)term;
  SessionEngine *engine = (SessionEngine *)userdata;
  if (!engine || !write) return;
  for (size_t i = 0; i < write->contents_len; i++) {
    const GhosttyClipboardContent *content = &write->contents[i];
    if (content->data.len > 0) {
      free(engine->clipboard_data);
      engine->clipboard_data = base64_encode((const uint8_t *)content->data.ptr, content->data.len, &engine->clipboard_len);
      break;
    }
  }
  GhosttyClipboardWriteReply reply;
  ghostty_sized_init(&reply, sizeof(reply));
  reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  reply.remember = false;
  if (write->reply) {
    write->reply(write, &reply);
  }
}

JNIEXPORT jlong JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineCreate(
    JNIEnv *env, jclass clazz, jint cols, jint rows, jint masterFd) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)calloc(1, sizeof(SessionEngine));
  if (!engine) return 0;

  if (pthread_mutex_init(&engine->lock, NULL) != 0) {
    free(engine);
    return 0;
  }

  engine->master_fd = masterFd;
  engine->cols = (uint16_t)(cols > 0 ? cols : 80);
  engine->rows = (uint16_t)(rows > 0 ? rows : 24);

  if (ghostty_terminal_new(&g_tracking_allocator, &engine->terminal, engine->cols, engine->rows) != GHOSTTY_SUCCESS) {
    pthread_mutex_destroy(&engine->lock);
    free(engine);
    return 0;
  }

  if (ghostty_render_state_new(&g_tracking_allocator, &engine->render_state) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(&g_tracking_allocator, &engine->row_cells) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(&g_tracking_allocator, &engine->row_it) != GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(&g_tracking_allocator, &engine->key_encoder) != GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_new(&g_tracking_allocator, &engine->mouse_encoder) != GHOSTTY_SUCCESS) {
    if (engine->key_encoder) ghostty_key_encoder_free(engine->key_encoder);
    if (engine->mouse_encoder) ghostty_mouse_encoder_free(engine->mouse_encoder);
    if (engine->row_cells) ghostty_render_state_row_cells_free(engine->row_cells);
    if (engine->row_it) ghostty_render_state_row_iterator_free(engine->row_it);
    if (engine->render_state) ghostty_render_state_free(engine->render_state);
    ghostty_terminal_free(engine->terminal);
    pthread_mutex_destroy(&engine->lock);
    free(engine);
    return 0;
  }

  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, engine);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_write_pty);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)on_clipboard_write);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ, (const void *)on_clipboard_read);

  init_horus_palette();
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, HORUS_PALETTE);

  GhosttyColorRgb def_fg = { .r = 0xF2, .g = 0xF4, .b = 0xF5 };
  GhosttyColorRgb def_bg = { .r = 0x09, .g = 0x0C, .b = 0x0D };
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &def_fg);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &def_bg);

  size_t max_scrollback = 10 * 1024 * 1024;
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &max_scrollback);

  // Mode 2027 (Grapheme Clustering) must be off by default per DEC/VT spec,
  // enabled only when guest application requests it via CSI ? 2027 h
  GhosttyTerminalModeConfig grapheme_cfg = { .mode = GHOSTTY_MODE_GRAPHEME_CLUSTER, .value = false };
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &grapheme_cfg);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_MODE, &grapheme_cfg);

  GhosttyRenderStateOverscan overscan = { .above = engine->rows, .below = engine->rows };
  ghostty_render_state_set(engine->render_state, GHOSTTY_RENDER_STATE_OPT_OVERSCAN, &overscan);

  GhosttyMouseEncoderSize msize;
  ghostty_sized_init(&msize, sizeof(msize));
  msize.cell_width = 1;
  msize.cell_height = 1;
  msize.screen_width = engine->cols;
  msize.screen_height = engine->rows;
  ghostty_mouse_encoder_set(engine->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &msize);

  return (jlong)(uintptr_t)engine;
}

JNIEXPORT void JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineDestroy(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return;

  pthread_mutex_lock(&engine->lock);
  if (engine->key_encoder) ghostty_key_encoder_free(engine->key_encoder);
  if (engine->mouse_encoder) ghostty_mouse_encoder_free(engine->mouse_encoder);
  if (engine->row_cells) ghostty_render_state_row_cells_free(engine->row_cells);
  if (engine->row_it) ghostty_render_state_row_iterator_free(engine->row_it);
  if (engine->render_state) ghostty_render_state_free(engine->render_state);
  if (engine->terminal) ghostty_terminal_free(engine->terminal);
  if (engine->clipboard_data) free(engine->clipboard_data);
  pthread_mutex_unlock(&engine->lock);

  pthread_mutex_destroy(&engine->lock);
  free(engine);
}

JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineResize(
    JNIEnv *env, jclass clazz, jlong handle, jint cols, jint rows,
    jint cellWidthPx, jint cellHeightPx) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine || cols <= 0 || rows <= 0) return -(jint)EINVAL;

  pthread_mutex_lock(&engine->lock);
  engine->cols = (uint16_t)cols;
  engine->rows = (uint16_t)rows;
  ghostty_terminal_resize(engine->terminal, (uint16_t)cols, (uint16_t)rows,
                          (uint32_t)(cellWidthPx > 0 ? cellWidthPx : 1),
                          (uint32_t)(cellHeightPx > 0 ? cellHeightPx : 1));

  GhosttyMouseEncoderSize msize;
  ghostty_sized_init(&msize, sizeof(msize));
  msize.cell_width = (uint32_t)(cellWidthPx > 0 ? cellWidthPx : 1);
  msize.cell_height = (uint32_t)(cellHeightPx > 0 ? cellHeightPx : 1);
  msize.screen_width = (uint32_t)cols * msize.cell_width;
  msize.screen_height = (uint32_t)rows * msize.cell_height;
  ghostty_mouse_encoder_set(engine->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &msize);

  if (engine->master_fd >= 0) {
    struct winsize ws = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = (unsigned short)msize.screen_width,
        .ws_ypixel = (unsigned short)msize.screen_height,
    };
    ioctl(engine->master_fd, TIOCSWINSZ, &ws);
  }
  pthread_mutex_unlock(&engine->lock);
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineReset(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return -(jint)EINVAL;

  pthread_mutex_lock(&engine->lock);
  ghostty_terminal_reset(engine->terminal);
  pthread_mutex_unlock(&engine->lock);
  return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineIsSynchronizedOutput(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return JNI_FALSE;

  pthread_mutex_lock(&engine->lock);
  GhosttyTerminalModeConfig sync_cfg = { .mode = GHOSTTY_MODE_SYNCHRONIZED_OUTPUT, .value = false };
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_MODE, &sync_cfg);
  pthread_mutex_unlock(&engine->lock);

  return sync_cfg.value ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineFeed(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray buffer, jint offset, jint count) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine || !buffer || offset < 0 || count < 0) return NULL;

  jsize arr_len = (*env)->GetArrayLength(env, buffer);
  if (offset > arr_len || count > arr_len - offset) return NULL;
  if (count == 0) return NULL;

  pthread_mutex_lock(&engine->lock);
  engine->reply_len = 0;

  jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
  if (bytes) {
    ghostty_terminal_vt_write(engine->terminal, (const uint8_t *)(bytes + offset), (size_t)count);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
  }

  jbyteArray reply = NULL;
  if (engine->reply_len > 0) {
    reply = (*env)->NewByteArray(env, (jsize)engine->reply_len);
    if (reply) {
      (*env)->SetByteArrayRegion(env, reply, 0, (jsize)engine->reply_len, (const jbyte *)engine->reply_buf);
    }
    engine->reply_len = 0;
  }
  pthread_mutex_unlock(&engine->lock);
  return reply;
}

/*
 * Frame layout written by nativeEngineSyncRender (little endian):
 *
 *   header   FRAME_HEADER_BYTES, int32 slots (see FRAME_HDR_*)
 *   rows     FRAME_ROW_BYTES per captured row, top to bottom
 *   cells    FRAME_CELL_BYTES per cell, then the row's UTF-8 text
 *
 * Captured rows are the overscan rows above the viewport, the viewport, then
 * the overscan rows below it. A clean row that the caller already holds is
 * written without cells (cell offset FRAME_NO_CELLS) unless [forceCells].
 */
#define FRAME_HEADER_BYTES 128
#define FRAME_ROW_BYTES 40
#define FRAME_CELL_BYTES 12
#define FRAME_NO_CELLS 0xFFFFFFFFu
#define FRAME_NO_SELECTION 0xFFFFu

enum {
  FRAME_HDR_ROWS_WRITTEN = 0,
  FRAME_HDR_COLS = 1,
  FRAME_HDR_CURSOR_ROW = 2,
  FRAME_HDR_CURSOR_COL = 3,
  FRAME_HDR_CURSOR_VISIBLE = 4,
  FRAME_HDR_ALTERNATE = 5,
  FRAME_HDR_MOUSE_TRACKING = 6,
  FRAME_HDR_MOUSE_SGR = 7,
  FRAME_HDR_BRACKETED_PASTE = 8,
  FRAME_HDR_OVERSCAN_ABOVE = 9,
  FRAME_HDR_VIEWPORT_ROWS = 10,
  FRAME_HDR_DIRTY = 11,
  FRAME_HDR_OVERSCAN_BELOW = 12,
  FRAME_HDR_REVERSE_VIDEO = 13,
  FRAME_HDR_SCROLL_TOTAL = 14,
  FRAME_HDR_SCROLL_OFFSET = 15,
  FRAME_HDR_SCROLL_LEN = 16,
  FRAME_HDR_VIEWPORT_ACTIVE = 17,
  FRAME_HDR_HAS_SELECTION = 18,
  FRAME_HDR_CURSOR_STYLE = 19,
  FRAME_HDR_CURSOR_BLINKING = 20,
  FRAME_HDR_DEFAULT_FG = 21,
  FRAME_HDR_DEFAULT_BG = 22,
  FRAME_HDR_MOUSE_ALTERNATE_SCROLL = 23,
  FRAME_HDR_SLOTS = 32,
};

static int32_t clamp_rows(uint64_t value) {
  return value > (uint64_t)INT32_MAX ? INT32_MAX : (int32_t)value;
}

static bool engine_mode(SessionEngine *engine, GhosttyMode mode) {
  GhosttyTerminalModeConfig cfg = { .mode = mode, .value = false };
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_MODE, &cfg);
  return cfg.value;
}

static jintArray sync_result(JNIEnv *env, jint status, jint a, jint b, jint c) {
  jintArray out = (*env)->NewIntArray(env, 4);
  if (out) {
    jint vals[4] = {status, a, b, c};
    (*env)->SetIntArrayRegion(env, out, 0, 4, vals);
  }
  return out;
}

static uint32_t rgb_to_argb(GhosttyColorRgb rgb) {
  return 0xFF000000u | ((uint32_t)rgb.r << 16) | ((uint32_t)rgb.g << 8) | (uint32_t)rgb.b;
}

JNIEXPORT jintArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSyncRender(
    JNIEnv *env, jclass clazz, jlong handle, jobject outDirectBuffer, jboolean forceCells) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine || !outDirectBuffer) return sync_result(env, -1, 0, 0, 0);

  uint8_t *buf = (uint8_t *)(*env)->GetDirectBufferAddress(env, outDirectBuffer);
  jlong cap = (*env)->GetDirectBufferCapacity(env, outDirectBuffer);
  if (!buf || cap < FRAME_HEADER_BYTES) return sync_result(env, -1, 1024 * 1024, 0, 0);

  pthread_mutex_lock(&engine->lock);

  ghostty_render_state_update(engine->render_state, engine->terminal);

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

  GhosttyRenderStateDirty dirty_mode = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty_mode);

  GhosttyRenderStateCursor cursor;
  ghostty_sized_init(&cursor, sizeof(cursor));
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  GhosttyRenderStateOverscan overscan = {0, 0};
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_OVERSCAN, &overscan);

  GhosttyColorRgb default_fg_rgb = { .r = 0xF2, .g = 0xF4, .b = 0xF5 };
  GhosttyColorRgb default_bg_rgb = { .r = 0x09, .g = 0x0C, .b = 0x0D };
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_COLOR_FOREGROUND, &default_fg_rgb);
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_COLOR_BACKGROUND, &default_bg_rgb);

  int32_t total_rows = (int32_t)overscan.above + (int32_t)rows + (int32_t)overscan.below;

  GhosttyTerminalScreen active_screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active_screen);
  bool is_alternate = (active_screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE);

  bool mouse_tracking = false;
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse_tracking);

  GhosttyTerminalScrollbar scrollbar = {0, 0, 0};
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar);

  bool viewport_active = true;
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &viewport_active);

  GhosttySelection current_selection;
  memset(&current_selection, 0, sizeof(current_selection));
  bool has_selection =
      ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &current_selection) == GHOSTTY_SUCCESS;

  bool reverse_video = engine_mode(engine, GHOSTTY_MODE_REVERSE_COLORS);

  size_t row_table_end = FRAME_HEADER_BYTES + (size_t)total_rows * FRAME_ROW_BYTES;
  if ((size_t)cap < row_table_end + (size_t)cols * (FRAME_CELL_BYTES + 4)) {
    pthread_mutex_unlock(&engine->lock);
    size_t wanted = row_table_end + (size_t)total_rows * (size_t)cols * (FRAME_CELL_BYTES + 4);
    return sync_result(env, -1, wanted > (size_t)INT32_MAX ? INT32_MAX : (jint)wanted, total_rows, (jint)dirty_mode);
  }

  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &engine->row_it);

  size_t data_offset = row_table_end;
  bool prev_row_had_spacer_head = false;
  int32_t rows_written = 0;
  bool out_of_space = false;
  bool full_redraw = dirty_mode == GHOSTTY_RENDER_STATE_DIRTY_FULL;
  bool write_all_cells = forceCells || full_redraw;
  uint32_t default_fg = rgb_to_argb(default_fg_rgb);
  uint32_t default_bg = rgb_to_argb(default_bg_rgb);

  for (int32_t r = 0; r < total_rows && !out_of_space; r++) {
    if (!ghostty_render_state_row_iterator_next(engine->row_it)) break;

    GhosttyRenderStateRowId row_id = {{0, 0}};
    ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_ID, &row_id);

    bool row_dirty = false;
    ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &row_dirty);

    GhosttyRow raw_row = 0;
    bool wrapped = false;
    if (ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &raw_row) == GHOSTTY_SUCCESS) {
      ghostty_row_get(raw_row, GHOSTTY_ROW_DATA_WRAP_CONTINUATION, &wrapped);
    }

    GhosttyRenderStateRowSelection row_sel;
    ghostty_sized_init(&row_sel, sizeof(row_sel));
    uint16_t sel_start = FRAME_NO_SELECTION;
    uint16_t sel_end = FRAME_NO_SELECTION;
    if (has_selection &&
        ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION, &row_sel) == GHOSTTY_SUCCESS) {
      sel_start = row_sel.start_x;
      sel_end = row_sel.end_x;
    }

    bool wrap_padding = wrapped && prev_row_had_spacer_head;
    prev_row_had_spacer_head = false;

    uint8_t *row_entry = buf + FRAME_HEADER_BYTES + (size_t)rows_written * FRAME_ROW_BYTES;
    bool write_cells = write_all_cells || row_dirty;
    uint32_t c_off = FRAME_NO_CELLS;
    uint32_t t_off = FRAME_NO_CELLS;
    size_t text_written = 0;
    bool row_has_visible = false;

    if (write_cells) {
      ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &engine->row_cells);
      size_t cell_data_offset = data_offset;
      size_t text_data_offset = cell_data_offset + (size_t)cols * FRAME_CELL_BYTES;
      if (text_data_offset > (size_t)cap) {
        out_of_space = true;
        break;
      }

      for (uint16_t c = 0; c < cols; c++) {
        ghostty_render_state_row_cells_select(engine->row_cells, c);

        GhosttyCell raw_cell = 0;
        ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw_cell);

        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        if (c == cols - 1 && wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) prev_row_had_spacer_head = true;

        uint8_t cell_width = 1;
        if (wide == GHOSTTY_CELL_WIDE_WIDE) cell_width = 2;
        else if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) cell_width = 0;

        uint8_t utf8_cell[HORUS_MAX_CELL_TEXT + 1];
        uint8_t *heap_cell = NULL;
        GhosttyBuffer gb = { .ptr = utf8_cell, .cap = sizeof(utf8_cell), .len = 0 };
        GhosttyResult gr = ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &gb);
        if (gr == GHOSTTY_OUT_OF_SPACE && gb.len > sizeof(utf8_cell)) {
          /* On OUT_OF_SPACE nothing was written and len holds the size needed. */
          heap_cell = (uint8_t *)malloc(gb.len);
          if (heap_cell) {
            gb.ptr = heap_cell;
            gb.cap = gb.len;
            gb.len = 0;
            gr = ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &gb);
          }
        }
        size_t cell_text_len = gr == GHOSTTY_SUCCESS ? gb.len : 0;
        if (cell_text_len > HORUS_MAX_CELL_TEXT) {
          /* Keep the base character and as many marks as fit, ending on a whole code point. */
          cell_text_len = HORUS_MAX_CELL_TEXT;
          while (cell_text_len > 0 && (gb.ptr[cell_text_len] & 0xC0) == 0x80) cell_text_len--;
        }
        if (text_data_offset + text_written + (cell_text_len > 0 ? cell_text_len : 1) > (size_t)cap) {
          free(heap_cell);
          out_of_space = true;
          break;
        }

        uint8_t text_len = 0;
        if (cell_text_len > 0) {
          text_len = (uint8_t)cell_text_len;
          memcpy(buf + text_data_offset + text_written, gb.ptr, cell_text_len);
          text_written += cell_text_len;
          if (cell_text_len != 1 || gb.ptr[0] != ' ') row_has_visible = true;
        } else if (wide != GHOSTTY_CELL_WIDE_SPACER_TAIL) {
          text_len = 1;
          buf[text_data_offset + text_written] = ' ';
          text_written += 1;
        }
        free(heap_cell);

        uint32_t fg_argb = default_fg;
        uint32_t bg_argb = default_bg;
        uint16_t cell_flags = 0;
        bool inverse = false;

        bool styled = false;
        ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &styled);
        GhosttyColorRgb rgb;
        if (styled) {
          if (ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &rgb) == GHOSTTY_SUCCESS) {
            fg_argb = rgb_to_argb(rgb);
          }
          GhosttyStyle style;
          memset(&style, 0, sizeof(style));
          if (ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style) == GHOSTTY_SUCCESS) {
            if (style.flags & GHOSTTY_STYLE_FLAG_BOLD) cell_flags |= HORUS_FLAG_BOLD;
            if (style.flags & GHOSTTY_STYLE_FLAG_ITALIC) cell_flags |= HORUS_FLAG_ITALIC;
            if (style.flags & GHOSTTY_STYLE_FLAG_FAINT) cell_flags |= HORUS_FLAG_DIM;
            if (style.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) cell_flags |= HORUS_FLAG_STRIKETHROUGH;
            if (style.flags & GHOSTTY_STYLE_FLAG_INVISIBLE) cell_flags |= HORUS_FLAG_INVISIBLE;
            if (GHOSTTY_STYLE_UNDERLINE(style) != 0) cell_flags |= HORUS_FLAG_UNDERLINE;
            if (style.flags & GHOSTTY_STYLE_FLAG_INVERSE) inverse = true;
          }
        }
        /* Unstyled blank cells can still carry a background (erase with a colour). */
        if (styled || cell_text_len == 0) {
          if (ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &rgb) == GHOSTTY_SUCCESS) {
            bg_argb = rgb_to_argb(rgb);
          }
        }
        /* DECSCNM reverses every cell, and an inverse cell back again. */
        if (inverse != reverse_video) {
          uint32_t tmp = fg_argb; fg_argb = bg_argb; bg_argb = tmp;
        }

        uint8_t *cell_ptr = buf + cell_data_offset + (size_t)c * FRAME_CELL_BYTES;
        cell_ptr[0] = cell_width;
        cell_ptr[1] = text_len;
        memcpy(cell_ptr + 2, &cell_flags, 2);
        memcpy(cell_ptr + 4, &fg_argb, 4);
        memcpy(cell_ptr + 8, &bg_argb, 4);
      }
      if (out_of_space) break;
      c_off = (uint32_t)cell_data_offset;
      t_off = (uint32_t)text_data_offset;
      data_offset = text_data_offset + text_written;
      data_offset = (data_offset + 3) & ~((size_t)3);
    } else {
      /* Without its cells, read just enough of the row's last cell for wrap padding. */
      ghostty_render_state_row_get(engine->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &engine->row_cells);
      if (cols > 0 && ghostty_render_state_row_cells_select(engine->row_cells, cols - 1) == GHOSTTY_SUCCESS) {
        GhosttyCell raw_cell = 0;
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_render_state_row_cells_get(engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw_cell);
        ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        prev_row_had_spacer_head = wide == GHOSTTY_CELL_WIDE_SPACER_HEAD;
      }
    }

    memcpy(row_entry, &row_id.bits[0], 8);
    memcpy(row_entry + 8, &row_id.bits[1], 8);
    memcpy(row_entry + 16, &cols, 2);
    /* A full redraw leaves row flags clean, but colors baked into cells may have changed. */
    row_entry[18] = (row_dirty || full_redraw) ? 1 : 0;
    row_entry[19] = wrapped ? 1 : 0;
    row_entry[20] = wrap_padding ? 1 : 0;
    row_entry[21] = row_has_visible ? 1 : 0;
    row_entry[22] = 0;
    row_entry[23] = 0;
    memcpy(row_entry + 24, &c_off, 4);
    memcpy(row_entry + 28, &t_off, 4);
    memcpy(row_entry + 32, &sel_start, 2);
    memcpy(row_entry + 34, &sel_end, 2);
    memset(row_entry + 36, 0, 4);
    rows_written++;
  }

  if (out_of_space) {
    /* The render state stays dirty, so a retry with a larger buffer sees the same rows. */
    pthread_mutex_unlock(&engine->lock);
    size_t grown = (size_t)cap * 2;
    return sync_result(env, -1, grown > (size_t)INT32_MAX ? INT32_MAX : (jint)grown, total_rows, (jint)dirty_mode);
  }

  bool cursor_shown = cursor.visible && cursor.viewport_has_value;
  int32_t hdr[FRAME_HDR_SLOTS];
  memset(hdr, 0, sizeof(hdr));
  hdr[FRAME_HDR_ROWS_WRITTEN] = rows_written;
  hdr[FRAME_HDR_COLS] = (int32_t)cols;
  hdr[FRAME_HDR_CURSOR_ROW] = cursor.viewport_has_value ? (int32_t)overscan.above + (int32_t)cursor.viewport_y : -1;
  hdr[FRAME_HDR_CURSOR_COL] = cursor.viewport_has_value ? (int32_t)cursor.viewport_x : 0;
  hdr[FRAME_HDR_CURSOR_VISIBLE] = cursor_shown ? 1 : 0;
  hdr[FRAME_HDR_ALTERNATE] = is_alternate ? 1 : 0;
  hdr[FRAME_HDR_MOUSE_TRACKING] = mouse_tracking ? 1 : 0;
  hdr[FRAME_HDR_MOUSE_SGR] = engine_mode(engine, GHOSTTY_MODE_MOUSE_FORMAT_SGR) ? 1 : 0;
  hdr[FRAME_HDR_BRACKETED_PASTE] = engine_mode(engine, GHOSTTY_MODE_BRACKETED_PASTE) ? 1 : 0;
  hdr[FRAME_HDR_OVERSCAN_ABOVE] = (int32_t)overscan.above;
  hdr[FRAME_HDR_VIEWPORT_ROWS] = (int32_t)rows;
  hdr[FRAME_HDR_DIRTY] = (int32_t)dirty_mode;
  hdr[FRAME_HDR_OVERSCAN_BELOW] = (int32_t)overscan.below;
  hdr[FRAME_HDR_REVERSE_VIDEO] = reverse_video ? 1 : 0;
  hdr[FRAME_HDR_SCROLL_TOTAL] = clamp_rows(scrollbar.total);
  hdr[FRAME_HDR_SCROLL_OFFSET] = clamp_rows(scrollbar.offset);
  hdr[FRAME_HDR_SCROLL_LEN] = clamp_rows(scrollbar.len);
  hdr[FRAME_HDR_VIEWPORT_ACTIVE] = viewport_active ? 1 : 0;
  hdr[FRAME_HDR_HAS_SELECTION] = has_selection ? 1 : 0;
  hdr[FRAME_HDR_CURSOR_STYLE] = (int32_t)cursor.visual_style;
  hdr[FRAME_HDR_CURSOR_BLINKING] = cursor.blinking ? 1 : 0;
  hdr[FRAME_HDR_DEFAULT_FG] = (int32_t)(reverse_video ? default_bg : default_fg);
  hdr[FRAME_HDR_DEFAULT_BG] = (int32_t)(reverse_video ? default_fg : default_bg);
  hdr[FRAME_HDR_MOUSE_ALTERNATE_SCROLL] = engine_mode(engine, GHOSTTY_MODE_MOUSE_ALTERNATE_SCROLL) ? 1 : 0;
  memcpy(buf, hdr, sizeof(hdr));

  ghostty_render_state_clean(engine->render_state);

  pthread_mutex_unlock(&engine->lock);

  return sync_result(env, 0, (jint)data_offset, rows_written, (jint)dirty_mode);
}

/*
 * Moves the viewport and sets how many rows to capture around it. [toBottom]
 * follows the active area; otherwise [row] becomes the first viewport row,
 * counted from the top of the scrollback. The next sync reflects the change.
 */
JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSetViewport(
    JNIEnv *env, jclass clazz, jlong handle, jboolean toBottom, jlong row, jint above, jint below) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return -(jint)EINVAL;

  pthread_mutex_lock(&engine->lock);
  GhosttyRenderStateOverscan request = {0, 0};
  ghostty_render_state_get(engine->render_state, GHOSTTY_RENDER_STATE_DATA_OVERSCAN_REQUEST, &request);
  uint16_t want_above = (uint16_t)(above < 0 ? 0 : above > 1000 ? 1000 : above);
  uint16_t want_below = (uint16_t)(below < 0 ? 0 : below > 1000 ? 1000 : below);
  if (request.above != want_above || request.below != want_below) {
    GhosttyRenderStateOverscan overscan = { .above = want_above, .below = want_below };
    ghostty_render_state_set(engine->render_state, GHOSTTY_RENDER_STATE_OPT_OVERSCAN, &overscan);
  }
  GhosttyTerminalScrollViewport behavior;
  memset(&behavior, 0, sizeof(behavior));
  if (toBottom) {
    behavior.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM;
  } else {
    behavior.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_ROW;
    behavior.value.row = (size_t)(row < 0 ? 0 : row);
  }
  GhosttyResult result = ghostty_terminal_scroll_viewport(engine->terminal, behavior);
  pthread_mutex_unlock(&engine->lock);
  return result == GHOSTTY_SUCCESS ? 0 : -(jint)EINVAL;
}

JNIEXPORT jstring JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEnginePollClipboard(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;

  pthread_mutex_lock(&engine->lock);
  jstring result = NULL;
  if (engine->clipboard_data && engine->clipboard_len > 0) {
    result = (*env)->NewStringUTF(env, engine->clipboard_data);
    free(engine->clipboard_data);
    engine->clipboard_data = NULL;
    engine->clipboard_len = 0;
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineEncodeKey(
    JNIEnv *env, jclass clazz, jlong handle, jint key, jint mods, jint action, jstring text) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;

  pthread_mutex_lock(&engine->lock);
  ghostty_key_encoder_set_from_terminal(engine->key_encoder, engine->terminal);

  GhosttyKeyEvent event = NULL;
  if (ghostty_key_event_new(&g_tracking_allocator, &event) != GHOSTTY_SUCCESS) {
    pthread_mutex_unlock(&engine->lock);
    return NULL;
  }

  ghostty_key_event_set_key(event, (GhosttyKey)key);
  ghostty_key_event_set_mods(event, (GhosttyMods)mods);
  ghostty_key_event_set_action(event, (GhosttyKeyAction)action);

  /* The event does not copy its text, so keep the bytes until encoding is done. */
  uint8_t *utf = NULL;
  if (text) {
    size_t utf_len = 0;
    utf = jstring_to_utf8(env, text, &utf_len);
    if (utf) {
      GhosttyString gstr = { .ptr = utf, .len = utf_len };
      ghostty_key_event_set_utf8(event, gstr);
    }
  }

  uint8_t buf[256];
  size_t written = 0;
  GhosttyResult res = ghostty_key_encoder_encode(engine->key_encoder, event, buf, sizeof(buf), &written);
  ghostty_key_event_free(event);
  free(utf);

  jbyteArray result = NULL;
  if (res == GHOSTTY_SUCCESS && written > 0) {
    result = (*env)->NewByteArray(env, (jsize)written);
    if (result) {
      (*env)->SetByteArrayRegion(env, result, 0, (jsize)written, (const jbyte *)buf);
    }
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

/*
 * Encodes a physical key press for the terminal's current modes (cursor
 * keys, kitty keyboard protocol, ...). [text] is the key's character
 * without Ctrl/Alt applied, or null for keys with none; [consumedMods] are
 * the modifiers that produced it (Shift for "A") and [unshifted] its
 * unshifted code point (or 0).
 */
JNIEXPORT jbyteArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineEncodeKeyEvent(
    JNIEnv *env, jclass clazz, jlong handle, jint key, jint mods, jint consumedMods,
    jint unshifted, jint action, jstring text) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;

  /* Convert before locking: the JNI string calls do not touch the engine. */
  uint8_t *utf = NULL;
  size_t utf_len = 0;
  if (text) utf = jstring_to_utf8(env, text, &utf_len);

  pthread_mutex_lock(&engine->lock);
  ghostty_key_encoder_set_from_terminal(engine->key_encoder, engine->terminal);
#if defined(__APPLE__)
  /* Host unit tests: treat Option as Alt, as Android's Alt always is. */
  GhosttyOptionAsAlt option_as_alt = GHOSTTY_OPTION_AS_ALT_TRUE;
  ghostty_key_encoder_set(engine->key_encoder, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &option_as_alt);
#endif
  GhosttyKeyEvent event = NULL;
  jbyteArray result = NULL;
  if (ghostty_key_event_new(&g_tracking_allocator, &event) == GHOSTTY_SUCCESS) {
    ghostty_key_event_set_key(event, (GhosttyKey)key);
    ghostty_key_event_set_mods(event, (GhosttyMods)mods);
    ghostty_key_event_set_consumed_mods(event, (GhosttyMods)consumedMods);
    ghostty_key_event_set_action(event, (GhosttyKeyAction)action);
    if (unshifted > 0) ghostty_key_event_set_unshifted_codepoint(event, (uint32_t)unshifted);
    if (utf && utf_len > 0) {
      GhosttyString gstr = { .ptr = utf, .len = utf_len };
      ghostty_key_event_set_utf8(event, gstr);
    }
    uint8_t buf[256];
    size_t written = 0;
    if (ghostty_key_encoder_encode(engine->key_encoder, event, buf, sizeof(buf), &written) == GHOSTTY_SUCCESS && written > 0) {
      result = (*env)->NewByteArray(env, (jsize)written);
      if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)written, (const jbyte *)buf);
    }
    ghostty_key_event_free(event);
  }
  pthread_mutex_unlock(&engine->lock);
  free(utf);
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineEncodeMouse(
    JNIEnv *env, jclass clazz, jlong handle, jfloat x, jfloat y,
    jint button, jint action, jint mods) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;

  pthread_mutex_lock(&engine->lock);
  ghostty_mouse_encoder_set_from_terminal(engine->mouse_encoder, engine->terminal);

  GhosttyMouseEvent event = NULL;
  if (ghostty_mouse_event_new(&g_tracking_allocator, &event) != GHOSTTY_SUCCESS) {
    pthread_mutex_unlock(&engine->lock);
    return NULL;
  }

  GhosttyMousePosition pos = { .x = x, .y = y };
  ghostty_mouse_event_set_position(event, pos);
  ghostty_mouse_event_set_button(event, (GhosttyMouseButton)button);
  ghostty_mouse_event_set_action(event, (GhosttyMouseAction)action);
  ghostty_mouse_event_set_mods(event, (GhosttyMods)mods);

  uint8_t buf[128];
  size_t written = 0;
  GhosttyResult res = ghostty_mouse_encoder_encode(engine->mouse_encoder, event, buf, sizeof(buf), &written);
  ghostty_mouse_event_free(event);

  jbyteArray result = NULL;
  if (res == GHOSTTY_SUCCESS && written > 0) {
    result = (*env)->NewByteArray(env, (jsize)written);
    if (result) {
      (*env)->SetByteArrayRegion(env, result, 0, (jsize)written, (const jbyte *)buf);
    }
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineEncodePaste(
    JNIEnv *env, jclass clazz, jlong handle, jstring text) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine || !text) return NULL;

  size_t text_len = 0;
  char *copy_text = (char *)jstring_to_utf8(env, text, &text_len);
  if (!copy_text) return NULL;

  pthread_mutex_lock(&engine->lock);

  GhosttyTerminalModeConfig paste_cfg = { .mode = GHOSTTY_MODE_BRACKETED_PASTE, .value = false };
  ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_MODE, &paste_cfg);
  bool bracketed = paste_cfg.value;

  size_t needed = 0;
  ghostty_paste_encode(copy_text, text_len, bracketed, NULL, 0, &needed);

  jbyteArray result = NULL;
  if (needed > 0) {
    uint8_t *out_buf = (uint8_t *)malloc(needed);
    if (out_buf) {
      size_t written = 0;
      if (ghostty_paste_encode(copy_text, text_len, bracketed, out_buf, needed, &written) == GHOSTTY_SUCCESS) {
        result = (*env)->NewByteArray(env, (jsize)written);
        if (result) {
          (*env)->SetByteArrayRegion(env, result, 0, (jsize)written, (const jbyte *)out_buf);
        }
      }
      free(out_buf);
    }
  }
  free(copy_text);
  pthread_mutex_unlock(&engine->lock);
  return result ? result : (*env)->NewByteArray(env, 0);
}

JNIEXPORT jstring JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineGetFormattedText(
    JNIEnv *env, jclass clazz, jlong handle, jboolean unwrap, jboolean trim) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;

  pthread_mutex_lock(&engine->lock);
  GhosttyFormatter formatter = NULL;
  if (ghostty_formatter_terminal_new(&g_tracking_allocator, &formatter, engine->terminal) != GHOSTTY_SUCCESS) {
    pthread_mutex_unlock(&engine->lock);
    return NULL;
  }

  bool u = (bool)unwrap;
  bool t = (bool)trim;
  ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_UNWRAP, &u);
  ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_TRIM, &t);

  uint8_t *out_buf = NULL;
  size_t out_len = 0;
  jstring result = NULL;
  if (ghostty_formatter_format_alloc(formatter, &g_tracking_allocator, &out_buf, &out_len) == GHOSTTY_SUCCESS && out_buf) {
    result = utf8_to_jstring(env, out_buf, out_len);
    ghostty_free(&g_tracking_allocator, out_buf, out_len);
  }
  ghostty_formatter_free(formatter);
  pthread_mutex_unlock(&engine->lock);
  return result;
}

/* Resolves a cell given as (column, row from the top of the scrollback). */
static bool engine_screen_ref(SessionEngine *engine, jint x, jint y, GhosttyGridRef *out) {
  if (x < 0 || y < 0) return false;
  GhosttyPoint point;
  memset(&point, 0, sizeof(point));
  point.tag = GHOSTTY_POINT_TAG_SCREEN;
  point.value.coordinate.x = (uint16_t)(engine->cols > 0 && x >= engine->cols ? engine->cols - 1 : x);
  point.value.coordinate.y = (uint32_t)y;
  memset(out, 0, sizeof(*out));
  return ghostty_terminal_grid_ref(engine->terminal, point, out) == GHOSTTY_SUCCESS;
}

/* Whether a cell shows nothing: no text, or a space. libghostty selects a run of spaces as a word. */
static bool engine_cell_is_blank(const GhosttyGridRef *ref) {
  GhosttyCell cell = 0;
  if (ghostty_grid_ref_cell(ref, &cell) != GHOSTTY_SUCCESS) return true;
  bool has_text = false;
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_TEXT, &has_text);
  if (!has_text) return true;
  uint32_t codepoint = 0;
  ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &codepoint);
  return codepoint == ' ' || codepoint == '\t' || codepoint == 0x00A0;
}

/* Writes a selection's ends, in reading order, as {startX, startY, endX, endY}; ends are inclusive. */
static jintArray selection_bounds(JNIEnv *env, SessionEngine *engine, const GhosttySelection *selection) {
  GhosttySelection ordered;
  memset(&ordered, 0, sizeof(ordered));
  if (ghostty_terminal_selection_ordered(engine->terminal, selection, GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) != GHOSTTY_SUCCESS) {
    return NULL;
  }
  GhosttyPointCoordinate start = {0, 0}, end = {0, 0};
  if (ghostty_terminal_point_from_grid_ref(engine->terminal, &ordered.start, GHOSTTY_POINT_TAG_SCREEN, &start) != GHOSTTY_SUCCESS ||
      ghostty_terminal_point_from_grid_ref(engine->terminal, &ordered.end, GHOSTTY_POINT_TAG_SCREEN, &end) != GHOSTTY_SUCCESS) {
    return NULL;
  }
  jintArray out = (*env)->NewIntArray(env, 4);
  if (out) {
    jint vals[4] = {(jint)start.x, clamp_rows(start.y), (jint)end.x, clamp_rows(end.y)};
    (*env)->SetIntArrayRegion(env, out, 0, 4, vals);
  }
  return out;
}

/*
 * Quotes, brackets, pipes and box drawing end a word, besides whitespace and
 * empty cells (TUIs such as OpenCode leave gaps unwritten). Colons and
 * slashes do not, so paths and URLs select whole.
 */
static const uint32_t WORD_BOUNDARIES[] = {
  0, ' ', '\t', 0x00A0, '"', '\'', '`', '(', ')', '[', ']', '{', '}', '<', '>', '|',
  0x2502, 0x2503, 0x2500, 0x2501, 0x256D, 0x256E, 0x2570, 0x256F,
  0x250C, 0x2510, 0x2514, 0x2518, 0x251C, 0x2524, 0x252C, 0x2534, 0x253C,
};

/*
 * Selects the word at a screen cell and returns its bounds like
 * nativeEngineSelectionBounds. Null when the cell holds no word (or does not
 * exist); the selection is then unchanged.
 */
JNIEXPORT jintArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSelectWord(
    JNIEnv *env, jclass clazz, jlong handle, jint x, jint y) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;
  pthread_mutex_lock(&engine->lock);
  jintArray result = NULL;
  GhosttyGridRef ref;
  if (engine_screen_ref(engine, x, y, &ref) && !engine_cell_is_blank(&ref)) {
    GhosttyTerminalSelectWordOptions options;
    ghostty_sized_init(&options, sizeof(options));
    options.ref = ref;
    options.boundary_codepoints = WORD_BOUNDARIES;
    options.boundary_codepoints_len = sizeof(WORD_BOUNDARIES) / sizeof(WORD_BOUNDARIES[0]);
    GhosttySelection selection;
    memset(&selection, 0, sizeof(selection));
    if (ghostty_terminal_select_word(engine->terminal, &options, &selection) == GHOSTTY_SUCCESS &&
        ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS) {
      GhosttySelection installed;
      memset(&installed, 0, sizeof(installed));
      if (ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &installed) == GHOSTTY_SUCCESS) {
        result = selection_bounds(env, engine, &installed);
      }
    }
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

/* Selects from one screen cell to another, both included, in either order. */
JNIEXPORT jboolean JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSelectRange(
    JNIEnv *env, jclass clazz, jlong handle, jint x0, jint y0, jint x1, jint y1) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return JNI_FALSE;
  pthread_mutex_lock(&engine->lock);
  bool ok = false;
  GhosttySelection selection;
  memset(&selection, 0, sizeof(selection));
  if (engine_screen_ref(engine, x0, y0, &selection.start)) {
    GhosttyGridRef end;
    /* Resolve the second end after the first: both stay valid, nothing mutated in between. */
    if (engine_screen_ref(engine, x1, y1, &end)) {
      selection.end = end;
      ok = ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS;
    }
  }
  pthread_mutex_unlock(&engine->lock);
  return ok ? JNI_TRUE : JNI_FALSE;
}

/* Selects the whole screen and scrollback. */
JNIEXPORT jboolean JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSelectAll(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return JNI_FALSE;
  pthread_mutex_lock(&engine->lock);
  GhosttySelection selection;
  memset(&selection, 0, sizeof(selection));
  bool ok = ghostty_terminal_select_all(engine->terminal, &selection) == GHOSTTY_SUCCESS &&
      ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection) == GHOSTTY_SUCCESS;
  pthread_mutex_unlock(&engine->lock);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineClearSelection(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return;
  pthread_mutex_lock(&engine->lock);
  ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
  pthread_mutex_unlock(&engine->lock);
}

/* The current selection's ends as {startX, startY, endX, endY} in screen rows, or null. */
JNIEXPORT jintArray JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSelectionBounds(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;
  pthread_mutex_lock(&engine->lock);
  jintArray result = NULL;
  GhosttySelection selection;
  memset(&selection, 0, sizeof(selection));
  if (ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &selection) == GHOSTTY_SUCCESS) {
    result = selection_bounds(env, engine, &selection);
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

/* The selected text as plain text with soft wraps joined, or null when nothing is selected. */
JNIEXPORT jstring JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineSelectionText(
    JNIEnv *env, jclass clazz, jlong handle) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine) return NULL;
  pthread_mutex_lock(&engine->lock);
  jstring result = NULL;
  GhosttySelection selection;
  memset(&selection, 0, sizeof(selection));
  GhosttyFormatter formatter = NULL;
  if (ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &selection) == GHOSTTY_SUCCESS &&
      ghostty_formatter_terminal_new(&g_tracking_allocator, &formatter, engine->terminal) == GHOSTTY_SUCCESS) {
    bool unwrap = true;
    bool trim = true;
    ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_UNWRAP, &unwrap);
    ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_TRIM, &trim);
    ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_SELECTION, &selection);
    uint8_t *out_buf = NULL;
    size_t out_len = 0;
    if (ghostty_formatter_format_alloc(formatter, &g_tracking_allocator, &out_buf, &out_len) == GHOSTTY_SUCCESS && out_buf) {
      result = utf8_to_jstring(env, out_buf, out_len);
      ghostty_free(&g_tracking_allocator, out_buf, out_len);
    }
    ghostty_formatter_free(formatter);
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

/* The OSC 8 hyperlink at (column, row from the top of the scrollback), or null. */
JNIEXPORT jstring JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeEngineGetHyperlink(
    JNIEnv *env, jclass clazz, jlong handle, jint col, jint row) {
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(uintptr_t)handle;
  if (!engine || col < 0 || row < 0) return NULL;

  pthread_mutex_lock(&engine->lock);
  GhosttyGridRef ref;
  jstring result = NULL;
  if (engine_screen_ref(engine, col, row, &ref)) {
    size_t needed = 0;
    if (ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &needed) == GHOSTTY_OUT_OF_SPACE && needed > 0) {
      uint8_t *uri_buf = (uint8_t *)malloc(needed);
      if (uri_buf) {
        size_t written = 0;
        if (ghostty_grid_ref_hyperlink_uri(&ref, uri_buf, needed, &written) == GHOSTTY_SUCCESS) {
          result = utf8_to_jstring(env, uri_buf, written);
        }
        free(uri_buf);
      }
    }
  }
  pthread_mutex_unlock(&engine->lock);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeUnicodeWidth(
    JNIEnv *env, jclass clazz, jint codePoint) {
  (void)env;
  (void)clazz;
  return (jint)ghostty_unicode_codepoint_width((uint32_t)codePoint);
}

JNIEXPORT jint JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeGraphemeWidth(
    JNIEnv *env, jclass clazz, jstring text) {
  (void)clazz;
  if (!text) return 0;
  jsize len = (*env)->GetStringLength(env, text);
  if (len == 0) return 0;
  const jchar *chars = (*env)->GetStringChars(env, text, NULL);
  if (!chars) return 0;

  uint32_t codepoints[64];
  size_t cp_count = 0;
  for (jsize i = 0; i < len && cp_count < 64; i++) {
    uint32_t cp = chars[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
      uint32_t low = chars[i + 1];
      if (low >= 0xDC00 && low <= 0xDFFF) {
        cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        i++;
      }
    }
    codepoints[cp_count++] = cp;
  }
  (*env)->ReleaseStringChars(env, text, chars);

  uint8_t width = 0;
  ghostty_unicode_grapheme_width(codepoints, cp_count, &width);
  return (jint)width;
}

JNIEXPORT jlong JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeAllocatedBytes(
    JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return (jlong)atomic_load(&g_tracking_stats.current_allocated_bytes);
}

JNIEXPORT jlong JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeAllocatedBlocks(
    JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return (jlong)atomic_load(&g_tracking_stats.current_allocated_blocks);
}

JNIEXPORT void JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeSetAllocationFailureCap(
    JNIEnv *env, jclass clazz, jlong capBytes) {
  (void)env;
  (void)clazz;
  atomic_store(&g_tracking_stats.failure_cap_bytes, (size_t)capBytes);
}

JNIEXPORT void JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeResetAllocatorStats(
    JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  atomic_store(&g_tracking_stats.peak_allocated_bytes, atomic_load(&g_tracking_stats.current_allocated_bytes));
  atomic_store(&g_tracking_stats.total_allocations, 0);
  atomic_store(&g_tracking_stats.total_frees, 0);
}

JNIEXPORT jboolean JNICALL
Java_com_scariflabs_horus_terminal_TerminalPtyJni_nativeSetMode(
    JNIEnv *env, jclass clazz, jlong handle, jint modeNum, jboolean ansi, jboolean value) {
  (void)env;
  (void)clazz;
  SessionEngine *engine = (SessionEngine *)(intptr_t)handle;
  if (!engine || !engine->terminal) return JNI_FALSE;

  GhosttyTerminalModeConfig cfg = {
      .mode = ghostty_mode_new((uint16_t)modeNum, (bool)ansi),
      .value = (bool)value,
  };
  pthread_mutex_lock(&engine->lock);
  GhosttyResult res = ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_MODE, &cfg);
  pthread_mutex_unlock(&engine->lock);
  return res == GHOSTTY_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
