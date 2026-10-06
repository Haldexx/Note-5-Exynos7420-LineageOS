#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

#include <android/log.h>
#include <sys/system_properties.h>
#include "data_profile_compat.h"

#define LOG_TAG "RILSHIM"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define REAL_RIL "/vendor/lib64/libsec-ril-mdm.so"

typedef void *RIL_Token;
typedef enum { RIL_E_SUCCESS = 0 } RIL_Errno;
typedef void (*RIL_TimedCallback)(void *param);

struct RIL_Env {
    void (*OnRequestComplete)(RIL_Token t, RIL_Errno e, void *response, size_t responselen);
    void (*OnUnsolicitedResponse)(int unsolResponse, const void *data, size_t datalen);
    void (*RequestTimedCallback)(RIL_TimedCallback cb, void *param, const struct timeval *relativeTime);
    void (*OnRequestAck)(RIL_Token t);
};

typedef struct {
    int version;
    void (*onRequest)(int request, void *data, size_t datalen, RIL_Token t);
    int (*onStateRequest)(void);
    int (*supports)(int requestCode);
    void (*onCancel)(RIL_Token t);
    const char *(*getVersion)(void);
} RIL_RadioFunctions;

#define RIL_REQUEST_GET_SIM_STATUS            1
#define RIL_REQUEST_SIGNAL_STRENGTH          19
#define RIL_REQUEST_VOICE_REGISTRATION_STATE 20
#define RIL_REQUEST_DATA_REGISTRATION_STATE  21
#define RIL_REQUEST_OPERATOR                 22
#define RIL_REQUEST_SETUP_DATA_CALL          27
#define RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL 47
#define RIL_REQUEST_DATA_CALL_LIST           57
#define RIL_REQUEST_VOICE_RADIO_TECH         108
#define RIL_REQUEST_SIM_AUTHENTICATION      125
#define RIL_REQUEST_SET_PREFERRED_DATA_MODEM 204  /* libril_sem RadioConfig 1.1 */
#define RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED 1000
#define RIL_UNSOL_SIGNAL_STRENGTH          1009
#define RIL_UNSOL_DATA_CALL_LIST_CHANGED   1010

static const struct RIL_Env *real_env;
static struct RIL_Env       shim_env;
static const RIL_RadioFunctions *real_funcs;
static int debug;

#define M_SIGNAL   0x01
#define M_CARD     0x02
#define M_REGSTATE 0x04
#define M_OPERATOR 0x08
#define M_DATACALL 0x10
#define M_WRAP     0x20   /* substitute RIL_RadioFunctions to see onRequest */
#define M_PROFILE  0x40   /* HAL 1.4 data profiles -> vendor v8 */
#define M_VOICE_RAT 0x80  /* query live voice registration, not model-family RAT */
#define M_DCREQ   0x100   /* SETUP_DATA_CALL request: 19 strings -> vendor's 7 */
#define M_NETSEL  0x200   /* SET_NETWORK_SELECTION_MANUAL: struct -> bare PLMN string */
#define M_DDS     0x400   /* SET_PREFERRED_DATA_MODEM: the only modem is the data modem */
#define M_SIMAUTH 0x800   /* SIM_AUTHENTICATION reply: hex text -> base64 */
#define M_INITGATE 0x1000 /* keep libril out of the blob until its managers exist */
#define M_ALL      0x1fff
static unsigned mask = M_ALL;

static uint8_t funcs_buf[512] __attribute__((aligned(16)));
#define ONREQUEST_OFF 8
#define ONSTATEREQUEST_OFF 16  /* libril_sem radioStateChangedInd: ldr x8, [x8, #0x10]; blr x8 */

#define RIL_E_RADIO_NOT_AVAILABLE 1
#define RADIO_STATE_UNAVAILABLE   1
#define PM_HANDLER_ID_OFF      0x10  /* Handler::Handler(): str w8, [x0, #0x10] */
static atomic_int vendor_ready;
static atomic_int gate_refused;
static void *const *pm_instance;  /* &PowerManager::mInstance in the blob, for the log only */

#define NTOK 1024
static struct { RIL_Token tok; int req; } tokmap[NTOK];
static int tokpos;
static pthread_mutex_t tokmtx = PTHREAD_MUTEX_INITIALIZER;

