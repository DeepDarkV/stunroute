#!/bin/sh
# 构建 stunroute 模块 zip（在 Linux 环境运行）
set -e
cd "$(dirname "$0")"

VER=$(grep '^version=' module/module.prop | cut -d= -f2)
OUT="out/stunroute-${VER}.zip"

rm -rf build dexout stage out
mkdir -p build dexout stage out

javac --release 11 -encoding UTF-8 -d build $(find src -name '*.java')
java -cp /workspace/r8.jar com.android.tools.r8.D8 --min-api 24 --output dexout $(find build -name '*.class')

cp -r module/. stage/
mkdir -p stage/bin
cp dexout/classes.dex stage/bin/stunroute.dex
cp module/default-services.conf stage/default-services.conf
cp README.md stage/README.md

chmod 755 stage/service.sh stage/ctl.sh stage/action.sh stage/customize.sh stage/uninstall.sh
chmod 644 stage/module.prop stage/default-services.conf stage/bin/stunroute.dex

cd stage
zip -qr "../$OUT" .
cd ..

echo "built: $OUT"
ls -la "$OUT"
