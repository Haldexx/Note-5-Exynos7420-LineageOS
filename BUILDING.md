# Building

The release is built from LineageOS 23.2 at the exact revisions in `manifests/noblelte-v1.0.xml`,
plus the patches and device configuration in this repository. Every patch series is validated
against those revisions. A Linux host with 32 GB RAM (or swap) and about 400 GB free space on a
case-sensitive filesystem is recommended.

## 1. Get the source

```bash
mkdir lineage23 && cd lineage23
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs
cp /path/to/this/repo/manifests/noblelte-v1.0.xml .repo/manifests/
repo init -m noblelte-v1.0.xml
repo sync -c -j8
```

The snapshot manifest already includes the Exynos 7420 device, kernel and vendor trees, and the
stock vendor files from
[proprietary_vendor_samsung_noblelte](https://github.com/Haldexx/proprietary_vendor_samsung_noblelte)
at `device/samsung/nobleltespr/proprietary`.

## 2. Apply the patches

```bash
bash /path/to/this/repo/scripts/apply-patches.sh "$PWD"
```

The script checks that each patched project is at its pinned revision, applies each patch series
once (it is safe to run again), installs the kernel configuration and copies
`device/samsung/nobleltedv` and `device/samsung/nobleltespr` into the tree.
`nobleltespr` holds the modules shared with the SM-N920I build (IMS, touch, LiveDisplay, modem
shims, SELinux policy).

The vendor files synced in step 1 stay in place: the script copies the device trees around them.
`proprietary-files.txt` lists each vendor file with its SHA-256. The LifeVibes voice tuning
(`vendor/etc/nxp`) is required; without it, calls crash the audio service.

## 3. Build

```bash
source build/envsetup.sh
lunch lineage_nobleltedv-bp4a-userdebug
m bacon
```

The installable ZIP is `out/target/product/nobleltedv/lineage-23.2-<date>-UNOFFICIAL-nobleltedv.zip`.
One build installs on every model listed in [README.md](README.md).

## Signing

To sign with your own keys, follow the LineageOS
[signing guide](https://wiki.lineageos.org/signing_builds): build `target-files-package` and
`otatools`, sign the target files with `sign_target_files_apks` (with keys for every APEX), then
package with `ota_from_target_files -k <releasekey>`. Builds signed with different keys need a
data wipe when switching between them.
