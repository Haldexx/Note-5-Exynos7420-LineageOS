# noblelte v1.0 — feature status

LineageOS 23.2 (Android 16), tested on the SM-N920I on T-Mobile US.

## What is working

- Booting to home screen, SELinux enforcing
- Touchscreen, capacitive keys, home/power/volume keys, double-tap to wake
- Display at native 1440x2560, LiveDisplay, auto-brightness
- Graphics: OpenGL ES 3.2 and Vulkan 1.0 on the Mali-T760
- Wi-Fi (2.4 and 5 GHz) and Wi-Fi hotspot
- Bluetooth
- Audio: speaker, earpiece, call audio
- Calls, SMS and mobile data
- VoLTE and Wi-Fi calling (T-Mobile)
- RCS chat in Google Messages
- GPS
- NFC
- Fingerprint
- Camera (Aperture): photos and video
- Sensors: accelerometer, gyroscope, compass, barometer, proximity, light, step counter,
  significant motion, tilt, pick-up, rotation vectors
- S Pen: pressure, tilt, side button and hover pointer
- Vibration, charging and battery reporting
- Widevine L3: Netflix and other streaming apps play in standard definition
- ADB, Doze, system tracing (Perfetto)
- Touch and app-launch performance boost

## What is not working

- Encryption: FDE was removed in Android 13; FBE needs kernel support the 3.10 kernel lacks
- Widevine L1 / HD streaming: the Samsung trusted application rejects protected playback, so
  Widevine runs at L3
- Heart-rate sensor (not exposed to apps)
- 60 fps video and video stabilization (OIS/VDIS)
- Google Camera from the Play Store (use Aperture)
- Wireless display (Miracast)
- Samsung S Pen apps (Air Command, Screen-off memo)
- MIFARE Classic NFC tags
- Google Play certification (unofficial build)

## Known issues

- Deep sleep is not yet verified on battery
- The battery charge counter is not reported (usage estimates use the percentage)

## Untested

- Headphones and headset microphone
- MTP / USB file transfer, USB tethering, USB OTG
- Hotspot with a connected client
- Wi-Fi Direct and Passpoint
- NFC tag reading and contactless payment
- Call recording
- Notification LED and flip cover
- Wireless charging
- SM-N920C, N920G, N920T, N920W8, N920S, N920K and N920L