static void tok_put(RIL_Token t, int req)
{
    pthread_mutex_lock(&tokmtx);
    tokmap[tokpos].tok = t;
    tokmap[tokpos].req = req;
    tokpos = (tokpos + 1) % NTOK;
    pthread_mutex_unlock(&tokmtx);
}

static int tok_get(RIL_Token t)
{
    int i, idx, req = -1;
    pthread_mutex_lock(&tokmtx);
    for (i = 1; i <= NTOK; i++) {
        idx = (tokpos - i + NTOK) % NTOK;
        if (tokmap[idx].tok == t) { req = tokmap[idx].req; break; }
    }
    pthread_mutex_unlock(&tokmtx);
    return req;
}

enum { SS_V10 = 56, SS_V12 = 80, SS_V14 = 132, SS_TDSCDMA_RSCP = 15 };

static pthread_mutex_t sigmtx = PTHREAD_MUTEX_INITIALIZER;
static int32_t last_sig[SS_V10 / 4];
static int have_last_sig;

static void sig_remember(const int32_t *s)
{
    pthread_mutex_lock(&sigmtx);
    memcpy(last_sig, s, sizeof(last_sig));
    have_last_sig = 1;
    pthread_mutex_unlock(&sigmtx);
}

static int sig_recall(int32_t *out)
{
    int ok;
    pthread_mutex_lock(&sigmtx);
    ok = have_last_sig;
    if (ok)
        memcpy(out, last_sig, sizeof(last_sig));
    pthread_mutex_unlock(&sigmtx);
    return ok;
}

static void expand_signal_strength(const int32_t *s, int32_t *d, size_t dints)
{
    size_t i;
    for (i = 0; i < dints; i++)
        d[i] = INT_MAX;

    d[0]  = s[0];          /* GSM.signalStrength  */
    d[1]  = s[1];          /* GSM.bitErrorRate    */
    d[2]  = INT_MAX;       /* GSM.timingAdvance - not reported by this blob */
    d[3]  = s[2];          /* CDMA.dbm            */
    d[4]  = s[3];          /* CDMA.ecio           */
    d[5]  = s[4];          /* EVDO.dbm            */
    d[6]  = s[5];          /* EVDO.ecio           */
    d[7]  = s[6];          /* EVDO.signalNoiseRatio */
    d[8]  = s[7];          /* LTE.signalStrength  */
    d[9]  = s[8];          /* LTE.rsrp            */
    d[10] = s[9];          /* LTE.rsrq            */
    d[11] = s[10];         /* LTE.rssnr           */
    d[12] = s[11];         /* LTE.cqi             */
    d[13] = s[12];         /* LTE.timingAdvance   */
    if (dints > SS_TDSCDMA_RSCP)
        d[SS_TDSCDMA_RSCP] = s[13];     /* TD-SCDMA.rscp */
}

enum { DC_SRC = 64, DC_DST = 0xcaf08, DC_MTU = 1500, DC_MTU4_OFF = 76, DC_MTU6_OFF = 80 };

static void widen_data_calls(const uint8_t *s, uint8_t *d, size_t n)
{
    size_t i;

    memset(d, 0, n * DC_DST);
    for (i = 0; i < n; i++) {
        uint8_t *e = d + i * DC_DST;
        memcpy(e, s + i * DC_SRC, DC_SRC);
        *(int32_t *)(e + DC_MTU4_OFF) = DC_MTU;
    }
}

static uint8_t *maybe_widen_data_calls(const void *response, size_t len, size_t *out_len)
{
    size_t n;
    uint8_t *buf;

    if (response == NULL || len == 0 || len % DC_SRC != 0)
        return NULL;

    n = len / DC_SRC;
    buf = malloc(n * DC_DST);
    if (buf == NULL)
        return NULL;

    widen_data_calls((const uint8_t *)response, buf, n);
    *out_len = n * DC_DST;
    return buf;
}

static void open_gate(void)
{
    const void *pm = pm_instance ? *pm_instance : NULL;
    int32_t id = -1;

    if (atomic_exchange(&vendor_ready, 1))
        return;
    if (pm != NULL)
        memcpy(&id, (const uint8_t *)pm + PM_HANDLER_ID_OFF, sizeof(id));
    if (id == 1 || pm_instance == NULL)
        ALOGI("vendor RIL ready: %d early requests refused, power manager handler %d",
              atomic_load(&gate_refused), (int)id);
    else
        ALOGE("vendor RIL ready but its power manager is handler %d, not 1: the startup "
              "race hit anyway; RADIO_POWER will be dropped until rild restarts", (int)id);
}

