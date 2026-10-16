#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# 
# Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>

# 从 Gradle 打出来的 app-image 里**只取应用本体**（jar + resources + 原生资源），
# 另写一个 shell 启动器，JDK 交给 Flathub 的 freedesktop 扩展提供。
#
# ⚠️ 这里换过一次思路，第二次，记下来免得又绕回去：
#
# 第一版是「整个 app-image 原样搬 + 用它自带的原生启动器」。那是因为 Compose 给
# jlink 传了 --strip-native-commands，打出来的运行时里连一个可执行文件都没有
# （本机实测 desktopApp/build/compose/tmp/main/runtime/bin/ 67 个文件全是 DLL、
# 0 个 exe），所以「自己写脚本 exec runtime/bin/java」在原理上跑不通。
#
# 第二版不再自带 JDK。**关键事实：org.freedesktop.Platform 运行时里没有 JVM**，
# Platform 与 Sdk 都不提供（真正给 JVM 的是 SDK 扩展 org.freedesktop.Sdk.Extension.openjdk，
# 且它只能构建时可见，运行时要用必须靠它自带的 install.sh 把 JVM 落到 /app）。
# 所以「用运行时的 JDK」在 Flatpak 里的准确含义是：改用 Freedesktop 官方扩展提供的
# OpenJDK，而不是 jpackage 用 jlink 现场裁出来的那一份。
# 换来的好处有三：
#   1. bundle 里不再塞一份与 glibc 绑死的自裁 JDK，构建机与运行时的 glibc 差异从此无关；
#   2. 那份 JDK 由 Freedesktop SDK 团队针对同版本 runtime 编译，java.desktop 等模块齐备；
#   3. 版本跟随 Flathub 更新，不必跟着 Gradle 每次重新裁。
# 代价是 /app/jre 仍然占体积（那由扩展的 install.sh 决定，不归我们管）。
#
# 单独成文件而不是塞进 workflow：这段逻辑有分支、有断言、以后还会改，
# 塞进 YAML 的 run: 里既难读也不好在本地复现。
set -euo pipefail

APP_IMAGE_ROOT="desktopApp/build/compose/binaries/main/app"
PACKAGING_DIR="packaging/flatpak"
STAGE="$PACKAGING_DIR/sundays-app"
# 应用本体在包内的落点。**磁盘相对路径与包内绝对路径必须分开写两个变量**：
# 曾经图省事拼成 "$STAGE$APPDIR_IN_APP"，而后者以 / 开头，拼出来是
# sundays-app/app/share/sundays/app —— 一个看着像绝对路径、其实是相对路径的东西，
# 于是所有文件整整错了一层目录，而脚本自己的断言查的也是同一个错路径，
# 一路绿灯直到打包完才发现少了 jar。
APPDIR_REL="share/sundays/app"
APPDIR_IN_APP="/app/$APPDIR_REL"
# 全小写：Flatpak 应用 ID 只允许 [a-z0-9_-] 与点。首版这里写的是
# com.langbiantianya.Sundays（大写 S），flatpak-builder 当场拒收，0 秒退出。
APP_ID="com.langbiantianya.sundays"
# 必须与 desktopApp/build.gradle.kts 里的 nativeDistributions.packageName 一致，
# 同时也是 jpackage 生成的 .cfg 文件名。
LAUNCHER_NAME="com.langbiantianya.sundays"
ICON_SRC="desktopApp/src/main/resources/icons/sundays_256.png"

say() { echo "::error::$1"; }

# 失败时唯一的匿名可读通道是 /check-runs/{id}/annotations：网页版会把大步骤日志
# 整个截断，原始日志下载要仓库权限。没有这条就只能靠猜 —— 前几次失败都是猜错了。
#
# ⚠️ 但这条通道有上限：GitHub 每个步骤只保留**前 10 条** workflow-command
# annotation，多发的直接丢弃。所以这里必须把最重要的放最前面、总条数压在 8 以内，
# 而不是「能打多少打多少」—— 曾经一口气打 40 条目录树，结果真正有用的那几行
# 排在第 11 条之后，一条都没留下来。
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

  # 目录树压成一行，省额度也更好读。-mindepth 1 是为了不把 base 自己再列一遍。
  say "compose 子目录：$(find "$base" -mindepth 1 -maxdepth 3 -type d 2>/dev/null | sed "s|^$base/||" | tr '\n' ' ' | head -c 400)"
  say "app-image 文件总数 $(find "$APP_IMAGE_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')"
  while IFS= read -r line; do
    [ -n "$line" ] || continue          # 没有候选时别打一条空 annotation，白占额度
    say "  jar 目录候选 $line"; n=$((n + 1))
    [ "$n" -ge 3 ] && break
  done <<EOF
