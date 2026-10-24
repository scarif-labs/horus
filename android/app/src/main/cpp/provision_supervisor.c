/* Own one provisioning tree, including orphans, without touching the app's
 * process group. Linux/Android only. Never exec over the subreaper. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t cancelled;
static void cancel(int sig) { cancelled = sig; }
static long long millis(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) _exit(125);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Bound bookkeeping even for a fork storm. Each adopted child gets one
 * TERM and its own grace period; repeated TERM can interrupt a cleanup trap. */
#define MAX_ADOPTED 4096
static struct { pid_t pid; long long deadline; } adopted[MAX_ADOPTED];
static size_t adopted_count;
static void forget(pid_t pid) {
    for (size_t i = 0; i < adopted_count; i++) {
        if (adopted[i].pid == pid) { adopted[i] = adopted[--adopted_count]; return; }
    }
}
static void stop_child(pid_t pid) {
    long long now = millis();
    for (size_t i = 0; i < adopted_count; i++) {
        if (adopted[i].pid != pid) continue;
        if (now >= adopted[i].deadline) kill(pid, SIGKILL);
        return;
    }
    if (adopted_count == MAX_ADOPTED) { kill(pid, SIGKILL); return; }
    adopted[adopted_count].pid = pid;
    adopted[adopted_count++].deadline = now + 2000;
    kill(pid, SIGTERM);
}

/* Only direct children of this subreaper. Orphans are adopted here, even
 * when a guest has used setsid(). No UID-wide or name-based signalling. */
static int children(pid_t leader, int sig) {
    DIR *dir = opendir("/proc");
    if (!dir) return -1;
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        char *end;
        long value = strtol(entry->d_name, &end, 10);
        if (*end || value <= 0 || value == leader) continue;
        pid_t pid = (pid_t)value;
        char path[96], line[2048];
        struct stat metadata;
        snprintf(path, sizeof(path), "/proc/%ld", value);
        if (stat(path, &metadata) || metadata.st_uid != getuid()) continue;
        snprintf(path, sizeof(path), "/proc/%ld/stat", value);
        FILE *f = fopen(path, "re");
        if (!f) continue;
        char *read = fgets(line, sizeof(line), f);
        fclose(f);
        if (!read) continue;
        char *close = strrchr(line, ')');
        long ppid;
        if (!close || sscanf(close + 1, " %*c %ld", &ppid) != 1 || ppid != getpid()) continue;
        /* Verify child ownership with the kernel after reading procfs. A
         * stale/recycled proc entry must never turn into an unrelated kill. */
        siginfo_t info = {0};
        if (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT)) continue;
        count++;
        if (info.si_pid) { waitpid(pid, NULL, WNOHANG); forget(pid); continue; }
        if (sig) stop_child(pid);
    }
    closedir(dir);
    return count;
}

int main(int argc, char **argv) {
    if (argc < 2) return 125;
    pid_t parent = getppid();
    struct sigaction action = {0};
    action.sa_handler = cancel;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || sigaction(SIGINT, &action, NULL) ||
        sigaction(SIGHUP, &action, NULL) ||
        prctl(PR_SET_CHILD_SUBREAPER, 1) ||
        prctl(PR_SET_PDEATHSIG, SIGTERM) || setsid() < 0) return 125;
    if (parent == 1 || getppid() != parent) return 143;
    /* Exec preserves an inherited SIGCHLD ignore disposition. Reset it so
     * waitable children pin their identities until this owner reaps them. */
    struct sigaction child_action = {0};
    child_action.sa_handler = SIG_DFL;
    sigemptyset(&child_action.sa_mask);
    if (sigaction(SIGCHLD, &child_action, NULL)) return 125;

    /* Block cancellation across fork: child restores defaults and the mask
     * before exec; parent verifies the private group before signalling it. */
    sigset_t blocked, previous;
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGTERM); sigaddset(&blocked, SIGINT); sigaddset(&blocked, SIGHUP);
    if (sigprocmask(SIG_BLOCK, &blocked, &previous)) return 125;
    pid_t leader = fork();
    if (leader < 0) return 125;
    if (!leader) {
        if (setpgid(0, 0)) _exit(125);
        action.sa_handler = SIG_DFL;
        sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL); sigaction(SIGHUP, &action, NULL);
        sigprocmask(SIG_SETMASK, &previous, NULL);
        execvp(argv[1], argv + 1);
        _exit(127);
    }
    /* Both sides set the same group. EACCES means exec won the race; verify
     * the group rather than assuming it belongs to us. A zombie stays pinned
     * until cleanup ends, so this pgid cannot name a later unrelated session. */
    int grouped = setpgid(leader, leader) == 0 || getpgid(leader) == leader;
    sigprocmask(SIG_SETMASK, &previous, NULL);
    if (!grouped) { kill(leader, SIGKILL); waitpid(leader, NULL, 0); return 125; }

    long long deadline = 0;
    int stopping = 0, forced = 0;
    siginfo_t info;
    for (;;) {
        info = (siginfo_t){0};
        if (waitid(P_PID, (id_t)leader, &info, WEXITED | WNOHANG | WNOWAIT) < 0 && errno != EINTR) return 125;
        int remaining = (stopping || cancelled || info.si_pid) ? children(leader, stopping) : 0;
        if (remaining < 0) {
            /* Fail closed: do not claim completion without observing adopted
             * children. Keep ownership and retry after terminating the group. */
            cancelled = SIGTERM;
        }
        if (!stopping && (cancelled || info.si_pid)) {
            stopping = 1;
            kill(-leader, SIGTERM);
            deadline = millis() + 2000;
        }
        if (stopping && info.si_pid && remaining == 0) break;
        if (stopping && !forced && millis() >= deadline) {
            forced = 1;
            kill(-leader, SIGKILL);
        }
        /* After KILL, retain ownership until the kernel releases every child.
         * Uninterruptible tasks must block retries rather than lose ownership. */
        struct timespec pause = {0, 10000000};
        nanosleep(&pause, NULL);
    }
    int status;
    while (waitpid(leader, &status, 0) < 0) if (errno != EINTR) return 125;
    if (cancelled) return 128 + cancelled;
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    if (!WIFEXITED(status)) return 125;
    return WEXITSTATUS(status);
}
