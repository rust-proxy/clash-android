#!/system/bin/sh
# Read a single snapshot for the module WebUI. Each record has a tab-separated type.
set -o pipefail
[ "$(id -u)" = 0 ] || { echo "KernelSU root required" >&2; exit 1; }

routes() {
    family=$1
    if rules=$(ip "-$family" rule show); then
        printf '%s\n' "$rules" | awk -v f="$family" '$1 == "9055:" {print "route" f "\t" $0}'
    else
        printf 'route%s\t读取策略规则失败\n' "$family"
    fi
    if entries=$(ip "-$family" route show table 17893); then
        printf '%s\n' "$entries" | awk -v f="$family" 'NF {print "route" f "\t" $0}'
    else
        printf 'route%s\t读取路由表失败\n' "$family"
    fi
}

sockets() {
    # /proc/PID/net/tcp contains the whole network namespace. Match socket
    # inodes against this process's open descriptors before assigning ownership.
    descriptors=$(ls -l "/proc/$1/fd") || return 1
    for table in tcp tcp6; do
        file="/proc/$1/net/$table"
        [ "$table" = tcp6 ] && [ ! -e "$file" ] && continue
        printf '%s\n' "$descriptors" | awk -v pid="$1" '
            FILENAME == "-" {
                if ($NF ~ /^socket:\[[0-9]+\]$/) {
                    inode = $NF
                    gsub(/[^0-9]/, "", inode)
                    owned[inode] = 1
                }
                next
            }
            $3 ~ /:0355$/ && ($10 in owned) {
                print "socket\t" pid "\t" $8 "\t" $10 "\t" $4
            }
        ' - "$file" || return 1
    done
}

routes 4
routes 6
for pid in $(pidof clash-tproxy); do
    executable=$(readlink "/proc/$pid/exe") || { [ ! -d "/proc/$pid" ] && continue; exit 1; }
    case ${executable%" (deleted)"} in
        /data/adb/modules/clash_android_tproxy/bin/*/clash-tproxy|/data/adb/modules_update/clash_android_tproxy/bin/*/clash-tproxy) ;;
        *) continue ;;
    esac
    uid=$(awk '/^Uid:/ {print $3}' "/proc/$pid/status")
    [ -n "$uid" ] || continue
    printf 'process\t%s\t%s\n' "$pid" "$uid"
    sockets "$pid" || printf 'socketError\t无法读取 PID %s 的套接字\n' "$pid"
    logcat -b main -d --pid="$pid" -v time 'clash-rs:V' '*:S' |
        awk -v pid="$pid" '/DNS query timeout/ {print "timeout\t" pid "\t" $0}' |
        tail -n 20 || printf 'logError\t无法读取 PID %s 的日志\n' "$pid"
done
printf 'done\n'
