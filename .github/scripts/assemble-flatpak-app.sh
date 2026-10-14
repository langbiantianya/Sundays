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

if [ ! -d "$APP_IMAGE_ROOT" ]; then
  echo "::error::$APP_IMAGE_ROOT 不存在 —— app-image 根本没打出来" >&2
  exit 1
fi

# --- 定位 jlink 出来的运行时 ------------------------------------------------
# ⚠️ 这里是第一版栽的坑，值得写下来：
# GNU find 的 -path 用 fnmatch(FNM_PATHNAME)，**通配符 * 不跨 '/'**。
# 所以 `-path '*/runtime/bin/java'` 匹配的是「**恰好一段**前缀 + /runtime/bin/java」，
# 而真实路径有三段前缀（<appName>/lib/runtime/bin/java），永远匹配不上，
# 表现为「明明打出来了却说找不到」。
# 教训：find 的 -path 不是 shell 的 glob。要么用 -name（按基名匹配，不涉及斜杠），
# 要么自己遍历。本文件统一走 -name。
JAVA_BIN=""
while IFS= read -r cand; do
  # 只认 <...>/runtime/bin/java 这个形状
  if [ "$(basename "$(dirname "$cand")")" = "bin" ] &&
     [ "$(basename "$(dirname "$(dirname "$cand")")")" = "runtime" ]; then
    JAVA_BIN="$cand"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_ROOT" -type f -name java 2>/dev/null || true)
EOF

if [ -z "$JAVA_BIN" ]; then
  echo "::error::$APP_IMAGE_ROOT 下找不到 runtime/bin/java —— app-image 没打出来或布局变了" >&2
  echo "实际目录树（前 3 层）：" >&2
  find "$APP_IMAGE_ROOT" -maxdepth 3 -type d 2>/dev/null | head -n 40 >&2
  exit 1
fi

# Linux 的 jpackage app-image 是 <app>/lib/runtime，Windows 是 <app>/runtime。
# 两段 dirname 刚好都落在「app 根目录」上，所以这里不用区分平台。
RUNTIME_DIR="$(dirname "$(dirname "$JAVA_BIN")")"
APP_IMAGE_DIR="$(dirname "$RUNTIME_DIR")"
echo "app-image 根目录：$APP_IMAGE_DIR"

# --- 定位 jar 目录 ---------------------------------------------------------
# 同样两平台不同：Linux 是 lib/app，Windows 是 app。
# 不写死，改成「找一个叫 app 且底下有 jar 的目录」。
LIB_DIR=""
while IFS= read -r d; do
  if [ -d "$d" ] && [ -n "$(find "$d" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
    LIB_DIR="$d"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_DIR" -maxdepth 2 -type d -name app 2>/dev/null || true)
EOF

if [ -z "$LIB_DIR" ]; then
  echo "::error::$APP_IMAGE_DIR 下找不到装着 jar 的 app 目录" >&2
  find "$APP_IMAGE_DIR" -maxdepth 2 -type d 2>/dev/null | head -n 40 >&2
  exit 1
fi

JAR_COUNT="$(find "$LIB_DIR" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ')"
if [ "$JAR_COUNT" -lt 1 ]; then
  echo "::error::$LIB_DIR 下一个 jar 都没有，依赖没打进来" >&2
  exit 1
fi
echo "jar 目录：$LIB_DIR（$JAR_COUNT 个 jar）"

# --- 组装 ------------------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/app"
cp -a "$RUNTIME_DIR" "$STAGE/runtime"
cp -a "$LIB_DIR/." "$STAGE/app/"

# 图标：Flatpak 的图标约定是 /app/share/icons/hicolor/256x256/apps/<app-id>.png，
# 源图正好是 256 的，manifest 的 build-commands 会把它装到那个位置。
cp "$ICON_SRC" "$PACKAGING_DIR/$APP_ID.png"

# --- 启动脚本 --------------------------------------------------------------
# 不用 Compose 自带的原生启动器：Linux 上它在 bin/<name>，靠相对路径回溯去找
# lib/runtime，在 Flatpak 沙箱里行为不易预期。自己 exec 出错时日志直接指向
# java，好排查得多。
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