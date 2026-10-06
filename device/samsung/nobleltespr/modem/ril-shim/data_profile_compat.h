#ifndef DATA_PROFILE_COMPAT_H
#define DATA_PROFILE_COMPAT_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>

struct profile_legacy {
    int32_t id;
    const char *apn, *protocol;
    int32_t auth;
    const char *user, *password;
    int32_t type, max_time, max_count, wait_time, enabled;
};
struct profile_v15 {
    int32_t id;
    const char *apn, *protocol, *roaming_protocol;
    int32_t auth;
    const char *user, *password;
    int32_t type, max_time, max_count, wait_time, enabled;
    int32_t samsung_reserved;
    uint32_t apn_types, bearer;
    int32_t mtu;
    const char *mvno_type, *mvno_match;
    int32_t preferred, persistent, mtu4, mtu6;
};
_Static_assert(sizeof(void *) == 8, "arm64 ABI required");
_Static_assert(sizeof(struct profile_legacy) == 72, "legacy profile size");
_Static_assert(sizeof(struct profile_v15) == 128, "HAL profile size");
_Static_assert(offsetof(struct profile_legacy, user) == 32, "vendor username");
_Static_assert(offsetof(struct profile_v15, auth) == 32, "HAL auth");

static const char *protocol_legacy(const char *p)
{
    if (!p) return NULL;
    if (!strcmp(p, "0") || !strcmp(p, "IP")) return "IP";
    if (!strcmp(p, "1") || !strcmp(p, "IPV6")) return "IPV6";
    if (!strcmp(p, "2") || !strcmp(p, "IPV4V6")) return "IPV4V6";
    if (!strcmp(p, "3") || !strcmp(p, "PPP")) return "PPP";
    return NULL;
}

static int profile_to_legacy(const void *src, struct profile_legacy *d)
{
    if (!src) return 0;
    const struct profile_legacy *old = src;
    const char *protocol = protocol_legacy(old->protocol);
    if (!protocol) return 0;
    if (old->protocol[0] >= '0' && old->protocol[0] <= '3') {
        const struct profile_v15 *s = src;
        *d = (struct profile_legacy){s->id, s->apn, protocol, s->auth,
            s->user, s->password, s->type, s->max_time, s->max_count,
            s->wait_time, s->enabled};
    } else {
        *d = *old;
        d->protocol = protocol;
    }
    if (!d->apn) d->apn = "";
    if (!d->user) d->user = "";
    if (!d->password) d->password = "";
    return 1;
}

struct attach_legacy {
    const char *apn, *protocol;
    int32_t auth;
    const char *user, *password, *roaming_protocol;
    int32_t modem_cognitive;
};
struct attach_v15_prefix {
    const char *apn, *protocol, *roaming_protocol;
    int32_t auth;
    const char *user, *password;
};
_Static_assert(sizeof(struct attach_legacy) == 56, "Samsung attach size");
static int attach_to_legacy(const void *src, size_t len, struct attach_legacy *d)
{
    if (!src) return 0;
    memset(d, 0, sizeof(*d));
    if (len == 120) {
        const struct attach_v15_prefix *s = src;
        d->apn = s->apn;
        d->protocol = protocol_legacy(s->protocol);
        d->roaming_protocol = protocol_legacy(s->roaming_protocol);
        d->auth = s->auth;
        d->user = s->user;
        d->password = s->password;
        memcpy(&d->modem_cognitive, (const uint8_t *)src + 56, 4);
    } else if (len == 40 || len == 56) {
        memcpy(d, src, len);
        d->protocol = protocol_legacy(d->protocol);
        d->roaming_protocol = len == 56 ? protocol_legacy(d->roaming_protocol) : d->protocol;
    } else return 0;
    if (!d->protocol || !d->roaming_protocol) return 0;
    if (!d->apn) d->apn = "";
    if (!d->user) d->user = "";
    if (!d->password) d->password = "";
    return 1;
}
#endif