static void shim_onUnsol(int id, const void *data, size_t len)
{
    if (debug)
        ALOGI("unsol id=%d len=%zu", id, len);

    if (id == RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED)
        open_gate();

    if ((mask & M_DATACALL) && id == RIL_UNSOL_DATA_CALL_LIST_CHANGED) {
        size_t out_len = 0;
        uint8_t *buf = maybe_widen_data_calls(data, len, &out_len);
        if (buf != NULL) {
            real_env->OnUnsolicitedResponse(id, buf, out_len);
            free(buf);
            return;
        }
    }

    if ((mask & M_SIGNAL) && id == RIL_UNSOL_SIGNAL_STRENGTH && data != NULL && len == SS_V10) {
        int32_t out[SS_V14 / 4];
        const int32_t *s = (const int32_t *)data;

        if (debug)
            ALOGI("sig v10 raw: %d %d %d %d %d %d %d %d %d %d %d %d %d %d",
                  s[0], s[1], s[2], s[3], s[4], s[5], s[6],
                  s[7], s[8], s[9], s[10], s[11], s[12], s[13]);

        sig_remember(s);
        expand_signal_strength(s, out, SS_V14 / 4);
        real_env->OnUnsolicitedResponse(id, out, SS_V14);
        return;
    }

    real_env->OnUnsolicitedResponse(id, data, len);
}

enum {
    CS_HDR       = 24,
    CS_APPS      = 8,
    CS_APP_SRC   = 64,   /* Samsung RIL_AppStatus */
    CS_APP_DST   = 48,   /* libril RIL_AppStatus  */
    CS_APP_COMMON = 44,  /* app_type .. pin2      */
    CS_SRC       = CS_HDR + CS_APPS * CS_APP_SRC,              /* 536 */
    CS_DST       = CS_HDR + CS_APPS * CS_APP_DST + 32          /* 440 */
};

enum { CS_AID_OFF = 16, CS_LABEL_OFF = 24 };

static void narrow_card_status(const uint8_t *s, uint8_t *d)
{
    int i;

    memset(d, 0, CS_DST);
    memcpy(d, s, CS_HDR);
    for (i = 0; i < CS_APPS; i++) {
        const uint8_t *se = s + CS_HDR + i * CS_APP_SRC;
        uint8_t *de = d + CS_HDR + i * CS_APP_DST;
        void *aid, *label;

        memcpy(de, se, CS_APP_COMMON);

        memcpy(&aid,   se + CS_AID_OFF,   sizeof(aid));
        memcpy(&label, se + CS_LABEL_OFF, sizeof(label));
        memcpy(de + CS_AID_OFF,   &aid,   sizeof(aid));
        memcpy(de + CS_LABEL_OFF, &label, sizeof(label));

        if (debug && i < 3) {
            const int32_t *w = (const int32_t *)se;
            ALOGI("card app[%d] words: %d %d %d %d | %08x %08x %08x %08x |"
                  " %d %d %d %d | %08x %08x %08x %08x  aid=%p label=%p",
                  i, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7],
                  w[8], w[9], w[10], w[11], w[12], w[13], w[14], w[15],
                  aid, label);
        }
    }
}

enum { REG_VOICE = 1120, REG_DATA = 1136, REG_STRUCT = REG_DATA, RADIO_TECH_LTE = 14,
       REG_LTE_VOPS = 1080 /* LteVopsInfo {isVopsSupported, isEmcBearerSupported} */ };

static void fill_lte_cell_identity(uint8_t *d, size_t off, char **s, int n,
                                   int lac_idx, int cid_idx);
static void plmn_remember(const char *numeric);

static int atoi_or(const char *s, int dflt)
{
    return (s == NULL || *s == '\0') ? dflt : (int)strtol(s, NULL, 10);
}

static uint8_t prop_is_one(const char *name)
{
    char v[PROP_VALUE_MAX] = {0};

    return __system_property_get(name, v) > 0 && v[0] == '1' && v[1] == '\0';
}

