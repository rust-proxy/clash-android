[ "$KSU" = true ] || abort "Install this module with KernelSU Manager."
set_perm_recursive "$MODPATH/bin" 0 0 0700 0700
# Use Android's ABI preference order for both universal and single-ABI ZIPs.
for abi in $(getprop ro.product.cpu.abilist | tr ',' ' '); do
    case "$abi" in arm64-v8a|armeabi-v7a|x86_64) ;; *) continue ;; esac
    if [ -x "$MODPATH/bin/$abi/clash-tproxy" ]; then
        ln -sf "bin/$abi/clash-tproxy" "$MODPATH/diagnostics" || abort "Cannot link diagnostics."
        break
    fi
done
[ -x "$MODPATH/diagnostics" ] || abort "No compatible diagnostics binary."
# The App can start a freshly installed module as soon as extraction is complete.
touch "$MODPATH/protocol-1"
ui_print "Start KernelSU TPROXY from Clash Android."
