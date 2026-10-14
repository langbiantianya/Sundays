# Flatpak 打包

Sundays 的 Flatpak 定义。CI 见 [`.github/workflows/build-flatpak.yml`](../../.github/workflows/build-flatpak.yml)，
它同时产出 `amd64` 与 `arm64` 两个架构，各跑在**原生 runner** 上
（`ubuntu-latest` 与 `ubuntu-24.04-arm`）—— 不交叉编译、不走 QEMU 模拟。

```bash
# 本地只跑组装这一步（前面的 app-image 得先打出来）
./gradlew :desktopApp:createDistributable
bash .github/scripts/assemble-flatpak-app.sh

# 再往下需要 flatpak-builder 与 freedesktop 运行时
flatpak-builder --force-clean --repo=flatpak-repo flatpak-build \
  packaging/flatpak/com.kxxnzstdsw.sundays.json
flatpak build-bundle flatpak-repo sundays.flatpak com.kxxnzstdsw.sundays master
```

bundle 里含的是本应用（外加同 repo 里的 debug runtime）；
`org.freedesktop.Platform` 与 OpenJDK 扩展来自 Flathub，安装时按 metadata 里的
声明去取，所以换机器装它仍需要能访问 Flathub。

**导出时分支取 ref 的最后一段。**
`flatpak-builder` 导出的 ref 完整形状是 `app/<id>/<arch>/<branch>` ——
实测就是 `app/com.kxxnzstdsw.sundays/x86_64/master`。取「`app/<id>/` 之后的
全部」会把 `<arch>` 一并当成分支名，`flatpak build-bundle` 会回一句
`Branch can't contain /` 而失败。`${ref##*/}` 取的才是分支。

## 目录里各文件干什么

| 文件 | 作用 |
| --- | --- |
| `com.kxxnzstdsw.sundays.json` | manifest，flatpak-builder 读它 |
| `com.kxxnzstdsw.sundays.desktop` | 桌面项，导出后才有开始菜单/启动器图标 |
| `com.kxxnzstdsw.sundays.png` | 图标，由组装脚本从 `desktopApp/src/main/resources/icons/sundays_256.png` 拷来，**不入库** |
| `sundays-app/` | 组装好的应用目录（只有 jar + 资源 + 启动脚本），**不入库** |

改 `desktopApp/build.gradle.kts` 里的 `nativeDistributions.packageName` 时，
`.github/scripts/assemble-flatpak-app.sh` 的 `LAUNCHER_NAME` 与
`com.kxxnzstdsw.sundays.json` 的 `command` 要一起改。
**主类不用改**：启动器里的主类是从 jpackage 生成的 `.cfg` 的 `app.mainclass`
读出来的，脚本里没有第二份。

`manifest` 里的 `sources` 只声明一份目录与两个静态文件：应用本体是
`.github/scripts/assemble-flatpak-app.sh` 在 flatpak-builder **外面**拼好的，
flatpak-builder 在这里只负责把它装进 `/app` 并导出元数据，不参与编译。
这么切是因为编译要用 Gradle、要用宿主 JDK 与完整网络，
硬塞进 flatpak-builder 的构建沙箱里只会平白多一层故障。

## 几个选型理由

**runtime 用 26.08。**
当前稳定分支（2026-08 正式发布，支持到 2028-09），24.08 已 EOL。
此前钉在 24.08 是因为要迁就自带的那份 jlink JDK —— 它是在 CI 构建机的
glibc 上编的，runtime 太老就会在启动时报 `GLIBC_2.39 not found`。
现在 JDK 换成了 Freedesktop 官方扩展提供的 OpenJDK（针对同一版 runtime 编译），
这个约束随之消失：runtime 比构建环境新是安全的（新运行时向下兼容）。

**JDK 不自己打包，用 `org.freedesktop.Sdk.Extension.openjdk`。**
这是本文档里最反直觉的一条，值得写清楚：

> **freedesktop 的 runtime 和 sdk 本身都没有 JVM。**
> Platform 与 Sdk 只保证「桌面上常用的东西」，Java 不在其中 ——
> 几百 MB 的东西塞进每个应用都要挂载的运行时里，代价比收益大。
> 官方给的唯一途径是 SDK 扩展 `org.freedesktop.Sdk.Extension.openjdk`，
> 而且它**只在构建时可见**；要在运行时用，必须跑它自带的
> `/usr/lib/sdk/openjdk/install.sh`，由那个脚本把 JVM 复制到 `/app/jre`。

所以 manifest 里多了一个 `openjdk` 模块，内容就一行 `install.sh`；
`/app/jre` 里那份东西是 Freedesktop 自己编的 OpenJDK，不是 jpackage 现场
jlink 出来的 —— 它与 runtime 的 glibc、头文件、库版本天然对齐，
换运行时版本时不用重新裁一遍。

代价要说明白：`install.sh` 装的是完整 JDK 的 `conf/lib/release`，
所以 `/app/jre` 仍然有体积，这部分由扩展决定，不归我们管。

