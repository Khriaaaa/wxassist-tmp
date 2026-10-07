#!/bin/bash
# wxassist 免 Gradle 构建：aapt2 → javac → d8 → zipalign → apksigner
set -e
SDK="${1:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
# 优先 JDK21，没有用默认 javac
JH=$(ls -d /usr/lib/jvm/java-21-openjdk-* 2>/dev/null | head -1)
[ -n "$JH" ] && export JAVA_HOME="$JH" && PATH="$JAVA_HOME/bin:$PATH"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
DIST="$ROOT/dist"
KS="$HOME/.wxr-debug.jks"
rm -rf "$OUT" "$DIST"; mkdir -p "$OUT/classes" "$OUT/gen" "$DIST"

# 1 编译资源
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

# 2 link + R.java
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" --auto-add-overlay "$OUT/res.zip"

# 3 javac -source 8
find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -source 8 -target 8 -classpath "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"

# 4 d8 --min-api 24
"$BT/d8" --min-api 24 --lib "$PLAT" --output "$OUT" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/final.apk"
cd "$OUT" && zip -j final.apk classes.dex >/dev/null
cd "$ROOT"

# 5 keystore 若无则生成
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=wxassist" >/dev/null 2>&1
fi

# 6 zipalign + sign
"$BT/zipalign" -f 4 "$OUT/final.apk" "$OUT/aligned.apk"
DATE=$(date +%Y%m%d)
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$DIST/wxassist-$DATE.apk" "$OUT/aligned.apk"
echo "APK_OK: $DIST/wxassist-$DATE.apk"
