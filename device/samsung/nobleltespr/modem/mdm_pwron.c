#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <limits.h>
#include <signal.h>

static volatile sig_atomic_t stopping;
static void stop_hold(int sig) { (void)sig; stopping = 1; }

#define SUBSYS_CODE    0xCD
#define SUBSYS_POWERUP _IO(SUBSYS_CODE, 1)

int main(int argc, char **argv)
{
    const char *dev = (argc > 1) ? argv[1] : "/dev/subsys_esoc0";
    unsigned int hold = 0; /* zero/default: retain the reference until stopped */
    if (argc > 2) {
        char *end;
        errno = 0;
        long value = strtol(argv[2], &end, 10);
        if (errno || *end || end == argv[2] || value < 0 || value > UINT_MAX) {
            fprintf(stderr, "invalid hold seconds: %s\n", argv[2]);
            return 2;
        }
        hold = (unsigned int)value;
    }
    signal(SIGTERM, stop_hold);
    signal(SIGINT, stop_hold);

    printf("mdm_pwron: dev=%s  SUBSYS_POWERUP=0x%lx\n",
           dev, (unsigned long)SUBSYS_POWERUP);
    fflush(stdout);

    int fd = open(dev, O_RDWR);
    if (fd < 0) {
        printf("  open failed: %s\n", strerror(errno));
        return 1;
    }
    printf("  opened (subsystem_get taken)\n");
    fflush(stdout);

    if (ioctl(fd, SUBSYS_POWERUP) < 0) {
        printf("  SUBSYS_POWERUP failed: %s\n", strerror(errno));
        close(fd);
        return 1;
    }
    printf("  SUBSYS_POWERUP OK -- modem power sequence started\n");
    printf("  holding reference: %u seconds (0 = until stopped)\n", hold);
    fflush(stdout);

    if (hold) {
        while (!stopping && hold) hold = sleep(hold);
    } else {
        while (!stopping) sleep(1);
    }
    close(fd);
    printf("  released\n");
    return 0;
}
