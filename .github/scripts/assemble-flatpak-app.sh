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

# 失败时唯一的匿名可读通道是 /check-runs/{id}/annotations：网页版会把大步骤日志
# 整个截断，原始日志下载要仓库权限。所以「到底产出了什么」必须打成 annotation，
# 否则就只能靠猜 —— 前两次失败都是猜布局猜错了。
# 上限 40 行：再多 GitHub 那边不好翻，也挤占真正报错的可见度。
say() { echo "::error::$1"; }

dump_lay_of_land() {
  local n=0 line
  if [ -f gradle-build.log ]; then
    say "Gradle 日志结论行：$(grep -aE 'BUILD (SUCCESSFUL|FAILED)' gradle-build.log | tail -n 1 || echo '（日志里没有 BUILD 行）')"
  else
    say "工作区没有 gradle-build.log，失败可能不在 Gradle"
  fi

  if [ ! -d "$APP_IMAGE_ROOT" ]; then
    say "搜索根 $APP_IMAGE_ROOT 不存在"
    local base="desktopApp/build/compose"
    if [ -d "$base" ]; then
      while IFS= read -r line; do
        say "  目录 $line"; n=$((n + 1))
        [ "$n" -ge 30 ] && break
      done <<EOF
$(find "$base" -maxdepth 4 -type d 2>/dev/null | sort)
EOF
    else
      say "连 $base 都不存在 —— Gradle 压根没往 build/compose 写东西"
    fi
    return
  fi

  say "搜索根存在，共 $(find "$APP_IMAGE_ROOT" -type f | wc -l | tr -d ' ') 个文件"
  while IFS= read -r line; do
    say "  目录 $line"; n=$((n + 1))
    [ "$n" -ge 30 ] && break
  done <<EOF
$(find "$APP_IMAGE_ROOT" -maxdepth 4 -type d 2>/dev/null | sort)
EOF
  while IFS= read -r line; do
    say "  运行时候选 $line"; n=$((n + 1))
    [ "$n" -ge 40 ] && break
  done <<EOF
$(find "$APP_IMAGE_ROOT" -type f -name 'java*' 2>/dev/null | sort)
EOF
}

if [ ! -d "$APP_IMAGE_ROOT" ]; then
  say "$APP_IMAGE_ROOT 不存在 —— app-image 根本没打出来"
  dump_lay_of_land
  exit 1
fi

# --- 定位 jlink 出来的运行时 ------------------------------------------------
# ⚠️ 这里栽过两次坑，值得写下来：
#
# 一、GNU find 的 -path 用 fnmatch(FNM_PATHNAME)，**通配符 * 不跨 '/'**。
#    所以 `-path '*/runtime/bin/java'` 匹配的是「**恰好一段**前缀 + /runtime/bin/java」，
#    而真实路径是 <app>/lib/runtime/bin/java，有两段前缀，永远匹配不上。
#    教训：find 的 -path 不是 shell 的 glob。要么用 -name（按基名匹配），要么自己遍历。
#
# 二、Windows 上实测到的是 <app>/runtime/bin/java，Linux 的 jpackage 按惯例是
#    <app>/lib/runtime/bin/java。**后者是推断，不是实测** —— 所以这里不写死
#    「祖父目录必须叫 runtime」，而是先找标准的 runtime/bin/java，找不到就退而
#    接受任意 bin/java，两段 dirname 照样落在 app 根目录上。
JAVA_BIN=""
while IFS= read -r cand; do
  if [ "$(basename "$(dirname "$cand")")" = "bin" ] &&
     [ "$(basename "$(dirname "$(dirname "$cand")")")" = "runtime" ]; then
    JAVA_BIN="$cand"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_ROOT" -type f -name java 2>/dev/null | sort || true)
EOF

if [ -z "$JAVA_BIN" ]; then
  while IFS= read -r cand; do
    if [ "$(basename "$(dirname "$cand")")" = "bin" ]; then
      JAVA_BIN="$cand"
      break
    fi
  done <<EOF
$(find "$APP_IMAGE_ROOT" -type f -name java 2>/dev/null | sort || true)
EOF
fi

if [ -z "$JAVA_BIN" ]; then
  say "$APP_IMAGE_ROOT 下找不到可执行入口 java —— app-image 没打出来，或布局跟预期不同"
  dump_lay_of_land
  exit 1
fi

# <...>/<runtime>/bin/java 往上两级就是 app 根目录；退化情形 <...>/bin/java
# 则落在它的上一层，但那一层同时装着 bin 与 app，拷成 runtime 依然能用。
RUNTIME_DIR="$(dirname "$(dirname "$JAVA_BIN")")"
APP_IMAGE_DIR="$(dirname "$RUNTIME_DIR")"
echo "java 入口：$JAVA_BIN"
echo "app-image 根目录：$APP_IMAGE_DIR"

# --- 定位 jar 目录 ---------------------------------------------------------
# Linux 是 lib/app，Windows 是 app，深度不一。不写死平台，找「叫 app 且底下有 jar」的。
LIB_DIR=""
while IFS= read -r d; do
  if [ -d "$d" ] && [ -n "$(find "$d" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
    LIB_DIR="$d"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_DIR" -maxdepth 3 -type d -name app 2>/dev/null | sort || true)
EOF

if [ -z "$LIB_DIR" ]; then
  say "$APP_IMAGE_DIR 下找不到装着 jar 的 app 目录"
  dump_lay_of_land
  exit 1
fi

JAR_COUNT="$(find "$LIB_DIR" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ')"
if [ "$JAR_COUNT" -lt 1 ]; then
  say "$LIB_DIR 下一个 jar 都没有，依赖没打进来"
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
