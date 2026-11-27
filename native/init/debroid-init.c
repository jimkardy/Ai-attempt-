/*
 * Debroid guest init — the first program the Debian arm64 guest runs under
 * User-Mode Linux (arch=um subarch=arm64).
 *
 * It replaces the shell script that used to live at /sbin/debroid-init. The
 * kernel starts it with `init=/sbin/debroid-init`; the old version was a
 * `#!/bin/sh` script, which meant the very first userspace process in the guest
 * was Debian's glibc `dash`. That is exactly where 0.3.1 died: exit code 159
 * (128 + 31, SIGSYS), i.e. Android's app seccomp filter trapped a system call
 * made by glibc's startup (the rseq family) and killed the guest.
 *
 * This binary is built static against bionic by CI, so:
 *   - it needs no loader or shared libraries inside Debian;
 *   - it does no glibc startup of its own (bionic never calls rseq);
 *   - it sets GLIBC_TUNABLES=glibc.pthread.rseq=0 in the environment *before*
 *     handing over to bash, so every Debian program started from this shell
 *     also skips rseq.
 *
 * Jobs: mount the pseudo filesystems, grow the root ext4 to the full disk, then
 * exec the shell on the console. Nothing here is privileged: the guest runs as
 * an ordinary app process.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static void make_dir(const char *path) {
    if (mkdir(path, 0755) != 0 && errno != EEXIST) {
        /* Not fatal: the filesystem below may already be mounted and read-only. */
    }
}

static void mount_quiet(const char *source, const char *target, const char *type,
                        unsigned long flags) {
    if (mount(source, target, type, flags, NULL) != 0 && errno != EBUSY) {
        printf("[debroid] warning: %s did not mount: %s\n", target, strerror(errno));
        fflush(stdout);
    }
}

/* Runs a child and waits for it; returns its exit status (-1 when it could not run). */
static int run(const char *path, char *const argv[]) {
    fflush(stdout);
    fflush(stderr);
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        execv(path, argv);
        _exit(127);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static void print_banner(void) {
    char version[64] = "unknown";
    FILE *f = fopen("/etc/debian_version", "r");
    if (f != NULL) {
        if (fgets(version, sizeof(version), f) != NULL) {
            version[strcspn(version, "\r\n")] = 0;
        }
        fclose(f);
    }
    printf("\nDebroid guest — Debian %s / %s\n", version, "aarch64");
    printf("A real Linux kernel running as an ordinary Android app process:\n");
    printf("no root, no KVM, no hypervisor.\n\n");
    fflush(stdout);
}

int main(void) {
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("HOME", "/root", 1);
    setenv("TERM", "xterm-256color", 1);
    setenv("LANG", "C.UTF-8", 1);
    /*
     * The reason this init exists: glibc's rseq registration is the syscall
     * Android's app seccomp policy traps, and a trapped call kills the process
     * with SIGSYS before it can print anything. Ask glibc not to register.
     */
    setenv("GLIBC_TUNABLES", "glibc.pthread.rseq=0", 1);

    make_dir("/proc");
    make_dir("/sys");
    make_dir("/dev");
    make_dir("/dev/pts");
    make_dir("/dev/shm");
    make_dir("/tmp");
    make_dir("/run");

    mount_quiet("proc", "/proc", "proc", 0);
    mount_quiet("sysfs", "/sys", "sysfs", 0);
    mount_quiet("devtmpfs", "/dev", "devtmpfs", 0);
    mount_quiet("devpts", "/dev/pts", "devpts", 0);
    mount_quiet("tmpfs", "/tmp", "tmpfs", 0);
    mount_quiet("tmpfs", "/run", "tmpfs", 0);
    mount_quiet("tmpfs", "/dev/shm", "tmpfs", 0);

    chmod("/tmp", 01777);
    chmod("/run", 01777);
    chmod("/dev/shm", 01777);

    sethostname("debroid", 7);

    print_banner();

    /* The disk is as large as the VM reserved; the filesystem inside it is only
     * as large as the base image it was cloned from. Grow it to fill the disk. */
    if (access("/dev/ubda", F_OK) == 0) {
        printf("[debroid] growing the root filesystem to fill /dev/ubda…\n");
        fflush(stdout);
        char *argv[] = {(char *)"resize2fs", (char *)"/dev/ubda", NULL};
        int rc = run("/sbin/resize2fs", argv);
        if (rc != 0) {
            printf("[debroid] resize2fs exited %d — keeping the current filesystem size\n", rc);
        }
        fflush(stdout);
        char *df[] = {(char *)"df", (char *)"-h", (char *)"/", NULL};
        run("/bin/df", df);
    } else {
        printf("[debroid] warning: /dev/ubda not found; the root filesystem cannot be grown\n");
        fflush(stdout);
    }

    printf("\nType 'exit' to shut the guest down.\n\n");
    fflush(stdout);

    /* The kernel already gave us the console on fd 0/1, but re-opening
     * /dev/console gives job control and line editing a real tty. */
    int fd = open("/dev/console", O_RDWR);
    if (fd >= 0) {
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }

    char *bash[] = {(char *)"bash", (char *)"--noprofile", (char *)"--norc", (char *)"-i", NULL};
    execv("/bin/bash", bash);

    char *sh[] = {(char *)"sh", (char *)"-i", NULL};
    execv("/bin/sh", sh);

    printf("[debroid] could not start a shell (%s) — the guest has no /bin/sh?\n", strerror(errno));
    fflush(stdout);
    return 1;
}
