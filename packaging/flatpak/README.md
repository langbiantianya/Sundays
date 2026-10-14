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
flatpak build-bundle flatpak-repo sundays.flatpak com.kxxnzstdsw.sundays stable
```

## 目录里各文件干什么

| 文件 | 作用 |
| --- | --- |
| `com.kxxnzstdsw.sundays.json` | manifest，flatpak-builder 读它 |
| `com.kxxnzstdsw.sundays.desktop` | 桌面项，导出后才有开始菜单/启动器图标 |
| `com.kxxnzstdsw.sundays.png` | 图标，由组装脚本从 `desktopApp/src/main/resources/icons/sundays_256.png` 拷来，**不入库** |
| `sundays-app/` | 组装好的应用目录（app-image 原样搬过来），**不入库** |

改 `desktopApp/build.gradle.kts` 里的 `nativeDistributions.packageName` 时，
`.github/scripts/assemble-flatpak-app.sh` 的 `LAUNCHER_NAME` 与
`com.kxxnzstdsw.sundays.json` 的 `command` 要一起改 —— 启动器文件名与
`app/<同名>.cfg` 是配套的。脚本会显式断言启动器存在，对不上直接报错。

`manifest` 里的 `sources` 只声明一份目录与两个静态文件：应用本体是
`.github/scripts/assemble-flatpak-app.sh` 在 flatpak-builder **外面**拼好的，
flatpak-builder 在这里只负责把它装进 `/app` 并导出元数据，不参与编译。
这么切是因为编译要用 Gradle、要用宿主 JDK 与完整网络，
硬塞进 flatpak-builder 的构建沙箱里只会平白多一层故障。

## 几个选型理由

**runtime 用 24.08 而不是 Flathub 当前的 26.08。**
freedesktop 24.08 基于 Ubuntu 24.04，glibc 是 2.39；CI 的构建机也是
ubuntu-24.04 / ubuntu-24.04-arm，glibc 同样是 2.39。skiko 的原生 `.so` 与
jlink 出来的 JDK 原生库都是在 2.39 上编的 —— 换成更老的 runtime（23.08 是
glibc 2.38）就会在**启动时**报 `GLIBC_2.39 not found`，而且那种错只在装好
Flatpak 之后才显形，排查成本很高。runtime 比构建环境新则没问题（新运行时向下兼容）。

**用 Compose 自带的原生启动器，不要自己写启动脚本。**
这条和最初的设想正好相反，值得记下来：Compose 给 jlink 传了
`--strip-native-commands`，打出来的运行时里**一个可执行文件都没有**
（本机实测 `desktopApp/build/compose/tmp/main/runtime/bin/` 下 67 个文件全是
Windows DLL，`0` 个 `.exe`）。所以「自己写个脚本 `exec runtime/bin/java`」
这条路在原理上就走不通 —— 那个 `java` 根本不存在。
app-image 自带的原生启动器（Linux 在 `bin/com.kxxnzstdsw.sundays`）是唯一入口。

也正因为如此，组装脚本**不重排目录，整棵 app-image 原样拷进 `sundays-app/`**：
原生启动器靠 `$APPDIR` 的相对关系去找 `lib/app/<同名>.cfg` 和 `lib/runtime`，
一挪动位置就只会在**启动时**才炸，而不是在构建时。

**应用 ID 必须全小写：`com.kxxnzstdsw.sundays`。**
Flatpak 的应用 ID 只允许 `[a-z0-9_-]` 与点。首版写成 `com.kxxnzstdsw.Sundays`
（跟着 `mainClass` 的大写 S 走），`flatpak-builder` 当场拒收，**0 秒退出**。
这也正好和 jpackage 的命名一致 —— app-image 目录本来就叫
`com.kxxnzstdsw.sundays`。

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

**`command` 必须写成绝对路径 `/app/bin/...`，不能写 `bin/...`。**
这条是 `flatpak-builder` 源码里的硬逻辑（`builder-manifest.c` 的
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
佐证），也照样报 `Command 'bin/com.kxxnzstdsw.sundays' not found`。
相对写法只适合「command 就是个可执行文件名」的老式用法，
官方测试清单里就是 `"command": "hello.sh"`。

这一条在排查上很费人：文件确实在、日志里也确实出现了它，
但工具坚持说找不到。中途还先后试过嵌套一层（`/app/sundays/bin`）、
往 `/app` 额外同步、加条件守卫又撤销，绕了一大圈 ——
最后是直接把源码拉下来 grep 出来的，看源码十分钟，比猜十轮快。

**应用装在 `/app` 根，`command` 指向 `bin/...`。**

**内容写进 `files/`，`buildsystem` 用 `simple`。**

**`build-commands` 装进 `files/` 而不是 `/app/`。**
`buildsystem` 为 `simple` 时 CWD 就是模块构建目录，`files/` 就是最终 `/app`
的内容，写它不必依赖 `/app` 那个符号链接。拷贝应用那一步写成
`if [ -d sundays-app ] ... else ...`：不同 flatpak-builder 版本上 `dir` 类型的
落点不同（有的是 `sundays-app/` 子目录，有的把内容摊到模块根），两种都收。
第一条 `echo MODULE_DIR: $(ls -A ...)` 把模块目录内容压成一行打进构建日志，
等于自带现场取证。

**`finish-args` 里的 `--share=network` 与 `--filesystem=home` 不能省。**
前者是连 MySQL / PostgreSQL 的必要条件，后者是打开本地 SQLite / DuckDB 文件的必要条件。

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
（压平成一行的目录树）、产物文件数、JVM 库与启动器候选各若干。
**能打多少打多少是错的，要打最有用的那几条。**
