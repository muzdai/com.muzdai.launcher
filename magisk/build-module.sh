#!/bin/sh
# Builds the Magisk module that puts vPro TV Launcher into /system/priv-app.
#   magisk/build-module.sh <signed vPro TV Launcher apk> [out.zip]
# Install on the TV:  adb push out.zip /data/local/tmp/ && adb shell su -c 'magisk --install-module /data/local/tmp/out.zip'
# then reboot. The APK must be signed with the same key as the installed launcher; the installed copy stays as an
# update of the system app, so later releases install with a plain `adb install -r`.
set -eu
apk=$1
out=${2:-muzdai_launcher.zip}
here=$(cd "$(dirname "$0")" && pwd)
# Every privileged permission the APK asks for must be in the allowlist, or the TV will not boot.
perms=$(aapt2 dump permissions "$apk" 2>/dev/null | sed -n "s/.*uses-permission: name='\([^']*\)'.*/\1/p")
for p in android.permission.DELETE_PACKAGES android.permission.WRITE_SECURE_SETTINGS; do
    echo "$perms" | grep -qx "$p" || echo "note: $p is allowlisted but not requested" >&2
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -r "$here/module.prop" "$here/system" "$work/"
mkdir -p "$work/META-INF/com/google/android" "$work/system/priv-app/MuzdaiLauncher"
cp "$apk" "$work/system/priv-app/MuzdaiLauncher/MuzdaiLauncher.apk"
printf '#!/sbin/sh\n#MAGISK\n' > "$work/META-INF/com/google/android/updater-script"
cat > "$work/META-INF/com/google/android/update-binary" <<'SH'
#!/sbin/sh
umask 022
OUTFD=$2
ZIPFILE=$3
. /data/adb/magisk/util_functions.sh
install_module
exit 0
SH
rm -f "$out"
(cd "$work" && zip -qr9 - .) > "$out"
echo "$out"