static void voice_strings_to_struct(char **s, int n, uint8_t *d)
{
    int regstate, rat;

    memset(d, 0, REG_STRUCT);
    regstate = atoi_or(n > 0 ? s[0] : NULL, 4); /* 4 = unknown */
    rat      = atoi_or(n > 3 ? s[3] : NULL, 0);
    *(int32_t *)(d + 0)  = regstate;
    *(int32_t *)(d + 4)  = rat;
    *(int32_t *)(d + 8)  = atoi_or(n > 7  ? s[7]  : NULL, 0);
    *(int32_t *)(d + 12) = atoi_or(n > 10 ? s[10] : NULL, 0);
    *(int32_t *)(d + 16) = atoi_or(n > 11 ? s[11] : NULL, 0);
    *(int32_t *)(d + 20) = atoi_or(n > 12 ? s[12] : NULL, 0);
    *(int32_t *)(d + 24) = atoi_or(n > 13 ? s[13] : NULL, 0);

    if ((regstate == 1 || regstate == 5) && rat == RADIO_TECH_LTE)
        fill_lte_cell_identity(d, 32, s, n, 1, 2);
}

static int32_t last_data_rat;   /* 0 = RADIO_TECH_UNKNOWN */

static pthread_mutex_t plmnmtx = PTHREAD_MUTEX_INITIALIZER;
static char last_plmn[8];
static int is_plmn(const char *s);   /* defined with the OPERATOR helpers */

static void plmn_remember(const char *numeric)
{
    if (numeric == NULL || !is_plmn(numeric))
        return;
    pthread_mutex_lock(&plmnmtx);
    snprintf(last_plmn, sizeof(last_plmn), "%s", numeric);
    pthread_mutex_unlock(&plmnmtx);
}

enum { CELL_TYPE_LTE = 3, CI_LTE_MCC = 8, CI_LTE_MNC = 12,
       CI_LTE_CI = 16, CI_LTE_TAC = 24 };

static void fill_lte_cell_identity(uint8_t *d, size_t off, char **s, int n,
                                   int lac_idx, int cid_idx)
{
    char plmn[8];
    size_t mcclen;

    pthread_mutex_lock(&plmnmtx);
    snprintf(plmn, sizeof(plmn), "%s", last_plmn);
    pthread_mutex_unlock(&plmnmtx);

    if (!is_plmn(plmn))
        return;

    *(int32_t *)(d + off) = CELL_TYPE_LTE;

    mcclen = 3;
    memcpy(d + off + CI_LTE_MCC, plmn, mcclen);
    d[off + CI_LTE_MCC + mcclen] = '\0';
    snprintf((char *)(d + off + CI_LTE_MNC), 4, "%s", plmn + mcclen);

    if (n > cid_idx && s[cid_idx] != NULL && *s[cid_idx] != '\0')
        *(int32_t *)(d + off + CI_LTE_CI) = (int32_t)strtol(s[cid_idx], NULL, 16);
    if (n > lac_idx && s[lac_idx] != NULL && *s[lac_idx] != '\0')
        *(int32_t *)(d + off + CI_LTE_TAC) = (int32_t)strtol(s[lac_idx], NULL, 16);
}

static void data_strings_to_struct(char **s, int n, uint8_t *d)
{
    int maxcalls, regstate, rat;

    memset(d, 0, REG_STRUCT);
    regstate = atoi_or(n > 0 ? s[0] : NULL, 4);
    rat = atoi_or(n > 3 ? s[3] : NULL, 0);
    if (regstate == 1 || regstate == 5)
        last_data_rat = rat;
    *(int32_t *)(d + 0) = regstate;
    *(int32_t *)(d + 4) = rat;
    *(int32_t *)(d + 8) = atoi_or(n > 4 ? s[4] : NULL, 0);
    maxcalls = atoi_or(n > 5 ? s[5] : NULL, 0);
    *(int32_t *)(d + 12) = maxcalls > 0 ? maxcalls : 1;

    if ((regstate == 1 || regstate == 5) && rat == RADIO_TECH_LTE) {
        fill_lte_cell_identity(d, 16, s, n, 1, 2);
        d[REG_LTE_VOPS]     = prop_is_one("ril.ims.ltevoicesupport");
        d[REG_LTE_VOPS + 1] = prop_is_one("ril.ims.ecsupport");
    }
}

