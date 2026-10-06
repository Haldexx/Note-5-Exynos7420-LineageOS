#define LOG_TAG "pm_client"

#include <stdlib.h>
#include <string.h>

#include <log/log.h>

typedef void (*pm_client_notifier)(void *data, int event);

struct pm_client {
    pm_client_notifier notifier;
    void *data;
    char peripheral[64];
    char name[64];
};

__attribute__((visibility("default")))
int pm_client_register(pm_client_notifier notifier, void *data, const char *peripheral,
                       const char *name, int *state, void **handle) {
    (void)state;
    if (peripheral == NULL || name == NULL || handle == NULL) {
        ALOGE("register: invalid parameters");
        return -1;
    }
    struct pm_client *client = calloc(1, sizeof(*client));
    if (client == NULL) {
        return -1;
    }
    client->notifier = notifier;
    client->data = data;
    strlcpy(client->peripheral, peripheral, sizeof(client->peripheral));
    strlcpy(client->name, name, sizeof(client->name));
    *handle = client;
    ALOGI("%s registered for %s (held up by mdm_pwron)", client->name, client->peripheral);
    return 0;
}

__attribute__((visibility("default")))
int pm_client_connect(void *handle) {
    struct pm_client *client = handle;
    if (client == NULL) {
        return -1;
    }
    ALOGI("%s vote for %s", client->name, client->peripheral);
    return 0;
}

__attribute__((visibility("default")))
int pm_client_disconnect(void *handle) {
    struct pm_client *client = handle;
    if (client == NULL) {
        return -1;
    }
    ALOGI("%s released %s", client->name, client->peripheral);
    return 0;
}

__attribute__((visibility("default")))
int pm_client_event_acknowledge(void *handle, int event) {
    (void)event;
    return handle == NULL ? -1 : 0;
}

__attribute__((visibility("default")))
int pm_client_unregister(void *handle) {
    if (handle == NULL) {
        return -1;
    }
    free(handle);
    return 0;
}
