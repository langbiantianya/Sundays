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
  packaging/flatpak/com.kxxnzstdsw.Sundays.json
flatpak build-bundle flatpak-repo sundays.flatpak com.kxxnzstdsw.Sundays stable
```

## 目录里各文件干什么

| 文件 | 作用 |
| --- | --- |
| `com.kxxnzstdsw.Sundays.json` | manifest，flatpak-builder 读它 |
| `com.kxxnzstdsw.Sundays.desktop` | 桌面项，导出后才有开始菜单/启动器图标 |
| `com.kxxnzstdsw.Sundays.png` | 图标，由组装脚本从 `desktopApp/src/main/resources/icons/sundays_256.png` 拷来，**不入库** |
| `sundays-app/` | 组装好的应用目录（app-image 原样搬过来），**不入库** |

改 `desktopApp/build.gradle.kts` 里的 `nativeDistributions.packageName` 时，
`.github/scripts/assemble-flatpak-app.sh` 的 `LAUNCHER_NAME` 与
`com.kxxnzstdsw.Sundays.json` 的 `command` 要一起改 —— 启动器文件名与
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

**`finish-args` 里的 `--share=network` 与 `--filesystem=home` 不能省。**
前者是连 MySQL / PostgreSQL 的必要条件，后者是打开本地 SQLite / DuckDB 文件的必要条件。

## 和 Windows 腿的差异

Windows 那条腿在 ARM 上需要把 `protoc` 退回 x86_64（上游没发 `windows-aarch_64`，
详见 `engine-protocol/build.gradle.kts` 的注释）。**Linux 这边不需要**：
protoc 是有 `linux-aarch_64` 产物的，osdetector 会自己挑对。

## 排障

CI 失败时先看 run 页面的 annotation —— 工作流把 Gradle 的 `* What went wrong *`
段转成了 annotation，`/check-runs/{id}/annotations` 公开可读，
不受「GitHub 网页把大步骤日志截断」的限制，也不需要仓库 admin 权限。
