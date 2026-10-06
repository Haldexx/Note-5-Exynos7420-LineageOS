# SPDX-License-Identifier: Apache-2.0
"""Include the actual static tools used by the N920P TWRP post-install step."""
import common

PRESERVE_RECOVERY = True

def FullOTA_InstallBegin(info):
    for tool in ("e2fsck_static", "resize2fs_static"):
        data = info.input_zip.read("SYSTEM/bin/" + tool)
        if len(data) < 64 or not data.startswith(b"\x7fELF"):
            raise ValueError("Missing or invalid recovery executable: " + tool)
        common.ZipWriteStr(info.output_zip, "install/bin/" + tool, data, perms=0o755)
    helper = info.input_zip.read("INSTALL/bin/note5-resize-system.sh")
    if not helper.startswith(b"#!/sbin/sh\n"):
        raise ValueError("Invalid TWRP system-resize helper")

def FullOTA_PostValidate(info):
    info.script.AppendExtra(
        'assert(run_program("/sbin/sh", "/tmp/install/bin/note5-resize-system.sh") == 0);')
