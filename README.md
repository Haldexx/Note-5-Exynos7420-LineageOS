# LineageOS 23.2 (Android 16) for the Samsung Galaxy Note 5

Unofficial LineageOS 23.2 for the Exynos 7420 Galaxy Note 5, maintained by
[Haldexx](https://github.com/Haldexx).

This repository holds the device configuration, SELinux policy and source patches used to build
the release.

**Download:** [latest release](https://github.com/Haldexx/Note-5-Exynos7420-LineageOS/releases/latest)

## Supported models

The installer accepts these Exynos models (single-SIM):

| Model | Codename |
| --- | --- |
| SM-N920C | noblelte, nobleltejv |
| SM-N920G | nobleltedd |
| SM-N920I | nobleltedv |
| SM-N920T | nobleltetmo |
| SM-N920W8 | nobleltebmc |
| SM-N920S | noblelteskt |
| SM-N920K | nobleltektt |
| SM-N920L | nobleltelgt |

Tested on the SM-N920I on T-Mobile US. The other models share the kernel, device tree and modem
stack, but are not yet tested. VoLTE and Wi-Fi calling depend on carrier support. The Sprint
(SM-N920P) and Verizon (SM-N920V) models use a Qualcomm modem and are not supported by this release.

## Status

Highlights: SELinux enforcing, VoLTE and Wi-Fi calling, RCS, S Pen with hover pointer, native
1440x2560, OpenGL ES 3.2 and Vulkan, tuned touch and app-launch performance.

## Installation

See [INSTALL.md](INSTALL.md).

## Building

See [BUILDING.md](BUILDING.md).

## Credits

- [LineageOS](https://lineageos.org) and the Android Open Source Project
- [samsungexynos7420](https://github.com/samsungexynos7420) for the Exynos 7420 device, kernel
  and vendor trees this port builds on
- [phhusson](https://github.com/phhusson) for the IMS client
- Haldexx: Android 16 port, patches and maintenance

## Disclaimer

This is an unofficial build. It is not affiliated with LineageOS, Samsung or Google, and it is not
Google Play certified. Flashing custom software can void your warranty and may brick your device.
You do this at your own risk.
