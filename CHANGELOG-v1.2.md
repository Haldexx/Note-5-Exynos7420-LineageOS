# noblelte v1.2 — changelog

LineageOS 23.2 (Android 16) for the Galaxy Note 5. Built from v1.1.
Signed with the same release keys as v1.0/v1.1, so it installs over v1.1 **without a data wipe**.

File: `lineage-23.2-<date>-UNOFFICIAL-noblelte-v1.2.zip`

## Fixes

1. **Ringback (dial) tone restored on outgoing calls.** On WiFi calling (and VoLTE), placing a call
   gave no ringback tone — the earpiece stayed silent while the far end rang, until the call
   connected. This was a side effect of the v1.1 WiFi-calling-indicator change: the indicator's
   network-type refresh pushed a call profile whose audio direction was still the default
   "send/receive", so the framework assumed a media path already existed and cancelled the local
   ringback tone it had just started. The refresh now mirrors the real early-media state, so the
   ringback tone plays while ringing and the WiFi-calling indicator still appears while placing the
   call. (IMS client)

2. **SMS over IMS now advertises its capability.** The IMS client left SMS unavailable
   (`cap=false`) even when IMS registered, because Android never sends a separate SMS-enable
   request. The SMS capability bit now defaults on, gated by the implemented SMS handler, a valid
   subscription, and the carrier's `SMS_OVER_IMS` support and transports (LTE/IWLAN). Explicit
   carrier/framework disables are still respected. (IMS client)

3. **IMS SMS protocol correctness.** Decode Android's hex SMSC field (length octet + BCD) instead
   of treating it as a phone number; preserve `sip:`/`sips:`/`tel:` identities; wait for the real
   RP-ACK/RP-ERROR before reporting success (no fabricated status PDU); allocate RP references
   independently of TP-MR for multipart texts; complete a submit exactly once on relay result,
   rejection, write error, cancellation or timeout; and acknowledge real incoming texts and
   delivery reports. (IMS client)

4. **Roaming IMS registration/reconnect recovery.** Registration no longer runs on the network
   callback thread; one bearer revision per proxy sequence; cancelled setup is cleaned up;
   redundant reconnects are skipped; framework readiness is reported as soon as the carrier accepts
   REGISTER (separately from the reg-event subscription); and source/P-CSCF address families are
   matched on dual-stack bearers. (IMS client)

5. **WiFi calling works without cellular service.** The framework required cellular data even for
   registered WiFi voice, so calls failed with "mobile network unavailable" when out of cellular
   coverage on validated WiFi. Registered, voice-capable IWLAN IMS is now allowed independently of
   cellular data (cellular IMS still requires data registration; emergency policy preserved). The
   IMS client also becomes ready on a loaded SIM with home credentials, without waiting for a
   cellular PLMN. (frameworks-opt-telephony 0005, IMS client)

6. **WiFi MMS picks the correct carrier APN.** Picture messages over WiFi used the general
   unrestricted data profile, which shadowed the dedicated carrier IWLAN-only MMS profile and
   failed IKE authentication. For a non-satellite, non-bootstrap MMS request on IWLAN, a valid
   carrier-provisioned IWLAN-only MMS profile is now preferred. This is carrier-independent — no
   hardcoded carrier/APN mapping. (frameworks-opt-telephony 0007)

7. **Location daemon socket permission.** netd was denied read/write/getopt/setopt on the socket
   descriptor passed from the location daemon, causing SCM_RIGHTS truncation and a one-second
   reconnect loop. A narrow `allow netd lhd:tcp_socket { read write getopt setopt };` rule fixes it;
   no global or broad SELinux grant. (device-common 0035/0036/0037)

8. **Smoother keyboard typing.** LatinIME is now speed-compiled (`PRODUCT_DEXPREOPT_SPEED_APPS`),
   which measurably shortened the typing frame-time tail. (device config)

9. **Sane default-APN MTU on the Exynos RIL.** Packed IPv4/IPv6 MTU values from the legacy HIDL 1.4
   RIL on samsungexynos7420 are now unpacked within valid ranges instead of being reported as
   implausible values. Other boards/HALs are unchanged. (frameworks-opt-telephony 0006)

10. **fs-verity guard for the permission store.** Optional integrity setup of the permission
    store's main/reserve copies now checks `VerityUtils.isFsVeritySupported` first.
    (frameworks-base 0010)

11. **Kernel: simultaneous IMS + MMS tunnels to the same ePDG gateway.** The WiFi MMS APN fix (6)
    made the phone select and authenticate the dedicated carrier MMS tunnel, but MMS still failed
    because the 3.10 VTI driver identified a tunnel only by its outer local/remote addresses — so a
    second tunnel to the same gateway collided with the IMS one (`RTNETLINK answers: File exists`).
    The VTI identity now includes the inbound key and underlying link, keeps exact duplicates
    rejected while allowing distinct keyed carrier tunnels, and routes inbound packets to the
    interface whose inner address matches the destination. No carrier literals or cleartext
    fallback. (kernel 0028)

