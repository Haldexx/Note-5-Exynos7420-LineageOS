#!/vendor/bin/sh

DIR=/data/vendor/modem
mkdir -p $DIR
chmod 0770 $DIR
exec >> $DIR/bringup.log 2>&1
echo "=== bring-up $(date) uptime $(cut -d' ' -f1 /proc/uptime) ==="

state() {
    setprop vendor.note5.modem.state "$1"
    echo "  state: $1"
}

if [ "$(getprop persist.vendor.note5.modem.disable)" = 1 ]; then
    state disabled
    exit 0
fi
if [ "$(getprop persist.vendor.note5.modem.inflight)" = 1 ]; then
    echo "  the previous bring-up never completed; skipping this boot"
    state skipped-after-incomplete-attempt
    exit 0
fi

ESOC=/sys/devices/qcom,mdm1.54/esoc0/subsys0/state
BYNAME=/dev/block/platform/15570000.ufs/by-name
if [ ! -e $ESOC ] || [ ! -e /dev/subsys_esoc0 ]; then
    echo "  no MDM9x35 ESOC device"
    state no-modem
    exit 0
fi

mkdir -p /dev/block/modem
for e in m9kefs1 m9kefs2 m9kefs3; do
    ln -sf $BYNAME/$e /dev/block/modem/$e
done
ln -sf $BYNAME/ /dev/block/modem/dump_path

mountpoint -q /firmware || mount -t vfat -o ro $BYNAME/RADIO /firmware
if [ ! -e /firmware/image/qdsp6sw.mbn ]; then
    echo "  modem firmware not found under /firmware/image"
    state no-firmware
    exit 1
fi
echo "  firmware images: $(ls /firmware/image | wc -l)"

mkdir -p $DIR/cpdump
chmod 0770 $DIR/cpdump
mountpoint -q /cpdump || mount --bind $DIR/cpdump /cpdump
mkdir -p /data/misc/mdmhelperdata
chmod 0770 /data/misc/mdmhelperdata
for d in qmux_radio qmux_audio qmux_bluetooth qmux_gps qmux_nfc; do
    mkdir -p /dev/socket/$d
    chmod 2770 /dev/socket/$d
    chown radio:radio /dev/socket/$d
done

st=$(cat $ESOC)
if [ "$st" != ONLINE ]; then
    setprop persist.vendor.note5.modem.inflight 1
    /vendor/bin/mdm_helper > $DIR/mdm_helper.log 2>&1 &
    helper=$!
    sleep 3
    /vendor/bin/mdm_pwron /dev/subsys_esoc0 0 > $DIR/mdm_pwron.log 2>&1 &
    pwron=$!
    i=0
    while [ $i -lt 12 ]; do
        sleep 5
        st=$(cat $ESOC)
        [ "$st" = ONLINE ] && break
        i=$((i + 1))
    done
    if [ "$st" != ONLINE ]; then
        echo "  esoc = $st after 60 s; releasing the modem"
        kill $pwron $helper
        wait
        setprop persist.vendor.note5.modem.inflight 0
        state failed-to-boot
        exit 1
    fi
    setprop persist.vendor.note5.modem.inflight 0
fi
echo "  esoc = $st, mhi = $(ls /dev/mhi_pipe_14 2>/dev/null)"

/vendor/bin/qmuxd > $DIR/qmuxd.log 2>&1 &
sleep 6
echo "  qmuxd = $(pidof qmuxd)"
state online
echo "=== phase 1 done, uptime $(cut -d' ' -f1 /proc/uptime) ==="

if [ "$(getprop persist.vendor.note5.ril.disable)" = 1 ]; then
    echo "  RIL disabled by persist.vendor.note5.ril.disable"
else
    start ril-daemon
    state ril-started
    echo "=== RIL started, uptime $(cut -d' ' -f1 /proc/uptime) ==="
    (
        n=0
        while :; do
            i=0
            while [ $i -lt 15 ]; do
                sleep 2
                b=$(getprop gsm.version.baseband)
                if [ -n "$b" ]; then
                    echo "  RIL up: baseband $b, uptime $(cut -d' ' -f1 /proc/uptime)"
                    exit 0
                fi
                i=$((i + 1))
            done
            if [ $n -ge 3 ]; then
                echo "  still no baseband version after $n restarts; leaving rild as it is"
                state ril-no-baseband
                exit 0
            fi
            n=$((n + 1))
            echo "  no baseband version 30 s after starting the RIL; restart $n of 3"
            stop ril-daemon
            sleep 3
            start ril-daemon
            state ril-restarted
            echo "=== RIL restarted, uptime $(cut -d' ' -f1 /proc/uptime) ==="
        done
    ) &
fi

wait