static int is_plmn(const char *s)
{
    size_t i, n;

    if (s == NULL)
        return 0;
    n = strlen(s);
    if (n < 5 || n > 6)
        return 0;
    for (i = 0; i < n; i++)
        if (s[i] < '0' || s[i] > '9')
            return 0;
    return 1;
}

static void operator_to_three(char **s, int n, char **out)
{
    int i;

    out[0] = n > 0 ? s[0] : NULL;
    out[1] = n > 1 ? s[1] : NULL;
    out[2] = NULL;
    for (i = 2; i < n; i++) {
        if (is_plmn(s[i])) { out[2] = s[i]; break; }
    }
    if (out[2] == NULL && n > 2)
        out[2] = s[2];
}

typedef struct { int sw1; int sw2; char *simResponse; } sim_io_response;

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int hex_to_base64(const char *in, char *out, size_t outlen)
{
    static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    uint8_t bin[256];
    size_t n = in ? strlen(in) : 0, i, o = 0;

    if (n < 2 || n % 2 || n / 2 > sizeof(bin) || (n / 2 + 2) / 3 * 4 + 1 > outlen)
        return 0;
    for (i = 0; i < n; i += 2) {
        int hi = hexval(in[i]), lo = hexval(in[i + 1]);
        if (hi < 0 || lo < 0)
            return 0;
        bin[i / 2] = (uint8_t)(hi << 4 | lo);
    }
    n /= 2;
    for (i = 0; i < n; i += 3) {
        uint32_t v = (uint32_t)bin[i] << 16 | (i + 1 < n ? bin[i + 1] << 8 : 0) | (i + 2 < n ? bin[i + 2] : 0);
        out[o++] = b64[v >> 18 & 63];
        out[o++] = b64[v >> 12 & 63];
        out[o++] = i + 1 < n ? b64[v >> 6 & 63] : '=';
        out[o++] = i + 2 < n ? b64[v & 63] : '=';
    }
    out[o] = 0;
    return 1;
}