$(find "$APP_IMAGE_ROOT" -type d \( -name app -o -path '*/lib/app' \) 2>/dev/null | sort)
EOF
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    say "  cfg/启动器 $line"; n=$((n + 1))
    [ "$n" -ge 5 ] && break
  done <<EOF
$(find "$APP_IMAGE_ROOT" -maxdepth 3 -type f \( -name '*.cfg' -o -name '*.exe' \) 2>/dev/null | sort)
EOF
}

# --- 定位 jar 目录（应用本体所在） ------------------------------------------
# 判据是「目录里有 jar 也有 jpackage 生成的 .cfg」，而不是目录叫什么。
#
# 曾经这里是「找含 bin/ 且旁边有 app 或 lib/app 的根」，那在 Linux 上成立，
# 在 Windows 上却从来没成立过：jpackage 的 Windows 布局压根没有 bin/，
# 启动器就是根目录下的 com.langbiantianya.sundays.exe。本仓库主要在 Windows 上开发，
# 一条判据只对一半平台成立就意味着本地永远复现不了 CI 的路径 ——
# 而这份脚本的价值恰恰在于能在本地跑。
#
# .cfg 那半个条件也不是凑数：主类与 java-options 都要从它里面读，
# 读不到就必须硬失败，总比猜一个主类名、等到用户点开始菜单才显形好。
JAR_DIR=""
while IFS= read -r d; do
  if [ -n "$(find "$d" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ] \
     && [ -n "$(find "$d" -maxdepth 1 -name '*.cfg' -print -quit 2>/dev/null)" ]; then
    JAR_DIR="$d"
    break
  fi
done <<EOF
$(find "$APP_IMAGE_ROOT" -type d -name app 2>/dev/null | sort || true)
EOF

if [ -z "$JAR_DIR" ]; then
  say "$APP_IMAGE_ROOT 下找不到「同时含 jar 与 .cfg」的 app 目录"
  say "主类只能从 .cfg 的 app.mainclass 取，取不到就不猜 —— 猜错只会在运行时炸"
  dump_lay_of_land
  exit 1
fi
APP_IMAGE_DIR="$(dirname "$JAR_DIR")"
[ "$(basename "$APP_IMAGE_DIR")" = lib ] && APP_IMAGE_DIR="$(dirname "$APP_IMAGE_DIR")"
echo "app-image 根目录：$APP_IMAGE_DIR"
echo "jar 目录：$JAR_DIR（$(find "$JAR_DIR" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ') 个 jar）"

# --- 读 jpackage 的 .cfg ----------------------------------------------------
# 主类与 JVM 参数一律从 jpackage 自己生成的配置里读，不在脚本里再写死第二份。
# 两份一旦漂移（改了 mainClass 却忘了改脚本），症状是启动时 ClassNotFoundException，
# 而那要等到用户点开始菜单才显形。CFG 是 jpackage 的单一事实来源。
CFG="$(find "$JAR_DIR" -maxdepth 1 -name "$LAUNCHER_NAME.cfg" -print -quit 2>/dev/null || true)"
if [ -z "$CFG" ]; then
  say "$JAR_DIR 下找不到 $LAUNCHER_NAME.cfg"
  dump_lay_of_land
  exit 1
fi

MAINCLASS="$(sed -n 's/^app\.mainclass=//p' "$CFG" | head -n1 | tr -d '\r')"
if [ -z "$MAINCLASS" ]; then
  say "$CFG 里没有 app.mainclass —— 组装脚本拒绝猜主类"
  dump_lay_of_land
  exit 1
fi
echo "主类：$MAINCLASS"

# java-options 逐行收集，拼成一行可以直接塞进 exec 的实参列表。
# $APPDIR 是 jpackage 留给启动器的占位符，换成本脚本自己的落点；
# Windows 上生成的 cfg 用反斜杠做分隔符，统一成 /，这样本地跑出来的脚本
# 和 Linux 上生成的不会有区别。
OPTS=""
while IFS= read -r raw; do
  opt="${raw%$'\r'}"
  opt="${opt#java-options=}"
  [ -n "$opt" ] || continue
  # 单引号会让下面生成的 shell 脚本失配。与其写一套转义规则，不如当场拒绝：
  # jpackage 生成的参数里不会出现单引号，出现了说明哪里不对，该报错而不是硬转。
  case "$opt" in
    *"'"*) say ".cfg 的 java-options 里出现单引号，无法安全生成启动脚本：$opt"; exit 1 ;;
  esac
  opt="${opt//\$APPDIR/$APPDIR_IN_APP}"
  opt="${opt//\\//}"
  OPTS="$OPTS '$opt'"
