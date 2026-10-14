#!/usr/bin/env bash
# 把 Gradle 打出来的 app-image 摆成 Flatpak 需要的目录结构，并生成启动脚本。
#
# 单独成文件而不是塞进 workflow：这段逻辑有分支、有断言、以后还会改，
# 塞进 YAML 的 run: 里既难读也不好在本地复现。
set -euo pipefail

APP_IMAGE_ROOT="desktopApp/build/compose/binaries/main/app"
PACKAGING_DIR="packaging/flatpak"
STAGE="$PACKAGING_DIR/sundays-app"
APP_ID="com.kxxnzstdsw.Sundays"
ICON_SRC="desktopApp/src/main/resources/icons/sundays_256.png"

# --- 定位 app-image --------------------------------------------------------
# 刻意**不写死路径**。Compose 在 Linux 上的 app-image 布局是否与 Windows 一致
# 文档没讲清楚，写死一旦对不上就是「文件不存在」这种没法自查的错。
# 改用 find 去找那个唯一确定存在的产物：jlink 打出来的 java 可执行文件。
JAVA_BIN="$(find "$APP_IMAGE_ROOT" -type f -path '*/runtime/bin/java' -print -quit 2>/dev/null || true)"
if [ -z "$JAVA_BIN" ]; then
  echo "::error::$APP_IMAGE_ROOT 下找不到 runtime/bin/java —— app-image 根本没打出来" >&2
  exit 1
fi

# runtime/bin/java -> .../<pkg>/runtime -> .../app/<pkg> -> jar 目录是它下面的 app/
RUNTIME_DIR="$(dirname "$(dirname "$JAVA_BIN")")"
APP_IMAGE_DIR="$(dirname "$RUNTIME_DIR")"
LIB_DIR="$APP_IMAGE_DIR/app"

if [ ! -d "$LIB_DIR" ]; then
  echo "::error::$LIB_DIR 不是目录，app-image 布局与预期不符（找的是 $JAVA_BIN）" >&2
  exit 1
fi

JAR_COUNT="$(find "$LIB_DIR" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ')"
if [ "$JAR_COUNT" -lt 1 ]; then
  echo "::error::$LIB_DIR 下一个 jar 都没有，依赖没打进来" >&2
  exit 1
fi
echo "app-image: $APP_IMAGE_DIR（$JAR_COUNT 个 jar）"

# --- 组装 ------------------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/app"
cp -a "$RUNTIME_DIR" "$STAGE/runtime"
cp -a "$LIB_DIR/." "$STAGE/app/"

# 图标：Flatpak 的图标约定是 /app/share/icons/hicolor/256x256/apps/<app-id>.png，
# 源图正好是 256 的，manifest 的 build-commands 会把它装到那个位置。
cp "$ICON_SRC" "$PACKAGING_DIR/$APP_ID.png"

# --- 启动脚本 --------------------------------------------------------------
# 不用 Compose 自带的原生启动器：它靠相对路径回溯去找 runtime/，
# 在 Flatpak 沙箱里行为不易预期。自己 exec 出错时日志直接指向 java，好排查得多。
cat > "$STAGE/bin/sundays" <<'LAUNCHER'
#!/bin/sh
HERE="$(cd "$(dirname "$0")/.." && pwd)"

# -cp 整串加引号是关键：shell 就不会做通配展开，而 Java 认 "目录/*" 这个写法
# （展开成该目录下所有 jar）。若让 shell 先展开，会变成一串空格分隔的路径，
# java 只把第一个当 classpath，剩下的全被当成主类和参数 —— 报的是莫名其妙的错。
exec "$HERE/runtime/bin/java" \
  -Djava.awt.headless=false \
  -cp "$HERE/app/*" \
  com.kxxnzstdsw.sundays.MainKt "$@"
LAUNCHER
chmod +x "$STAGE/bin/sundays"

echo "已组装 $STAGE"
du -sh "$STAGE" | awk '{print "  体积 " $1}'