static void shim_onRequestComplete(RIL_Token t, RIL_Errno e, void *response, size_t responselen)
{
    int req = tok_get(t);

    if (debug)
        ALOGI("complete req=%d err=%d len=%zu", req, (int)e, responselen);

    if ((mask & M_VOICE_RAT) && req == RIL_REQUEST_VOICE_RADIO_TECH &&
        e == RIL_E_SUCCESS) {
        int32_t rat = 0;
        if (response && responselen >= 4 * sizeof(char *) &&
            responselen <= 21 * sizeof(char *) && responselen % sizeof(char *) == 0) {
            char **s = response;
            int reg = atoi_or(s[0], 4);
            if (reg == 1 || reg == 5) rat = atoi_or(s[3], 0);
            if (rat < 0 || rat > 20) rat = 0;
            if (debug) ALOGI("voice RAT from live registration: reg=%d rat=%d", reg, rat);
            real_env->OnRequestComplete(t, e, &rat, sizeof(rat));
        } else {
            real_env->OnRequestComplete(t, (RIL_Errno)66, NULL, 0);
        }
        return;
    }

    if ((mask & M_CARD) && req == RIL_REQUEST_GET_SIM_STATUS && response != NULL &&
        responselen == CS_SRC) {
        uint8_t out[CS_DST];
        narrow_card_status((const uint8_t *)response, out);
        if (debug) {
            const int32_t *h = (const int32_t *)response;
            ALOGI("card: state=%d upin=%d gsm=%d cdma=%d ims=%d napps=%d -> %d bytes",
                  h[0], h[1], h[2], h[3], h[4], h[5], CS_DST);
        }
        real_env->OnRequestComplete(t, e, out, CS_DST);
        return;
    }

    if ((mask & M_SIGNAL) && req == RIL_REQUEST_SIGNAL_STRENGTH) {
        int32_t out[SS_V14 / 4];
        int32_t cached[SS_V10 / 4];

        if (e == RIL_E_SUCCESS && response != NULL && responselen == SS_V10) {
            sig_remember((const int32_t *)response);
            expand_signal_strength((const int32_t *)response, out, SS_V14 / 4);
            real_env->OnRequestComplete(t, e, out, SS_V14);
            return;
        }
        if (e != RIL_E_SUCCESS && sig_recall(cached)) {
            if (debug)
                ALOGI("polled signal strength failed (err=%d); replaying last good",
                      (int)e);
            expand_signal_strength(cached, out, SS_V14 / 4);
            real_env->OnRequestComplete(t, RIL_E_SUCCESS, out, SS_V14);
            return;
        }
    }

    if ((mask & M_DATACALL) &&
        (req == RIL_REQUEST_SETUP_DATA_CALL || req == RIL_REQUEST_DATA_CALL_LIST) &&
        e == RIL_E_SUCCESS) {
        size_t out_len = 0;
        uint8_t *buf = maybe_widen_data_calls(response, responselen, &out_len);
        if (buf != NULL) {
            if (debug)
                ALOGI("datacall req=%d %zu -> %zu (status=%d cid=%d ifname='%s')",
                      req, responselen, out_len, *(int32_t *)buf,
                      *(int32_t *)(buf + 8),
                      *(char **)(buf + 24) ? *(char **)(buf + 24) : "");
            real_env->OnRequestComplete(t, e, buf, out_len);
            free(buf);
            return;
        }
    }

    if ((mask & M_REGSTATE) && e == RIL_E_SUCCESS && response != NULL &&
        ((req == RIL_REQUEST_VOICE_REGISTRATION_STATE && responselen != REG_VOICE) ||
         (req == RIL_REQUEST_DATA_REGISTRATION_STATE && responselen != REG_DATA))) {
        uint8_t out[REG_STRUCT];
        size_t out_len = req == RIL_REQUEST_VOICE_REGISTRATION_STATE ? REG_VOICE : REG_DATA;
        char **s = (char **)response;
        int n = (int)(responselen / sizeof(char *));

        if (req == RIL_REQUEST_VOICE_REGISTRATION_STATE)
            voice_strings_to_struct(s, n, out);
        else
            data_strings_to_struct(s, n, out);

        if (debug)
            ALOGI("reg req=%d %d strings -> regState=%d rat=%d",
                  req, n, *(int32_t *)out, *(int32_t *)(out + 4));

        real_env->OnRequestComplete(t, e, out, out_len);
        return;
    }

    if ((mask & M_OPERATOR) && req == RIL_REQUEST_OPERATOR && e == RIL_E_SUCCESS &&
        responselen != 3 * sizeof(char *)) {
        char *out[3] = { NULL, NULL, NULL };
        char **s = (char **)response;
        int n = response ? (int)(responselen / sizeof(char *)) : 0;

        if (n > 0)
            operator_to_three(s, n, out);
        plmn_remember(out[2]);
        if (debug)
            ALOGI("operator: %d strings -> long='%s' short='%s' numeric='%s'",
                  n, out[0] ? out[0] : "", out[1] ? out[1] : "",
                  out[2] ? out[2] : "");
        real_env->OnRequestComplete(t, e, out, sizeof(out));
        return;
    }

    if ((mask & M_SIMAUTH) && req == RIL_REQUEST_SIM_AUTHENTICATION && e == RIL_E_SUCCESS &&
        response != NULL && responselen == sizeof(sim_io_response)) {
        sim_io_response out = *(sim_io_response *)response;
        char b64[352];

        if (hex_to_base64(out.simResponse, b64, sizeof(b64))) {
            if (debug)
                ALOGI("sim_authentication: sw %02x%02x, %zu hex chars -> base64", out.sw1, out.sw2,
                      strlen(out.simResponse));
            out.simResponse = b64;
            real_env->OnRequestComplete(t, e, &out, sizeof(out));
            return;
        }
    }

    real_env->OnRequestComplete(t, e, response, responselen);
}

static const char *protocol_name(const char *v)
{
    if (v == NULL || *v == '\0')
        return "IP";
    if ((*v >= 'A' && *v <= 'Z') || (*v >= 'a' && *v <= 'z'))
        return v;
    switch (*v) {
    case '0': return "IP";
    case '1': return "IPV6";
    case '2': return "IPV4V6";
    case '3': return "PPP";
    default:  return NULL;
    }
}

enum { NETSEL_STRUCT = 12, NETSEL_PLMN_OFF = 4, NETSEL_PLMN_MAX = 8 };

static int shim_onStateRequest(void)
{
    if ((mask & M_INITGATE) && !atomic_load(&vendor_ready))
        return RADIO_STATE_UNAVAILABLE;
    return real_funcs->onStateRequest();
}

