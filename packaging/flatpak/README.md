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
| `sundays-app/` | 组装好的应用目录，**不入库** |

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

**启动脚本自己写，不复用 Compose 的原生启动器。**
Compose 那个启动器靠相对路径回溯去找 `runtime/`，在 Flatpak 沙箱里行为不易预期。
自己 `exec` 到 jlink 出来的 `java`，出错时日志直接指向 java，好排查得多。

**`-cp "$dir/*"` 整串要带引号。**
shell 若先做通配展开，会展开成一串空格分隔的路径，`java` 只把第一个当 classpath，
剩下的全被当成主类和参数，报出来的是莫名其妙的错。带引号则由 Java 自己展开。

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
