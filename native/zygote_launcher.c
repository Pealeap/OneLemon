/*
 * zygote_launcher: stands in for init when starting the guest's zygote.
 *
 * OneLemon originally shipped OneLineage's zygote_exec, which hardcodes
 * LineageOS 23.2's BOOTCLASSPATH and SYSTEMSERVERCLASSPATH (including
 * org.lineageos.platform.jar). That cannot start a Samsung One UI guest: its
 * services.jar needs Samsung's own boot class path and jars. This launcher
 * takes the class paths (and the other runtime variables) from its own
 * environment, exported by master_inner.sh from the guest's own
 * derive_classpath output, and only falls back to the old defaults when a
 * variable is not set.
 *
 * Like init's "socket zygote stream 660 root system" it creates the zygote and
 * usap pool sockets, passes them as fds 3 and 4, and closes every other fd
 * before the exec. stderr/stdout go to /data/zygote_stderr.log so a guest that
 * dies before logd is up still leaves a trace.
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define AID_SYSTEM 1000

static void make_socket(const char *path, int target_fd) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); exit(1); }
    if (fd != target_fd) {
        if (dup3(fd, target_fd, 0) < 0) { perror("dup3"); exit(1); }
        close(fd);
    }
    unlink(path);
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(target_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror(path); exit(1); }
    if (listen(target_fd, 10) < 0) { perror("listen"); exit(1); }
    chmod(path, 0660);
    chown(path, 0, AID_SYSTEM);
}

/* Variables taken from our environment when set, else these defaults. */
static const char *const kInherited[][2] = {
    {"PATH", "/system/bin:/system/xbin"},
    {"ANDROID_ROOT", "/system"},
    {"ANDROID_DATA", "/data"},
    {"ANDROID_STORAGE", "/storage"},
    {"ANDROID_ART_ROOT", "/apex/com.android.art"},
    {"ANDROID_I18N_ROOT", "/apex/com.android.i18n"},
    {"ANDROID_TZDATA_ROOT", "/apex/com.android.tzdata"},
    {"ANDROID_DNS_MODE", "local"},
    {"BOOTCLASSPATH", NULL},
    {"DEX2OATBOOTCLASSPATH", NULL},
    {"SYSTEMSERVERCLASSPATH", NULL},
    {"STANDALONE_SYSTEMSERVER_JARS", NULL},
    {"EXTERNAL_STORAGE", "/sdcard"},
    {"ASEC_MOUNTPOINT", "/mnt/asec"},
    {"ANDROID_ASSETS", "/system/app"},
};

int main(void) {
    static char *envp[32];
    int n = 0;

    setpgid(0, 0);
    make_socket("/dev/socket/zygote", 3);
    make_socket("/dev/socket/usap_pool_primary", 4);

    envp[n++] = "ANDROID_SOCKET_zygote=3";
    envp[n++] = "ANDROID_SOCKET_usap_pool_primary=4";
    for (size_t i = 0; i < sizeof(kInherited) / sizeof(kInherited[0]); i++) {
        const char *name = kInherited[i][0];
        const char *value = getenv(name);
        if (!value || !*value) value = kInherited[i][1];
        if (!value) {
            fprintf(stderr, "zygote_launcher: %s is not set\n", name);
            continue;
        }
        size_t len = strlen(name) + strlen(value) + 2;
        char *entry = malloc(len);
        snprintf(entry, len, "%s=%s", name, value);
        envp[n++] = entry;
    }
    envp[n] = NULL;

    const char *jit = getenv("ONEOS_GUEST_JIT_ZYGOTE");
    int force_jit = jit && strcmp(jit, "1") == 0;
    fprintf(stderr, "zygote_launcher: %s (ONEOS_GUEST_JIT_ZYGOTE=%s)\n",
            force_jit ? "-Xforcejitzygote" : "the image's boot image, no -Xforcejitzygote",
            jit ? jit : "unset");

    int null_fd = open("/dev/null", O_RDWR);
    int log_fd = open("/data/zygote_stderr.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (null_fd >= 0) dup3(null_fd, 0, 0);
    if (log_fd >= 0) {
        dup3(log_fd, 1, 0);
        dup3(log_fd, 2, 0);
    } else if (null_fd >= 0) {
        dup3(null_fd, 1, 0);
        dup3(null_fd, 2, 0);
    }
    if (null_fd > 4) close(null_fd);
    if (log_fd > 4) close(log_fd);
    for (int fd = 5; fd < 1024; fd++) close(fd);

    char *argv[8];
    int a = 0;
    argv[a++] = "zygote64";
    argv[a++] = "-Xzygote";
    if (force_jit) argv[a++] = "-Xforcejitzygote";
    argv[a++] = "/system/bin";
    argv[a++] = "--zygote";
    argv[a++] = "--start-system-server";
    argv[a++] = "--socket-name=zygote";
    argv[a] = NULL;

    execve("/system/bin/app_process64", argv, envp);
    perror("execve app_process64");
    return 127;
}