static void shim_onRequest(int request, void *data, size_t datalen, RIL_Token t)
{
    if ((mask & M_INITGATE) && !atomic_load(&vendor_ready)) {
        atomic_fetch_add(&gate_refused, 1);
        if (debug)
            ALOGI("request id=%d refused: vendor RIL still starting", request);
        real_env->OnRequestComplete(t, (RIL_Errno)RIL_E_RADIO_NOT_AVAILABLE, NULL, 0);
        return;
    }
    tok_put(t, request);
    if (debug)
        ALOGI("request id=%d len=%zu", request, datalen);
    if (debug && request == RIL_REQUEST_SETUP_DATA_CALL && data &&
        datalen % sizeof(char *) == 0) {
        char **s = (char **)data;
        size_t n = datalen / sizeof(char *), i;
        ALOGI("setup_data_call: %zu strings", n);
        for (i = 0; i < n; ++i)
            ALOGI("  [%2zu] %s", i, (i == 3 || i == 4) ? "<redacted>" : (s[i] ? s[i] : "(null)"));
    }
    if (debug && request == RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL && data) {
        const unsigned char *b = (const unsigned char *)data;
        size_t n = datalen < 16 ? datalen : 16, i;
        char hex[16 * 3 + 1], txt[17];

        for (i = 0; i < n; ++i) {
            snprintf(hex + i * 3, 4, "%02x ", b[i]);
            txt[i] = (b[i] >= 32 && b[i] < 127) ? (char)b[i] : '.';
        }
        hex[n ? n * 3 - 1 : 0] = 0;
        txt[n] = 0;
        ALOGI("set_network_selection_manual: len=%zu bytes=[%s] as-text=\"%s\"",
              datalen, hex, txt);
    }
    if ((mask & M_NETSEL) && request == RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL &&
        data != NULL && datalen == NETSEL_STRUCT) {
        char plmn[NETSEL_PLMN_MAX + 1];

        memcpy(plmn, (const char *)data + NETSEL_PLMN_OFF, NETSEL_PLMN_MAX);
        plmn[NETSEL_PLMN_MAX] = 0;
        if (plmn[0] == 0) {
            ALOGE("set_network_selection_manual: no operator numeric in the request");
            real_env->OnRequestComplete(t, (RIL_Errno)44, NULL, 0);
            return;
        }
        if (debug)
            ALOGI("set_network_selection_manual: passing plmn '%s' as a bare string", plmn);
        real_funcs->onRequest(request, plmn, strlen(plmn) + 1, t);
        return;
    }
    if ((mask & M_VOICE_RAT) && request == RIL_REQUEST_VOICE_RADIO_TECH) {
        real_funcs->onRequest(RIL_REQUEST_VOICE_REGISTRATION_STATE, NULL, 0, t);
        return;
    }
    if ((mask & M_DDS) && request == RIL_REQUEST_SET_PREFERRED_DATA_MODEM) {
        int32_t modem = -1;

        if (data != NULL && datalen >= sizeof(modem))
            memcpy(&modem, data, sizeof(modem));
        if (debug)
            ALOGI("set_preferred_data_modem: modem %d", (int)modem);
        real_env->OnRequestComplete(t, modem == 0 ? RIL_E_SUCCESS : (RIL_Errno)44, NULL, 0);
        return;
    }
    if ((mask & M_DCREQ) && request == RIL_REQUEST_SETUP_DATA_CALL && data &&
        datalen % sizeof(char *) == 0 && datalen / sizeof(char *) >= 7) {
        char **in = (char **)data;
        size_t n = datalen / sizeof(char *);
        char *out[7];
        char rat[12];
        const char *proto;

        proto = protocol_name(n > 6 ? in[6] : NULL);
        if (proto == NULL) {
            ALOGE("setup_data_call: unknown protocol '%s'", in[6]);
            real_env->OnRequestComplete(t, (RIL_Errno)44, NULL, 0);
            return;
        }

        if (last_data_rat > 0)
            snprintf(rat, sizeof(rat), "%d", last_data_rat + 2);
        else
            snprintf(rat, sizeof(rat), "%s", (n > 0 && in[0]) ? in[0] : "0");

        out[0] = rat;
        out[1] = (n > 1) ? in[1] : NULL;
        if (n > 8 && in[8] && !strcmp(in[8], "64") && in[1] &&
            (!strcmp(in[1], "-1") || !strcmp(in[1], "0")))
            out[1] = "2";
        out[2] = (n > 2) ? in[2] : NULL;
        out[3] = (n > 3) ? in[3] : NULL;
        out[4] = (n > 4) ? in[4] : NULL;
        out[5] = (n > 5) ? in[5] : NULL;
        out[6] = (char *)proto;

        if (debug)
            ALOGI("setup_data_call: %zu -> 7 strings; rat='%s' apn='%s' proto='%s'",
                  n, out[0], out[2] ? out[2] : "", proto);

        real_funcs->onRequest(request, out, sizeof(out), t);
        return;
    }
    if ((mask & M_PROFILE) && request == 111) {
        struct attach_legacy out;
        if (!attach_to_legacy(data, datalen, &out)) {
            real_env->OnRequestComplete(t, (RIL_Errno)44, NULL, 0);
            return;
        }
        if (debug) ALOGI("initial attach: %zu -> %zu bytes", datalen, sizeof(out));
        real_funcs->onRequest(request, &out, sizeof(out), t);
        return;
    }
    if ((mask & M_PROFILE) && request == 128) {
        struct profile_legacy profiles[18];
        struct profile_legacy *ptrs[18];
        size_t n = datalen / sizeof(void *);
        if (datalen % sizeof(void *) || n > 18 || (n && !data)) {
            real_env->OnRequestComplete(t, (RIL_Errno)44, NULL, 0);
            return;
        }
        for (size_t i = 0; i < n; ++i) {
            if (!profile_to_legacy(((void **)data)[i], &profiles[i])) {
                real_env->OnRequestComplete(t, (RIL_Errno)44, NULL, 0);
                return;
            }
            ptrs[i] = &profiles[i];
        }
        if (debug) ALOGI("data profiles: %zu converted for vendor v8", n);
        real_funcs->onRequest(request, n ? ptrs : NULL, datalen, t);
        return;
    }
    real_funcs->onRequest(request, data, datalen, t);
}

