#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
KS="$HOME/.wxr-debug.jks"
OUT="$ROOT/dist"
TODAY="$(date +%Y%m%d)"
APP=wx-noti-collector

# 优先用 JDK21（javac -source 8 需要）
JDK21="$(ls -d /usr/lib/jvm/java-21-openjdk-* 2>/dev/null | head -1 || true)"
if [ -n "$JDK21" ] && [ -x "$JDK21/bin/javac" ]; then export PATH="$JDK21/bin:$PATH"; fi

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug" >/dev/null 2>&1
fi

rm -rf "$ROOT/build" "$OUT"
mkdir -p "$ROOT/build" "$OUT"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$ROOT/build/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$ROOT/build/base.apk" -I "$PLAT" \
  --manifest "$ROOT/AndroidManifest.xml" --java "$ROOT/build" \
  --min-sdk-version 24 --target-sdk-version 34 "$ROOT/build/res.zip"

echo "[3/6] javac"
javac -source 8 -target 8 -Xlint:-options -classpath "$PLAT" -d "$ROOT/build/classes" \
  "$ROOT"/src/com/khria/wxnoti/*.java "$ROOT"/build/com/khria/wxnoti/R.java

echo "[4/6] d8"
mkdir -p "$ROOT/build/dex"
"$BT/d8" --release --lib "$PLAT" --output "$ROOT/build/dex" \
  $(find "$ROOT/build/classes" -name '*.class')

echo "[5/6] zipalign"
cp "$ROOT/build/base.apk" "$ROOT/build/unsigned.apk"
cd "$ROOT/build/dex" && zip -q -j "$ROOT/build/unsigned.apk" classes.dex
"$BT/zipalign" -f 4 "$ROOT/build/unsigned.apk" "$OUT/$APP-$TODAY.apk"

echo "[6/6] apksigner"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --out "$OUT/$APP-$TODAY-signed.apk" "$OUT/$APP-$TODAY.apk"
mv "$OUT/$APP-$TODAY-signed.apk" "$OUT/$APP-$TODAY.apk"
echo "OK: $OUT/$APP-$TODAY.apk"
