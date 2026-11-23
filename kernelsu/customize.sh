[ "$KSU" = true ] || abort "Install this module with KernelSU Manager."
set_perm_recursive "$MODPATH/bin" 0 0 0700 0700
# The App can start a freshly installed module as soon as extraction is complete.
touch "$MODPATH/protocol-1"
ui_print "Start KernelSU TPROXY from Clash Android."