**用自写的 `/bin/sh` 启动器，不再用 app-image 自带的原生启动器。**
这条路曾经被证明是走不通的，所以现在能走了值得记一笔原因：

第一版想自己写个脚本 `exec runtime/bin/java`，但 Compose 给 jlink 传了
`--strip-native-commands`，打出来的运行时里**一个可执行文件都没有**
（本机实测 `desktopApp/build/compose/tmp/main/runtime/bin/` 下 67 个文件
全是 DLL、`0` 个 `.exe`）。既然要带的那份运行时里根本没有 `java`，
自己写脚本 exec 它就无从谈起，只能反过来用 app-image 自带的原生启动器，
并且为了迁就它**整棵 app-image 原样搬、不重排目录**（它靠 `$APPDIR` 的
相对关系去找 `lib/app/<同名>.cfg` 和 `lib/runtime`）。

现在运行时整个不要了，`$APPDIR` 的那套相对关系也就不再需要，
「自己写启动脚本」重新变得可行 —— 同一个想法，隔了一次方案调整就成立了。
**当时判定的「不可能」只对当时的前提成立。**

启动器长这样（由组装脚本从 `.cfg` 生成，勿手改）：

```sh
#!/bin/sh
set -eu
APPDIR='/app/share/sundays/app'
JAVA=/app/jre/bin/java
if [ ! -x "$JAVA" ]; then JAVA="$(command -v java 2>/dev/null || true)"; fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "Sundays: 找不到可用的 java（期望 /app/jre/bin/java，来自 org.freedesktop.Sdk.Extension.openjdk）" >&2
  exit 127
fi
exec "$JAVA" '-Djpackage.app-version=1.0.0' '-Dcompose.application.resources.dir=/app/share/sundays/app/resources' '-Dcompose.application.configure.swing.globals=true' '-Dskiko.library.path=/app/share/sundays/app' -cp "$APPDIR/*" 'com.kxxnzstdsw.sundays.MainKt' "$@"
```

两个细节：

- **主类与 `java-options` 一律从 jpackage 生成的 `.cfg` 读**，脚本里不写死第二份。
  两份一旦漂移（改了 `mainClass` 却忘了改脚本），症状是启动时
  `ClassNotFoundException`，要等到用户点开始菜单才显形。读不到 `.cfg` 时
  脚本直接失败，不猜。
- **classpath 用 `-cp "$APPDIR/*"`**，由 JVM 自己按目录枚举 jar。
  jpackage 把 classpath 折成了多行续行（实测 `.cfg` 共 169 行，classpath
  就占了 160 行），解析它比不解析更脆，而通配符语义完全一致。

**组装脚本用「目录里有 jar 也有 `.cfg`」定位应用本体，不看目录叫什么。**
Linux 的 app-image 是 `lib/app/`，Windows 是 `app/`；更关键的是 Windows 布局
**根本没有 `bin/`**（启动器就是根下的 `com.kxxnzstdsw.sundays.exe`）。
第一版的定位判据是「含 `bin/` 且旁边有 `app` 或 `lib/app` 的根」，
在 Linux 上成立、在 Windows 上从来没成立过 —— 而这个仓库主要在 Windows 上
开发，一条只对一半平台成立的判据意味着本地永远复现不了 CI 的路径，
也就失去了「能在本地跑」这个唯一价值。

**应用 ID 必须全小写：`com.kxxnzstdsw.sundays`。**
Flatpak 的应用 ID 只允许 `[a-z0-9_-]` 与点。首版写成 `com.kxxnzstdsw.Sundays`
（跟着 `mainClass` 的大写 S 走），`flatpak-builder` 当场拒收，**0 秒退出**。

**不装 `Platform.GL` / `Platform.Vulkan` 扩展。**
自 freedesktop 23.08 起，GL / EGL / Mesa 已经并进主运行时，那两个扩展是留给
老应用的兼容件。本应用不需要它们，装上只是平白多两个可能不存在的 ref ——
CI 上就是这一步失败的，而 `flatpak install` 缺 ref 时几秒内就退出，
匿名视角下那几秒的日志偏偏看不到。

**flatpak-builder 要在 manifest 所在目录下调用。**
`sources` 里的相对路径按「manifest 所在目录」还是按「当前工作目录」解析，
各版本说法不一 —— 从仓库根调用时，`dir` source 没被放进模块构建目录，
构建命令直接报 `cp: cannot stat 'sundays-app'`。
工作流里 `cd packaging/flatpak` 之后再调用，两种解释结果必然相同，就不必赌；
输出目录则用 `$GITHUB_WORKSPACE` 绝对路径指回仓库根。

**`elfutils` 不能从 apt 列表里省掉。**
`flatpak-builder` 1.x 剥离 debuginfo 时调的是 `eu-strip`、压缩时调
`eu-elfcompress`，两个都在 `elfutils` 包里。缺了它们不会在安装时报错，
而是等模块全部装好、走到收尾阶段才炸一句
`Failed to execute child process "eu-strip"` —— 前面十几步全绿，看着像
快成功了。所以工作流装完立刻逐个 `command -v` 点名确认。

