#!/sbin/sh
set -u
tools=${1:-/tmp/install/bin}
system=${2:-/dev/block/platform/15570000.ufs/by-name/SYSTEM}

check_filesystem() {
    "$tools/e2fsck_static" -fy "$system"
    result=$?
    case "$result" in
        0|1) return 0 ;;
        *) echo "Note 5 system filesystem check failed ($result)." >&2; return "$result" ;;
    esac
}

check_filesystem || exit $?
"$tools/resize2fs_static" "$system" || exit $?
check_filesystem || exit $?
exit 0