const RIL_RadioFunctions *RIL_Init(const struct RIL_Env *env, int argc, char **argv)
{
    const RIL_RadioFunctions *(*real_init)(const struct RIL_Env *, int, char **);
    char prop[PROP_VALUE_MAX] = {0};
    void *h;

    if (__system_property_get("persist.vendor.note5.ril.shim.debug", prop) > 0 && prop[0] == '1')
        debug = 1;

    prop[0] = 0;
    if (__system_property_get("persist.vendor.note5.ril.shim.mask", prop) > 0 && prop[0] != 0)
        mask = (unsigned)strtoul(prop, NULL, 0);

    h = dlopen(REAL_RIL, RTLD_NOW | RTLD_GLOBAL);
    if (h == NULL) {
        ALOGE("dlopen(%s) failed: %s", REAL_RIL, dlerror());
        return NULL;
    }

    real_init = (const RIL_RadioFunctions *(*)(const struct RIL_Env *, int, char **))
                dlsym(h, "RIL_Init");
    if (real_init == NULL) {
        ALOGE("no RIL_Init in %s: %s", REAL_RIL, dlerror());
        return NULL;
    }

    pm_instance = (void *const *)dlsym(h, "_ZN12PowerManager9mInstanceE");
    if (pm_instance == NULL)
        ALOGE("no PowerManager::mInstance in %s: startup race check off", REAL_RIL);

    real_env = env;
    shim_env = *env;
    shim_env.OnUnsolicitedResponse = shim_onUnsol;
    shim_env.OnRequestComplete     = shim_onRequestComplete;

    real_funcs = real_init(&shim_env, argc, argv);
    if (real_funcs == NULL) {
        ALOGE("real RIL_Init returned NULL");
        return NULL;
    }

    ALOGI("shim active: real RIL version %d, debug=%d, mask=0x%02x",
          real_funcs->version, debug, mask);

    if (!(mask & (M_WRAP | M_INITGATE)))
        return real_funcs;

    memcpy(funcs_buf, real_funcs, sizeof(funcs_buf));
    *(void **)(funcs_buf + ONREQUEST_OFF) = (void *)shim_onRequest;
    if (mask & M_INITGATE)
        *(void **)(funcs_buf + ONSTATEREQUEST_OFF) = (void *)shim_onStateRequest;
    return (const RIL_RadioFunctions *)funcs_buf;
}