**内容要同时写 `/app` 与 `files/`，两个地方都写。**
`builder_options_get_prefix()` 对非 runtime 构建返回 `/app`；
但历史上两种写法都被证伪过：只写模块目录下的 `files/`，收尾阶段校验 command
时看的是 `<build-dir>/files`，于是报 `Command not found`；只写 `/app` 时，
一旦某个版本上模块沙箱前缀并非 `files`，同样落空。

这个坑的表现极具误导性：探测行验的是模块目录里的 `files/`，永远报
`exists=yes`；而收尾时 `<build-dir>/files` 已被清空，于是报
`Command not found`。**验错了地方，就把「文件在」误当成「文件进了应用」。**
所以现在两处都写，探测行也同时报两处的落地情况。

**`command` 必须写成绝对路径 `/app/bin/...`，不能写 `bin/...`。**
这是 `flatpak-builder` 源码里的硬逻辑（`builder-manifest.c` 的
`builder_manifest_finish`）：

```c
if (!g_path_is_absolute (self->command))
  {
    bin_dir = g_file_resolve_relative_path (files_dir, "bin");
    command_file = g_file_get_child (bin_dir, self->command);   /* files/bin/<整串> */
  }
else if (g_str_has_prefix (self->command, "/app/"))
  command_file = g_file_resolve_relative_path (files_dir, self->command + 5);
```

**相对路径的 `command` 会被整个当作文件名挂到 `files/bin/` 下面。**
所以 `bin/com.kxxnzstdsw.sundays` 实际找的是
`files/bin/bin/com.kxxnzstdsw.sundays` —— 哪怕 `files/bin/com.kxxnzstdsw.sundays`
明明存在且可执行（build-commands 末尾那行 `CHK` 会打出 `exists=yes exec=yes`
佐证），也照样报 `Command not found`。相对写法只适合「command 就是个
可执行文件名」的老式用法，官方测试清单里就是 `"command": "hello.sh"`。

这一条在排查上很费人：文件确实在、日志里也确实出现了它，
但工具坚持说找不到。中途还先后试过嵌套一层（`/app/sundays/bin`）、
往 `/app` 额外同步、加条件守卫又撤销，绕了一大圈 ——
最后是直接把源码拉下来 grep 出来的，看源码十分钟，比猜十轮快。

**`finish-args` 里的 `--share=network` 与 `--filesystem=home` 不能省。**
前者是连 MySQL / PostgreSQL 的必要条件，后者是打开本地 SQLite / DuckDB 文件的必要条件。

## 架构自检

放在下载 2GB 运行时之前，查两样：

1. **skiko jar 的 classifier**（`linux-x64` / `linux-arm64`）——
   Gradle 解析依赖时按 `os.arch` 选出来的，板上钉钉。
   ⚠️ Linux 的 arm64 产物叫 `linux-arm64` 不是 `linux-aarch64`：
   Maven 的 osdetector 用 `aarch64`，skiko 自己发包时用 `arm64`，两套命名。
2. **skiko jar 内嵌 `.so` 的 ELF 头 `e_machine`**（偏移 `0x12`，小端 2 字节；
   `0x3E` = x86-64，`0xB7` = AArch64）—— 文件名可以是错的，字节不会。

刻意读 jar 里的字节，而不是 app-image 里落地的那个 `.so`：后者要到应用
启动时才被 skiko 解出来，构建产物里未必有（Linux 上有没有全看 jpackage
的心情，不能当契约）。用 `python3` 解 zip 而不是 `readelf`，
因为 `readelf` 属于 binutils，为了读两个字节多装一个包不值得。

## 和 Windows 腿的差异

Windows 那条腿在 ARM 上需要把 `protoc` 退回 x86_64（上游没发 `windows-aarch_64`，
详见 `engine-protocol/build.gradle.kts` 的注释）。**Linux 这边不需要**：
protoc 是有 `linux-aarch_64` 产物的，osdetector 会自己挑对。

## 排障

CI 失败时看 run 的 annotation —— `/check-runs/{id}/annotations` 公开可读，
不受「GitHub 网页把大步骤日志截断」的限制，也不需要仓库 admin 权限。

**但这条通道每个步骤只保留前 10 条。**
workflow command 发出的 annotation，超出 10 条的部分直接丢弃 —— 不是截断显示，
是真的没有。所以工作流只 dump 日志的**末尾 9 行**并且先发：曾经整份日志逐行
发出去，真正那句错误排在第 13、14 行，结果一条都没留下来，屏幕上只剩满屏
开头的 banner。

组装脚本的失败诊断同理压在 8 条以内：Gradle 结论行、compose 子目录
（压平成一行的目录树）、产物文件数、jar 目录与 cfg 候选各若干。
**能打多少打多少是错的，要打最有用的那几条。**

同理，**别为「本来就可以没有」的东西写硬断言**。组装脚本一度要求
`resources/` 目录必须存在，可本机实测它在 Windows 产物里就是个空目录，
Linux 上会不会干脆没有也没验证过 —— 为这种可有可无的东西卡住整条流水线，
是这个仓库吃过好几次的亏。现在那里只打印一行提示。
