#!/system/bin/sh
BB=$(which busybox 2>/dev/null || echo "/data/adb/ksu/bin/busybox")
[ -x "$BB" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1)

echo "[Pivoted Root] Preparing pivot..."
cd /data/local/tmp/lineage_stage/rootfs
$BB mount --make-rprivate /
if ! $BB pivot_root . postinstall; then
    echo "[Pivoted Root] Fallback: bind mounting rootfs onto itself..."
    $BB mount --bind . .
    if ! $BB pivot_root . postinstall; then
        echo "[Pivoted Root] FATAL: pivot_root failed completely! Aborting."
        exit 1
    fi
fi
$BB mount -t proc proc /proc

export PATH=/system/bin:/system/xbin

# OneLemon used to ship OneLineage's zygote_exec, which hardcodes LineageOS's
# BOOTCLASSPATH/SYSTEMSERVERCLASSPATH (including org.lineageos.platform.jar).
# A One UI guest needs its own Samsung class path, so derive it from the guest's
# classpaths protobufs exactly as init does. zygote_exec now reads these
# variables from the environment.
DERIVE=/apex/com.android.sdkext/bin/derive_classpath
CLASSPATH_FILE=/data/system/environ/classpath
mkdir -p /data/system/environ
if [ -x "$DERIVE" ] && "$DERIVE" "$CLASSPATH_FILE" > /data/derive_classpath.log 2>&1; then
    while read -r kw name value; do
        [ "$kw" = "export" ] && [ -n "$name" ] && export "$name=$value"
    done < "$CLASSPATH_FILE"
    echo "[Pivoted Root] Guest class paths loaded from $CLASSPATH_FILE"
else
    echo "[Pivoted Root] WARNING: derive_classpath failed, zygote keeps the host's class paths"
    sed 's/^/[Pivoted Root]   /' /data/derive_classpath.log 2>/dev/null | head -5
fi

echo "[Pivoted Root] Setting up /data_mirror and /data/data mounts for Zygote isolation..."
$BB mount --bind /data/user/0 /data/data 2>/dev/null || true
$BB mount -t tmpfs tmpfs /data_mirror 2>/dev/null || true
mkdir -p /data_mirror/data_de/null/0 /data_mirror/data_ce/null/0 /data_mirror/cur_profiles /data_mirror/ref_profiles
chmod -R 777 /data_mirror
$BB mount --bind /data/user_de/0 /data_mirror/data_de/null/0 2>/dev/null || true
$BB mount --bind /data/user/0 /data_mirror/data_ce/null/0 2>/dev/null || true
mkdir -p /data/misc/profiles/cur /data/misc/profiles/ref
$BB mount --bind /data/misc/profiles/cur /data_mirror/cur_profiles 2>/dev/null || true
$BB mount --bind /data/misc/profiles/ref /data_mirror/ref_profiles 2>/dev/null || true

echo "[Pivoted Root] Setting up /data/media and emulated storage..."
mkdir -p /data/media/0/Android /data/media/0/Pictures/Screenshots /data/media/0/Download /data/media/0/DCIM /data/media/0/Documents
chown -R 1023:1023 /data/media 2>/dev/null || true
chmod -R 777 /data/media 2>/dev/null || true
$BB mount -t tmpfs tmpfs /storage 2>/dev/null || true
mkdir -p /storage/emulated/0
$BB mount --bind /data/media/0 /storage/emulated/0 2>/dev/null || true
mkdir -p /mnt/user/0
$BB mount -t tmpfs tmpfs /mnt/user/0 2>/dev/null || true
mkdir -p /mnt/user/0/emulated
$BB mount --bind /data/media/0 /mnt/user/0/emulated 2>/dev/null || true

echo "[Pivoted Root] Rootfs successfully activated as /"
echo "[Pivoted Root] Testing /dev/null resolution:"
exec 8< /dev/null
echo -n "FD 8: "
$BB readlink /proc/$$/fd/8
exec 8<&-

echo "[Pivoted Root] Testing su availability:"
su -c id || echo "su check non-fatal"

# The vendor composer still treats the killed host SurfaceFlinger as its
# client: every frame from the guest comes back NO_RESOURCES and the panel
# keeps One UI's last frame. Restart it so the guest gets a fresh client.
COMPOSER_SVC=""
for c in vendor.qti.hardware.display.composer vendor.hwcomposer-3 vendor.hwcomposer; do
    [ -n "$(getprop init.svc.$c)" ] && { COMPOSER_SVC=$c; break; }
done
[ -n "$COMPOSER_SVC" ] || COMPOSER_SVC=$(getprop | sed -n 's/^\[init\.svc\.\([^]]*\)\]: \[running\]$/\1/p' | grep -m1 -E 'hwcomposer|display\.composer|graphics\.composer')
echo "[Pivoted Root] Restarting the vendor display composer (${COMPOSER_SVC:-none found})..."
[ -z "$COMPOSER_SVC" ] || setprop ctl.restart "$COMPOSER_SVC"
sleep 0.5
n=0
while [ $n -lt 30 ] && ! service check android.hardware.graphics.composer3.IComposer/default 2>/dev/null | grep -q ': found'; do
    n=$((n + 1)); sleep 0.1