12. **Kernel: IPsec tunnel teardown no longer hangs (MMS retry recovery).** After an MMS tunnel got
    stuck, the IPv6 blackhole route cached when an XFRM policy had no matching SA kept a reference to
    the deleted tunnel device (`unregister_netdevice: waiting for ipsec15 to become free`), blocking
    netd and the global IPsec lock so later MMS setups failed with `SERVICE_TEMPORARILY_UNAVAILABLE`.
    The blackhole route now gets the standard IPv6 `ifdown` callback so it releases its device
    reference. (kernel 0029)

13. **WiFi learns its country from scan when cellular is unavailable.** With no cellular country
    information (e.g. on WiFi-only), the 5 GHz upper channels (149–165) were filtered out, so some
    APs — like one on channel 153 — never appeared in scans. Android's existing scan-based country
    fallback (`config_wifiUpdateCountryCodeFromScanResultGeneric`) is now enabled. Telephony country
    is still preferred, weak/contradictory beacons are still rejected, and no fixed country is forced.
    (Enabled in the Wi-Fi mainline module's compiled default — a device static overlay does not apply
    to `ServiceWifiResources` because it ships inside the `com.android.wifi` APEX.) (wifi 0001)

14. **Other Note 5 variants no longer boot-loop (model detection limited to the display model).**
    v1.1 linked the device vendor-init library so Settings could show the correct model, but that
    reactivated the entire Nougat-era init hook. For non-N920I bootloaders (SM-N920C/K/S/L/G/W8/T/P)
    it replaced the unified build's device codename, build fingerprints and description with stock
    Android 7 values and injected legacy radio/SIM-slot defaults — a credible cause of the reported
    variant boot regression. The hook now overrides only `ro.product.*.model` (so About phone still
    shows e.g. SM-N920C) and preserves the unified product identity and modem configuration;
    unknown/missing bootloaders keep the build defaults. N920I was unaffected either way.
    (device-noblelte 0006)

15. **MMS upload no longer blocks indefinitely on a stalled connection.** The MMS service's HTTP
    upload could hang for the whole transaction when the carrier connection stalled mid-POST. The
    upload socket now takes a bounded write timeout, so a stalled send fails and is retried instead
    of wedging the request. This bounds the client write; a large/video MMS over a very flaky uplink
    can still fail to complete end-to-end. (mms-service 0001)

16. **Kernel: no socket-buffer leak on IPv6 IPsec path-MTU errors.** On the IPv6 ESP output path a
    packet larger than the tunnel PMTU was dropped without freeing its `skb`, leaking kernel memory
    during MMS/IMS tunnel use. The packet is now freed on the PMTU-error path. (kernel 0030)

17. **Kernel: IPv6 address-device routes released on interface teardown.** A per-address IPv6 route
    could keep a reference to a deleted IPsec tunnel device after teardown, contributing to the
    `unregister_netdevice: waiting for ipsecNN to become free` class of hangs. Those routes are now
    removed when the device is unregistered. (kernel 0031)

18. **Hardware process-cache limit restored.** The product's SimpleDeviceConfig RRO replaces
    `configs_base` wholesale, which hid the common vendor hardware cache-limit value and left
    ActivityManager at its 1024-process platform default. The limit is now placed in `configs_device`
    so the product keeps its intended cached-process budget. (device-common 0039)

19. **Staged: dual-SIM (DSDS) support for dual-SIM Note 5 variants — not yet verified on hardware.**
    Infrastructure for SM-N920F/DS, SM-N9208 and SM-N9200 is compiled in but gated off by default.
    A hardware detector (opt-in via `ro.vendor.note5.sim_autodetect`) reads `/proc/simslot_count` and
    enables a second radio daemon and DSDS properties only when the hardware actually reports two
    slots; single-slot hardware stays single-SIM. The PhhIms client gains per-slot
    feature/registration isolation so a second slot uses its own subscription. Kernel 0032 makes the
    SIM-slot driver return errors instead of panicking on a GPIO read failure. This stages dual SIM
    dual standby only; it is **not** verified on a real dual-SIM device and does not promise dual
    VoLTE/Wi-Fi calling or hot-swap. (device-noblelte 0007, kernel 0032, IMS client)

## Install (over v1.1 or an earlier v1.2)
Same release keys as v1.0/v1.1, so no wipe is needed:
1. Boot into TWRP.
2. Install `lineage-23.2-<date>-UNOFFICIAL-noblelte-v1.2.zip`.
3. Reinstall GApps (the ROM replaces the system partition).
4. Reboot.

Coming from any other build (or test keys) still needs a Format Data first.