done <<EOF
$(grep -E '^java-options=' "$CFG" || true)
EOF
if [ -z "$OPTS" ]; then
  say "$CFG 里一条 java-options 都没有，生成出来的启动器等于裸跑主类"
  exit 1
fi

# --- Flatpak 专属的追加参数 -------------------------------------------------
# 只对这份 Linux 包生效、但必须由我们加的 JVM 参数。
#
# 症状：GNOME 51 运行时里一点就抛
#   NullPointerException: Cannot invoke "java.lang.Integer.intValue()"
#     at java.desktop/com.sun.java.swing.plaf.gtk.GTKLookAndFeel.initialize(GTKLookAndFeel.java:1473)
#
# 那是 JDK 自己的空指针。GTKLookAndFeel.initialize() 里就两行：
#   int gnomeShellVersion = unixToolkit.getGnomeShellMajorVersion();
#   shouldApplyTheme = gnomeShellVersion >= 47;
# getGnomeShellMajorVersion() 返回 Integer，要靠执行 `gnome-shell --version`
# 才有值；Flatpak 沙箱里没有 gnome-shell 这个二进制（它属于 GNOME 会话，
# 不属于运行时），于是返回 null，自动拆箱当场炸。
# **不是我们代码的错，也没有「保持 GTK 但不炸」的配置项。**
#
# 为什么是 -Dswing.systemlaf 而不是 -Dswing.defaultlaf：
# skiko 选外观就一行（Actuals.awt.kt）：
#   UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
# 而 UIManager.getSystemLookAndFeelClassName() 读的系统属性是 **swing.systemlaf**
# —— 先读它，没有才按操作系统猜（Windows → WindowsLookAndFeel，
# Linux + GNOME → GTKLookAndFeel，macOS → Aqua）。
# 另外 UIManager.setLookAndFeel(String) 只按传进来的类名实例化，
# 压根不读 swing.defaultlaf。所以后者在这里完全不起作用：写上去会让人
# 以为修好了，实际一点用没有。
#
# 为什么放在这个脚本而不是 build.gradle.kts：jpackage 的 --java-options
# 是全局的，写进构建脚本会连 Windows 与 macOS 一起改掉，而那两边本来就该用
# 原生外观（WindowsLookAndFeel / AquaLookAndFeel），改成 Metal 是纯倒退。
#
# 这是**追加**而不是第二份拷贝：.cfg 里的主类与 java-options 仍然只有那一份，
# 启动器里的 java 参数仍然是「读来的 + 这一条」。
FLATPAK_ONLY_OPTS="-Dswing.systemlaf=javax.swing.plaf.metal.MetalLookAndFeel"
case "$FLATPAK_ONLY_OPTS" in
  *"'"*) say "FLATPAK_ONLY_OPTS 里有单引号，无法安全塞进启动脚本"; exit 1 ;;
esac
OPTS="$OPTS '$FLATPAK_ONLY_OPTS'"

echo "java-options 条数：$(printf '%s' "$OPTS" | tr -c "'" '\n' | grep -c "'")"
echo "其中 Flatpak 追加：$FLATPAK_ONLY_OPTS"

# --- 组装 -------------------------------------------------------------------
# 只搬应用本体：jar、resources、以及 jpackage 顺手拷进来的原生资源
# （Linux 上是 libskiko-linux-*.so 与 libicudtl.dat，Windows 上是对应的 .dll/.dat）。
# **不搬 jlink 运行时** —— 它是这一版要摆脱的东西。
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/$APPDIR_REL"
cp -a "$JAR_DIR/." "$STAGE/$APPDIR_REL/"

