# Flatpak 打包

Sundays 的 Flatpak 定义。CI 见 [`.github/workflows/build-flatpak.yml`](../../.github/workflows/build-flatpak.yml)，
它同时产出 `amd64` 与 `arm64` 两个架构，各跑在**原生 runner** 上（`ubuntu-latest` 与
`ubuntu-24.04-arm`）—— 不交叉编译、不走 QEMU 模拟。

```bash
# 本地只跑组装这一步（前面的 app-image 得先打出来）
./gradlew :desktopApp:createDistributable
bash .github/scripts/assemble-flatpak-app.sh

# 再往下需要 flatpak-builder 与 freedesktop 运行时
flatpak install flathub org.freedesktop.Platform//26.08 org.freedesktop.Sdk//26.08 \
  org.freedesktop.Sdk.Extension.openjdk//26.08
flatpak-builder --force-clean --repo=flatpak-repo flatpak-build \
  packaging/flatpak/com.langbiantianya.sundays.json
flatpak build-bundle flatpak-repo sundays.flatpak com.langbiantianya.sundays master
```

bundle 里含的是本应用；freedesktop 运行时与 JDK 扩展来自 Flathub，安装时按 metadata
里的声明去取，所以换机器装它仍需要能访问 Flathub。

## 目录里各文件干什么

| 文件 | 作用 |
| --- | --- |
| `com.langbiantianya.sundays.json` | manifest，flatpak-builder 读它 |
| `com.langbiantianya.sundays.desktop` | 桌面项，导出后才有开始菜单/启动器图标 |
| `com.langbiantianya.sundays.png` | 图标，由组装脚本从 `desktopApp/src/main/resources/icons/sundays_256.png` 拷来，**不入库** |
| `sundays-app/` | 组装好的应用目录（只有 jar + 资源 + 启动脚本），**不入库** |

改 `desktopApp/build.gradle.kts` 里的 `nativeDistributions.packageName` 时，
[组装脚本](../../.github/scripts/assemble-flatpak-app.sh) 的 `APP_ID` / `LAUNCHER_NAME`
与 manifest 的 `command` 要一起改。**主类不用改** —— 启动器的主类是从 jpackage 生成的
`.cfg` 读出来的，脚本里没有第二份。

## 关键选型（结论索引）

推导过程写在代码注释里：**组装逻辑看
[`assemble-flatpak-app.sh`](../../.github/scripts/assemble-flatpak-app.sh) 的段首注释，
CI 侧的校验与预检看 [`build-flatpak.yml`](../../.github/workflows/build-flatpak.yml) 的
`FREEDESKTOP_RUNTIME` 段**。下面是结论本身，改动前先看代码里那两处。

| 结论 | 为什么 |
| --- | --- |
| runtime 用 `org.freedesktop.Platform` **26.08**，不用 `org.gnome.Platform` | GNOME 51 本身就是架在 freedesktop 26.08 上的一层扩展，本应用一个 GNOME 库都用不上；且启动器要绕的空指针跟运行时是哪一家无关，换回去不会自己好 |
| `sdk-extensions` **只能写裸名** | `build-init` 的 `ensure_extensions()` 只剥 `@tag`，不解析 `//分支`。写上分支会被当成名字的一部分，再拼上 `<arch>/<SDK 当前分支>`，报 `not installed` |
| JDK 来自 `org.freedesktop.Sdk.Extension.openjdk`，**不自带 jlink 运行时** | runtime 与 sdk 本身都没有 JVM；扩展只在构建时可见，靠它自带的 `install.sh` 把 JVM 落到 `/app/jre`。与 runtime 的 glibc 天然对齐 |
| `sdk-extensions` 声明了**不等于装了**，CI 必须显式 `flatpak install` | manifest 只声明「构建时挂进沙箱」，装不装是另一件事。装完逐个核对三个 ref |
| 启动器必须追加 `-Dswing.systemlaf=…MetalLookAndFeel` | Flatpak 沙箱里没有 `gnome-shell` 二进制，`GTKLookAndFeel.initialize()` 拆箱 null 当场炸。**属性名是 `swing.systemlaf`**，不是 `swing.defaultlaf` |
| `Locale` / `GL.default` 不写进 manifest，也不单独装 | 它们是**运行时扩展**，随运行时一起拉取。应用侧没有可写的键；GNOME 那轮的预检已撤（那些 ref 在 freedesktop 上不存在） |
| `--share=network` 与 `--filesystem=home` 不能省 | 前者连 MySQL / PostgreSQL，后者打开本地 SQLite / DuckDB |

> ⚠️ **CI 能验的只有「包打出来、metadata 指向 freedesktop 26.08」** —— 启动那一步
> 需要 Linux 机器，本仓库的打包机是 Windows，NPE 修法尚未在真机上复验过。

## 架构自检与排障

工作流在上传**之前**自检产物架构：对一遍 skiko jar 的 classifier（`linux-x64` /
`linux-arm64`），再直接读 app 目录里 skiko 原生库的 ELF 头 `e_machine`
（`0x3E` = x86-64，`0xB7` = AArch64）。不对就不上传。

CI 失败时看 annotation 而不是大步骤日志 —— GitHub 网页版会把长日志整个截断。
注意每步每级只保留**前 10 条**、超 90 字符的整条丢弃，所以诊断信息要短、要排在前面。

排障与自检的完整逻辑见
[`build-flatpak.yml`](../../.github/workflows/build-flatpak.yml) 的步骤注释。
