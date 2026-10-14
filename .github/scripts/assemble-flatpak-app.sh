#!/usr/bin/env bash
# 把 Gradle 打出来的 app-image 原样搬进 Flatpak 目录，并备好图标。
#
# ⚠️ 这里换过一次思路，值得记下来，因为它反直觉：
#
# 最开始是自己在 bin/ 下写一个启动脚本 exec runtime/bin/java。**这在 Compose
# 打包产物上必然跑不起来** —— Compose 给 jlink 传了 --strip-native-commands，
# 打出来的运行时里连一个可执行文件都没有。本机实测：
#   desktopApp/build/compose/tmp/main/runtime/bin/  67 个文件，全是 DLL，0 个 exe
#   desktopApp/build/compose/binaries/.../runtime/bin/  同上
# 唯一的启动器是 app-image 自带的原生启动器（Linux 在 bin/<packageName>，
# Windows 在 <packageName>.exe）。
#
# 所以本脚本不再重排目录、不再自己写启动脚本：**整个 app-image 原样拷贝**。
# 原生启动器靠 $APPDIR 的相对关系去找 lib/app 里的 .cfg 和 lib/runtime，
# 目录结构一改它就找不到；原样搬过去，这些关系天然保持。
#
# 单独成文件而不是塞进 workflow：这段逻辑有分支、有断言、以后还会改，
# 塞进 YAML 的 run: 里既难读也不好在本地复现。
set -euo pipefail

APP_IMAGE_ROOT="desktopApp/build/compose/binaries/main/app"
PACKAGING_DIR="packaging/flatpak"
STAGE="$PACKAGING_DIR/sundays-app"
APP_ID="com.kxxnzstdsw.Sundays"
ICON_SRC="desktopApp/src/main/resources/icons/sundays_256.png"

# 必须与 desktopApp/build.gradle.kts 里的 nativeDistributions.packageName 一致。
# 启动器文件名和 app/<同名>.cfg 是配套的，对不上就启动不了，所以这里显式断言，
# 而不是猜一个名字去 find。
LAUNCHER_NAME="com.kxxnzstdsw.sundays"

# jlink 直接产出的运行时。app-image 里的那份是 jpackage 从它拷过去的，
# 万一拷贝没落全（Gradle 构建缓存只还原任务声明过的输出时就可能发生），
# 拿这份原图顶上。两者内容本就同源。
JLINK_RUNTIME="desktopApp/build/compose/tmp/main/runtime"

say() { echo "::error::$1"; }

# 失败时唯一的匿名可读通道是 /check-runs/{id}/annotations：网页版会把大步骤日志
# 整个截断，原始日志下载要仓库权限。没有这条就只能靠猜 —— 前三次失败都是猜错了。
dump_lay_of_land() {
  local n=0 line
  if [ -f gradle-build.log ]; then
    say "Gradle 日志结论行：$(grep -aE 'BUILD (SUCCESSFUL|FAILED)' gradle-build.log | tail -n 1 || echo '（日志里没有 BUILD 行）')"
  else
    say "工作区没有 gradle-build.log，失败可能不在 Gradle"
  fi

  local base="desktopApp/build/compose"
  if [ ! -d "$base" ]; then
    say "连 $base 都不存在 —— Gradle 压根没往 build/compose 写东西"
    return
  fi

  while IFS= read -r line; do
    say "  目录 $line"; n=$((n + 1))
    [ "$n" -ge 25 ] && break
  done <<EOF
$(find "$base" -maxdepth 4 -type d 2>/dev/null | sort)
EOF

  say "app-image 文件总数 $(find "$APP_IMAGE_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')"
  while IFS= read -r line; do
    say "  JVM 库 $line"; n=$((n + 1))
    [ "$n" -ge 32 ] && break
  done <<EOF
$(find "$base" -type f \( -name 'libjvm.so' -o -name 'jvm.dll' \) 2>/dev/null | sort)
EOF
  while IFS= read -r line; do
    say "  启动器 $line"; n=$((n + 1))
    [ "$n" -ge 40 ] && break
  done <<EOF
$(find "$APP_IMAGE_ROOT" -type f -perm -u+x ! -name '*.jar' ! -name '*.so' 2>/dev/null | sort)
EOF
}