done
echo "[Pivoted Root] Composer registered after $(((5 + n) * 100)) ms"
# The composer's rc has "onrestart restart surfaceflinger", which brings the
# HOST SurfaceFlinger back. Stop it before the guest's SurfaceFlinger owns the
# display and the SurfaceFlinger service name.
setprop ctl.stop surfaceflinger
n=0
while [ $n -lt 20 ] && pidof surfaceflinger >/dev/null; do
    kill -9 $(pidof surfaceflinger) 2>/dev/null
    n=$((n + 1)); sleep 0.1
done

echo "[Pivoted Root] Starting LineageOS SurfaceFlinger..."
# sf.log used to grow without bound: the guest's RenderEngine dumps LLVM IR for
# every shader it compiles ("Global Value @fullLiteral...") and got to 300+ MB in
# ~3 minutes, filling /data. SurfaceFlinger's real diagnostics go through logcat,
# so stderr can be dropped.
/system/bin/surfaceflinger > /dev/null 2>&1 &
SF_PID=$!
echo "[Pivoted Root] SurfaceFlinger PID: $SF_PID"
sleep 2

echo "[Pivoted Root] Starting LineageOS BootAnimation..."
setprop service.bootanim.exit 0
/system/bin/bootanimation > /data/anim.log 2>&1 &
ANIM_PID=$!
echo "[Pivoted Root] BootAnimation PID: $ANIM_PID"
sleep 1

# A stock One UI guest has a DZJ3 vold but the image carries the host's DZJ7
# framework.jar. The DZJ7 IVold interface has setCeStorageProtection(), which
# the older guest vold does not implement: LockSettingsService dies with
# "ServiceSpecificException (code 0)" during first-boot synthetic-password
# setup. Keep using the host's already-running DZJ7 vold instead of starting
# the guest's older one. A GSI guest still starts its own vold.
if grep -q '^ro.build.version.sem=' /system/build.prop 2>/dev/null; then
    echo "[Pivoted Root] Stock Samsung guest: using the host vold (DZJ7)"
else
    echo "[Pivoted Root] Starting LineageOS Vold..."
    /system/bin/vold --blkid_context=u:r:blkid:s0 --blkid_untrusted_context=u:r:blkid_untrusted:s0 --fsck_context=u:r:fsck:s0 --fsck_untrusted_context=u:r:fsck_untrusted:s0 > /data/vold.log 2>&1 &
    VOLD_PID=$!
    echo "[Pivoted Root] Vold PID: $VOLD_PID"
fi
sleep 1

# Wifi. switch_to_lineage.sh stops the host wificond; the guest's own registers
# IWificond before WifiService initialises, and drives Samsung's wifi HAL (which
# is still registered by host init) with matching AIDL.
echo "[Pivoted Root] Starting LineageOS wificond..."
/system/bin/wificond > /data/wificond.log 2>&1 &
WIFICOND_PID=$!
echo "[Pivoted Root] wificond PID: $WIFICOND_PID"
sleep 1

# Overlay idmaps. idmap2 refuses the GSI's product overlays because framework-res
# does not declare the targeted resources as overlayable, so OMS ends up with no
# idmap at all and silently ignores every overlay: gesture navigation never
# applies (navbar stays 3-button) and the SystemUI accent overlays do nothing.
# Build the nav-bar idmap ourselves, before zygote can cache the empty overlay.
NAV_IDMAP="/data/resource-cache/system@product@overlay@NavigationBarModeGestural@NavigationBarModeGesturalOverlay.apk@idmap"
NAV_OV="/system/product/overlay/NavigationBarModeGestural/NavigationBarModeGesturalOverlay.apk"
mkdir -p /data/resource-cache
if [ ! -s "$NAV_IDMAP" ] && [ -f "$NAV_OV" ]; then
    echo "[Pivoted Root] Generating gesture-nav overlay idmap..."
    /system/bin/idmap2 create --target-apk-path /system/framework/framework-res.apk \
        --overlay-apk-path "$NAV_OV" --idmap-path "$NAV_IDMAP" --ignore-overlayable \
        > /data/idmap.log 2>&1 \
        && chmod 644 "$NAV_IDMAP" && chown system:system "$NAV_IDMAP" \
        && echo "[Pivoted Root] idmap OK ($(wc -c < "$NAV_IDMAP") bytes)" \
        || echo "[Pivoted Root] idmap FAILED: $(head -n1 /data/idmap.log 2>/dev/null)"
fi

# Newly installed apps have no data directories (the guest has no installd), and
# zygote kills their first launch with "Failed to mount /data_mirror/...". This
# sweep creates them while Lineage runs, so Play Store/Aurora installs become
# launchable within ~15 s.
if [ -x /system/bin/setsid ]; then
    setsid /system/bin/sh /data/local/tmp/app_storage_loop.sh < /dev/null > /dev/null 2>&1 &
else
    /system/bin/sh /data/local/tmp/app_storage_loop.sh < /dev/null > /dev/null 2>&1 &
fi
echo "[Pivoted Root] App storage sweeper started..."

echo "[Pivoted Root] Cleaning stale sockets..."
rm -f /dev/socket/zygote /dev/socket/usap_pool_primary 2>/dev/null || true

echo "[Pivoted Root] Launching LineageOS Zygote & SystemServer via zygote_exec..."
exec /data/zygote_exec
