# Installing noblelte v1.0

## Before you start

- A Galaxy Note 5 model listed in [README.md](README.md), with TWRP installed.
- **A full backup.** This release is signed with its own release keys. Installing it over any
  other build, including earlier test builds of this port, requires a data wipe.
- A GApps package for Android 16 arm64 if you want Google apps (tested with NikGApps basic).
- The phone charged above 50%.

## Install

1. Copy the ROM ZIP (and GApps) to the phone or an SD card.
2. Check the ZIP against `SHA256SUMS.txt`.
3. Boot into TWRP.
4. Back up your current system, boot and data partitions.
5. Wipe > Format Data (type `yes`). This erases internal storage, so copy anything you need off
   the phone first.
6. Install the ROM ZIP.
7. Install the GApps ZIP, if wanted. Install it before the first boot.
8. Reboot to system. The first boot takes several minutes.

## Updating later

Releases signed with the same keys install over each other without a wipe. Reinstall GApps after
each ROM update, because the update replaces the system partition.

## Notes

- The ZIP updates only the system and boot partitions. It keeps TWRP and does not touch the modem,
  EFS or bootloader.
- Data is not encrypted: the 3.10 kernel has no file-based encryption support.
- Widevine runs at L3, so streaming apps play in standard definition.