# --- 定位 app-image 根目录 --------------------------------------------------
# 判据：某个 bin/ 目录的父目录里同时躺着 app/ 或 lib/app（jar 与 .cfg 的所在）。
# lib/runtime/bin 也会被 find 命中，但它父目录里既没有 app 也没有 lib/app，
# 自然被排除 —— 这比按目录名猜层级可靠。
APP_IMAGE_DIR=""
while IFS= read -r b; do
  parent="$(dirname "$b")"
  if [ -d "$parent/lib/app" ] || [ -d "$parent/app" ]; then
    APP_IMAGE_DIR="$parent"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_ROOT" -type d -name bin 2>/dev/null | sort || true)
EOF

if [ -z "$APP_IMAGE_DIR" ]; then
  say "$APP_IMAGE_ROOT 下找不到「含 bin/ 且旁边有 app 或 lib/app」的应用根目录"
  dump_lay_of_land
  exit 1
fi
echo "app-image 根目录：$APP_IMAGE_DIR"

# --- 校验原生启动器 --------------------------------------------------------
# 必须用它，不能自己写脚本 exec java：运行时里没有 java 可执行文件（见文件头）。
LAUNCHER="$APP_IMAGE_DIR/bin/$LAUNCHER_NAME"
if [ ! -f "$LAUNCHER" ]; then
  say "找不到原生启动器 $LAUNCHER"
  say "若 packageName 改过，同步改本脚本的 LAUNCHER_NAME 与 packaging/flatpak/*.json 的 command"
  dump_lay_of_land
  exit 1
fi

# --- 组装：整棵目录原样搬过去 ----------------------------------------------
# 不重排层级。原生启动器靠 $APPDIR 相对定位 .cfg 与 lib/runtime，
# 一旦挪动位置就会在启动时（而不是构建时）才炸。
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$APP_IMAGE_DIR/." "$STAGE/"
# cp -a 已经保留执行位；这里再点一次是防 jpackage 在某个平台上漏掉。
chmod +x "$STAGE/bin/$LAUNCHER_NAME"

# 图标：Flatpak 的图标约定是 /app/share/icons/hicolor/256x256/apps/<app-id>.png，
# 源图正好是 256 的，manifest 的 build-commands 会把它装到那个位置。
cp "$ICON_SRC" "$PACKAGING_DIR/$APP_ID.png"

# --- 运行时自愈 ------------------------------------------------------------
# jpackage 是把 jlink 原图拷进 app-image 的。若 Gradle 构建缓存只还原了任务
# 声明过的输出，这一步拷过去的东西可能没落全，启动器就会在运行时报找不到
# libjli。发现没有 JVM 共享库就用原图补上，并说清楚补了没有。
JVM_LIB=""
for probe in "$STAGE/lib/runtime/lib/server/libjvm.so" "$STAGE/runtime/lib/server/libjvm.so"; do
  if [ -f "$probe" ]; then
    JVM_LIB="$probe"
    break
  fi
done

if [ -z "$JVM_LIB" ] && [ -f "$JLINK_RUNTIME/lib/server/libjvm.so" ]; then
  say "app-image 里的运行时缺 JVM 共享库，改用 jlink 原图 $JLINK_RUNTIME 补齐"
  rm -rf "$STAGE/lib/runtime" "$STAGE/runtime"
  cp -a "$JLINK_RUNTIME" "$STAGE/lib/runtime"
  JVM_LIB="$STAGE/lib/runtime/lib/server/libjvm.so"
fi

if [ -z "$JVM_LIB" ]; then
  say "既没有 app-image 自带的运行时，也没有可用的 jlink 原图（$JLINK_RUNTIME）"
  dump_lay_of_land
  exit 1
fi
echo "JVM 共享库：$JVM_LIB"

JAR_DIR=""
for cand in "$STAGE/lib/app" "$STAGE/app"; do
  if [ -d "$cand" ] && [ -n "$(find "$cand" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
    JAR_DIR="$cand"
    break
  fi
done

if [ -z "$JAR_DIR" ]; then
  say "$STAGE 下找不到装着 jar 的目录（lib/app 或 app）"
  dump_lay_of_land
  exit 1
fi
echo "jar 目录：$JAR_DIR（$(find "$JAR_DIR" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ') 个 jar）"
echo "已组装 $STAGE"
du -sh "$STAGE" | awk '{print "  体积 " $1}'
