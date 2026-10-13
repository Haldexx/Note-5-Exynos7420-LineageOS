# noblelte v1.1 — changelog

LineageOS 23.2 (Android 16) for the Galaxy Note 5. Built from v1.0 with the fixes below.
Signed with the same release keys as v1.0, so it installs over v1.0 **without a data wipe**.

File: `lineage-23.2-<date>-UNOFFICIAL-noblelte-v1.1.zip`

## Fixes

1. **Random app crashes / "Play Store install fails first try, works on retry."**
   zram was using the `lz4` compressor; this 3.10 kernel's lz4 is the old pre-4.11 import that
   could return corrupt bytes on swap-in under memory pressure, which showed up as unrelated app
   crashes (ART GC aborts, SIGSEGV) and failed/retried installs. Switched zram to `lzo`, the
   kernel's proven default. (device-common 0029)

2. **About phone always showed "SM-N920I" on every variant.**
   Android 16 dropped the init mechanism that applied the per-variant model, so every variant kept
   the build's default. Re-linked the per-variant init lib; Model, device name and fingerprint are
   now correct per variant (N920C/G/I/T/W8/S/K/L). (system-core 0002)

3. **On-screen navigation bar now works.** Enabling it (Settings > System > Buttons > navigation
   bar) previously disabled the hardware keys but showed no working bar. It now shows SystemUI's
   real 3-button navigation bar with working Back/Home/Recents, and it appears/disappears the
   moment you toggle it — no reboot. (frameworks-base 0004/0005/0006/0007, launcher3 0001)

4. **New option: keep hardware buttons with the navigation bar.** Under Settings > System >
   Buttons > navigation bar there's now a "Keep hardware buttons" switch, so you can show the
   on-screen navigation bar and still use the capacitive hardware keys. (lineageparts 0001)

5. **Samsung Live HDR control in Aperture.** Adds a Live HDR on/off button for supported
   rear-camera photo and video modes. This uses Samsung's existing shadow/highlight processing
   and saves normal SDR output; it does not enable 10-bit HDR. The front camera hides the option.
   App and encoder tests passed. (aperture 0001)

6. **Rear-camera 60 FPS recording at 1080p and lower.** Includes the guarded Samsung camera
   backport and Aperture changes. Encoder tests measured 60.01 FPS at 1080p and 720p; saved app
   clips measured 60.01 FPS at the 1080p setting and 59.84 FPS at 480p. Aperture closes the
   hardware camera between mode changes to avoid the legacy buffer race. 4K is limited to supported
   rates, including 30 FPS. Front 1080p30 passed. HDR is disabled above 30 FPS. These are N920I
   tests; other variants and third-party camera apps need testing.
   (device-common 0030, vendor-samsung 0002, aperture 0002/0003)

7. **Camera no longer crashes when changing settings.** If the camera HAL restarted (e.g. around
   a 60 fps mode switch), the camera service aborted instead of recovering, so changing camera
   settings could crash the camera. The service now tolerates a HAL restart and keeps running.
   (frameworks-av 0002)

8. **SIM hotswap.** Removing or inserting the SIM while powered on is now handled live, with no
   "restart required" prompt. (If the modem can't re-attach after a swap, toggle airplane mode or
   reboot to regain data.) (device-common 0031)

9. **Prevent black preview from Live HDR at 60 FPS.** Live HDR ON is incompatible with
   the high-speed viewfinder, so HDR is disabled above 30 FPS and the saved HDR setting is
   restored when returning to normal-rate video or photo mode. Real
   60 FPS preview and recording remain available with HDR off. The earlier claim
   that this hardware cannot preview 60 FPS was disproved by a controlled comparison.
   The speculative cold-start and preview-size patches were withdrawn.
   (aperture 0006)
10. **Camera session-error follow-up — validation pending.** The physical-close callback
    barrier and request-option ordering changes address a reproduced fatal reopen timeout.
    One 60 FPS recording returned to normal video/HDR successfully, but repeated setup
    and black-preview failures remain under investigation. The Exynos shutdown candidate
    compiled and still requires phone tests; do not call this release-ready.
    (aperture 0007–0009)

11. **Gallery photo editing restored.** The default gallery (Glimpse) had no editor and its edit
    button had nothing to hand off to without Google Photos. Added Gallery2 as the built-in editor
    backend, so the gallery's edit button now opens crop, filters and adjustments. Verified on
    N920I. (device-common 0032)

12. **WiFi-calling indicator shows while placing a call.** The VoWiFi indicator used to appear only
    after a call connected, so you couldn't tell if the call you were placing was over WiFi. It now
    appears while the call is dialing/ringing, and clears if the call ends up on VoLTE (e.g. a
    WiFi→VoLTE fallback). Incoming calls already showed it at ring. (shared IMS client)

13. **Auto cellular recovery after a long outage.** After the phone
    has had no service for a long, continuous period with the radio on, it now cycles the radio to
    force a fresh network attach, so it recovers without a manual reboot. Conservative: default 45
    minutes, tunable via the `persist.sys.oos_radio_recovery_min` property (`0` disables). Can't be
    reproduced on demand; needs validation against a real prolonged outage. (frameworks-opt-telephony
    0004)

14. **Incoming IMS call handling.** The shared IMS (SIP) client now deduplicates incoming
    transactions, keeps the answer attached through UPDATE/PRACK, replies on the receiving TCP/UDP
    path, and reports established/ended state to the matching session, with explicit outcomes for
    duplicate answers, cancellation, missing ACK and unsupported operations. Targets the Turkcell
    "answer doesn't register / phantom second call" report; call waiting, hold and conference
    remain unimplemented. (shared IMS client)

15. **Camera stabilization.** Rear-camera OIS is enabled (Samsung still mode for photos, video
    mode for recording), plus a translation-only software stabilizer at 720p/1080p through 60 FPS
    with a 16% field-of-view crop (not Samsung's VDIS engine). Also fixes a sync-fence leak and an
    ISP reservation failure, and stops a diagnostic SIGPIPE from killing the camera provider.
    Stabilized clips measured ~58.7–59.7 FPS at 1080p and 59.8 FPS at 720p; small residual jitter
    remains. N920I tested; other variants need validation.
    (device-common / vendor-samsung / kernel)

16. **System stability fixes.** Added a missing location-service SELinux permission, stopped
    fs-verity attempts on unsupported settings files, kept fixed-panel refresh ranges valid (1440p
    unchanged), made the Wi-Fi HAL report link-layer stats honestly (normal packet-counter
    fallback), and fixed an IMS periodic-registration startup crash (checks transport readiness
    and handles alarms before the IMS service exists). (frameworks-base, broadcom-wifi, IMS)

17. **Music widget play/pause fixed.** The Music (Twelve) widget now uses Media3's official
    playback command builder, and legacy cached widget actions are translated to it, so Media3
    handles session registration, foreground notifications and playback resumption — fixing the
    foreground-service timeout caused by the old widget action. (twelve)

## Install (over v1.0)
Same release keys as v1.0, so no wipe is needed:
1. Boot into TWRP.
2. Install `lineage-23.2-<date>-UNOFFICIAL-noblelte-v1.1.zip`.
3. Reinstall GApps (the ROM replaces the system partition).
4. Reboot.

Coming from any other build (or test keys) still needs a Format Data first.

