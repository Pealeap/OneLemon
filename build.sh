#!/usr/bin/env bash
# By Dani.
# Universal Android build script (aapt2 + javac + d8 + zipalign + apksigner)
# Run it from the project root (the folder with AndroidManifest.xml, res/, src/).
#
# Optional overrides via env vars:
#   ANDROID_HOME / ANDROID_SDK_ROOT  -> SDK location
#   BUILD_TOOLS_VERSION              -> e.g. 34.0.0 (default: latest installed)
#   PLATFORM_API                     -> e.g. 34 (default: latest installed)
#   OUT_APK                          -> output path (default: ./<folder-name>.apk)
#   KEYSTORE / KS_PASS / KS_ALIAS    -> custom signing key
set -euo pipefail

PROJECT_DIR="$PWD"
BUILD_DIR="$PROJECT_DIR/build"
APP_NAME="$(basename "$PROJECT_DIR")"
OUT_APK="${OUT_APK:-$PROJECT_DIR/$APP_NAME.apk}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -f AndroidManifest.xml ] || die "AndroidManifest.xml not found in $PROJECT_DIR. Run this from the project root."

# ---------- Java ----------
if [ -z "${JAVA_HOME:-}" ]; then
    for c in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 \
             /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64; do
        [ -d "$c" ] && { export JAVA_HOME="$c"; break; }
    done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || die "javac not found. Install a JDK (17 recommended) or set JAVA_HOME."
command -v zip   >/dev/null || die "zip not found."

# ---------- Android SDK ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
        [ -d "$c" ] && { SDK="$c"; break; }
    done
fi
[ -d "${SDK:-}" ] || die "Android SDK not found. Set ANDROID_HOME."

BT_VER="${BUILD_TOOLS_VERSION:-$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -n1)}"
SDK_TOOLS="$SDK/build-tools/$BT_VER"
[ -x "$SDK_TOOLS/aapt2" ] || die "build-tools not found at $SDK_TOOLS"

API="${PLATFORM_API:-$(ls "$SDK/platforms" 2>/dev/null | sed -n 's/^android-\([0-9][0-9]*\)$/\1/p' | sort -n | tail -n1)}"
PLATFORM_JAR="$SDK/platforms/android-$API/android.jar"
[ -f "$PLATFORM_JAR" ] || die "android.jar not found at $PLATFORM_JAR"

# ---------- Package name from manifest ----------
PKG="$(sed -n 's/.*package="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -n1)"
[ -n "$PKG" ] || die "Could not read package name from AndroidManifest.xml"
PKG_PATH="${PKG//./\/}"

echo "Project : $PROJECT_DIR"
echo "SDK     : $SDK (build-tools $BT_VER, android-$API)"
echo "Package : $PKG"

echo "=== 1. Cleaning & preparing build directories ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "=== 2. Compiling resources with aapt2 ==="
LINK_RES=()
if [ -d res ] && [ -n "$(ls -A res)" ]; then
    "$SDK_TOOLS/aapt2" compile --dir res -o "$BUILD_DIR/compiled_res.zip"
    LINK_RES+=("$BUILD_DIR/compiled_res.zip")
else
    echo "(no res/ directory, skipping)"
fi

echo "=== 3. Linking resources with aapt2 ==="
LINK_ARGS=(-I "$PLATFORM_JAR" --manifest AndroidManifest.xml
           -o "$BUILD_DIR/base.apk" --java "$BUILD_DIR/gen")
[ -d assets ] && LINK_ARGS+=(-A assets)
"$SDK_TOOLS/aapt2" link "${LINK_ARGS[@]}" ${LINK_RES[@]+"${LINK_RES[@]}"}

echo "=== 4. Compiling Java sources ==="
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find src -name '*.java' 2>/dev/null)
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$BUILD_DIR/gen" -name '*.java')
[ ${#SOURCES[@]} -gt 0 ] || die "No .java files found in src/"
javac -source 1.8 -target 1.8 -Xlint:-options \
    -cp "$PLATFORM_JAR" \
    -d "$BUILD_DIR/classes" \
    "${SOURCES[@]}"

echo "=== 5. Generating classes.dex with d8 ==="
CLASSES=()
while IFS= read -r f; do CLASSES+=("$f"); done < <(find "$BUILD_DIR/classes" -name '*.class')
"$SDK_TOOLS/d8" --lib "$PLATFORM_JAR" --output "$BUILD_DIR/dex" "${CLASSES[@]}"

echo "=== 6. Packaging classes.dex into APK ==="
( cd "$BUILD_DIR/dex" && zip -u "$BUILD_DIR/base.apk" classes.dex )

echo "=== 7. Aligning APK with zipalign ==="
"$SDK_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"

echo "=== 8. Signing APK ==="
KEYSTORE="${KEYSTORE:-$PROJECT_DIR/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -storepass "$KS_PASS" \
        -alias "$KS_ALIAS" \
        -keypass "$KS_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=$APP_NAME,O=Dev,C=US"
fi

mkdir -p "$(dirname "$OUT_APK")"
"$SDK_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --key-pass "pass:$KS_PASS" \
    --out "$OUT_APK" \
    "$BUILD_DIR/aligned.apk"

echo "=== BUILD SUCCESSFUL ==="
echo "Output APK: $OUT_APK"
ls -la "$OUT_APK"