if [ ! -d "$STAGE/$APPDIR_REL/resources" ]; then
  # 只告警，不失败。本机实测 Windows 产物里这个目录是**空的**，Linux 上会不会
  # 干脆没有也没验证过，而 jpackage 的 .cfg 里那句 -Dcompose.application.resources.dir
  # 指向的就是它 —— 目录不存在时 Compose 会回退到 classpath，功能不受影响。
  # 曾经这里是个硬失败：为一个「本来就可以没有」的东西卡住整条流水线，
  # 是这个仓库吃过好几次的亏（同类教训另见本脚本开头的 annotation 额度那段）。
  echo "提示：没有 resources 目录，Compose 会回退到 classpath 取资源"
fi
if [ -z "$(find "$STAGE/$APPDIR_REL" -maxdepth 1 -name '*.jar' -print -quit 2>/dev/null)" ]; then
  say "$STAGE/$APPDIR_REL 下没有 jar"
  exit 1
fi
if find "$STAGE" -type d -name runtime -print -quit 2>/dev/null | grep -q .; then
  say "$STAGE 里出现了 runtime 目录 —— jlink 运行时不该被搬进来"
  exit 1
fi

# --- 生成启动器 -------------------------------------------------------------
# 一个 /bin/sh 脚本，exec Freedesktop 扩展装到 /app/jre 的 java。
# classpath 用 Java 通配符而不是逐个列 160 个 jar：jpackage 把 classpath 折成了
# 多行续行（实测 cfg 共 169 行，classpath 就占了 160 行），解析它比不解析更脆，
# 而 -cp '/path/*' 由 JVM 自己按目录枚举，语义完全一致。
#
# exec 写成**一行**，不用反斜杠续行。续行要同时躲两层转义（printf 的格式串
# 和 shell 的反斜杠），实测写错过一次，结果每个 java-options 都被截断成独立一行，
# java 只收到第一个参数 —— 而 sh -n 完全看不出来。
LAUNCHER="$STAGE/bin/$LAUNCHER_NAME"
{
  printf '%s\n' '#!/bin/sh'
  printf '%s\n' '# 由 .github/scripts/assemble-flatpak-app.sh 从 jpackage 的 .cfg 生成，勿手改。'
  printf '%s\n' '# 入口原来用 app-image 自带的原生二进制；现在 JDK 由'
  printf '%s\n' '# org.freedesktop.Sdk.Extension.openjdk 提供，这里只负责把 JVM 拉起来。'
  printf '%s\n' 'set -eu'
  printf '%s\n' "APPDIR='$APPDIR_IN_APP'"
  printf '%s\n' 'JAVA=/app/jre/bin/java'
  printf '%s\n' '# 兜底：万一扩展换了个落点，退到 PATH 上找 java。'
  printf '%s\n' 'if [ ! -x "$JAVA" ]; then JAVA="$(command -v java 2>/dev/null || true)"; fi'
  printf '%s\n' 'if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then'
  printf '%s\n' '  echo "Sundays: 找不到可用的 java（期望 /app/jre/bin/java，来自 org.freedesktop.Sdk.Extension.openjdk）" >&2'
  printf '%s\n' '  exit 127'
  printf '%s\n' 'fi'
  printf '%s\n' "exec \"\$JAVA\"$OPTS -cp \"\$APPDIR/*\" '$MAINCLASS' \"\$@\""
} > "$LAUNCHER"
chmod +x "$LAUNCHER"

if [ ! -s "$LAUNCHER" ]; then
  say "生成的启动器是空文件：$LAUNCHER"
  exit 1
fi

# 图标：Flatpak 的图标约定是 /app/share/icons/hicolor/256x256/apps/<app-id>.png，
# 源图正好是 256 的，manifest 的 build-commands 会把它装到那个位置。
cp "$ICON_SRC" "$PACKAGING_DIR/$APP_ID.png"

echo "启动器：$LAUNCHER"
echo "已组装 $STAGE"
du -sh "$STAGE" | awk '{print "  体积 " $1}'
