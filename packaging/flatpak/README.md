# Flatpak 打包

Sundays 的 Flatpak 定义。CI 见 [`.github/workflows/build-flatpak.yml`](../../.github/workflows/build-flatpak.yml)，
它同时产出 `amd64` 与 `arm64` 两个架构，各跑在**原生 runner** 上
（`ubuntu-latest` 与 `ubuntu-24.04-arm`）—— 不交叉编译、不走 QEMU 模拟。

```bash
# 本地只跑组装这一步（前面的 app-image 得先打出来）
./gradlew :desktopApp:createDistributable
bash .github/scripts/assemble-flatpak-app.sh

# 再往下需要 flatpak-builder 与 GNOME 运行时
flatpak install flathub org.gnome.Platform//51 org.gnome.Sdk//51 \
  org.freedesktop.Sdk.Extension.openjdk//26.08
flatpak-builder --force-clean --repo=flatpak-repo flatpak-build \
  packaging/flatpak/com.kxxnzstdsw.sundays.json
flatpak build-bundle flatpak-repo sundays.flatpak com.kxxnzstdsw.sundays master
```

bundle 里含的是本应用（外加同 repo 里的 debug runtime）；
`org.gnome.Platform`（连带底下的 freedesktop 基线）与 JDK 扩展来自 Flathub，
安装时按 metadata 里的声明去取，所以换机器装它仍需要能访问 Flathub。

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

**runtime 用 GNOME 51，底下是 freedesktop 26.08。**
`org.gnome.Platform//51` 不是独立的一套运行时，它本身就是架在
`org.freedesktop.Platform//26.08` 上的一层扩展 —— 证据是实测
`flatpak install flathub org.gnome.Platform//51` 连带拉下来的东西：

```
org.freedesktop.Platform.GL.default      26.08
org.freedesktop.Platform.VAAPI.Intel      26.08
org.freedesktop.Platform.codecs-extra    26.08-extra
```

分支号就是底下那层基线的版本号。**记这个是因为后面那个 JDK 扩展要靠它**。
（另：24.08 那时候钉着它，是因为要迁就自带 jlink JDK 的 glibc；
换成官方扩展提供的 OpenJDK 之后这个约束就没了 —— 新运行时向下兼容，
不需要为了迁就构建环境而把 runtime 压老。）

**`sdk-extensions` 里只能写裸名，写上 `//分支` 必然失败。**
这一条是本仓库真踩过的坑，而且错法只有一种，值得把机制写清楚：

```json
"sdk-extensions": [ "org.freedesktop.Sdk.Extension.openjdk" ]
```

写成交 `org.freedesktop.Sdk.Extension.openjdk//26.08` 会得到一句看起来像
路径拼接错了的报错：

```
error: Requested extension org.freedesktop.Sdk.Extension.openjdk//26.08/x86_64/51 not installed
```

注意末尾那个 `/x86_64/51` —— 它把 `//26.08` 当成了名字的一部分，
又拼上了 SDK 的 arch 与**分支 51**（不是基线 26.08）。机制在 flatpak
`build-init` 的 `ensure_extensions()` 里（`app/flatpak-builtins-build-init.c`）：

```c
extensions = flatpak_list_extensions (metakey, default_arch, default_branch);
...
/* Remove any '@' from the name */
flatpak_parse_extension_with_tag (requested_extension, &requested_extension_name, NULL);
...
if (strcmp (ext->id, requested_extension_name) == 0) { ... }
...
return flatpak_fail (error, _("Requested extension %s/%s/%s not installed"),
                     requested_extension_name, default_arch, default_branch);
```

三件事同时成立：

1. `flatpak_parse_extension_with_tag()` **只剥 `@tag`，不解析 `//分支`**
   （flatpak main 上这段代码至今一字未改，所以任何版本都一样）；
2. 候选扩展来自 **SDK 自己 metadata 里声明的扩展点** ——
   `org.gnome.Sdk//51` 确实声明了 `org.freedesktop.Sdk.Extension.openjdk`，
   所以**裸名能对上**（Java-GI 官方模板 `jwharm/java-gi-app-template`
   跑在 GNOME 49 上用的就是这个写法）；
3. 报错消息把整串再拼上 `<arch>/<SDK 的分支>`，于是成了那句话。

**扩展装在哪条分支上跟 manifest 无关。** 那是 `flatpak install` 时的事：
GNOME 51 的基线是 26.08，所以 CI 里装 `//26.08` 那个 ref，装完立刻点名核对
（见下面「装完必须核对」）。manifest 只负责说「我要这个扩展点」。

**改 runtime 时，`runtime-version` 与「装哪个扩展 ref」是两个独立改动点。**
只改前一个是自然的手滑，但**不会**像想象中那样立刻报错：裸名照样能对上，
真正出事的是装错分支。工作流里把两件事分开守着 —— manifest 的四个字段逐个与
env 比对（其中 sdk-extensions 单独判「有没有多写 `//`」），安装完再把三个 ref
逐个点名核对。

这条校验本地实跑时还抓到一个自己写的 bug：取值用的 `sed ... | head -n1`
取的是**第一个**匹配，而 manifest 开头那段 `"//"` 长注释里同样写着扩展名，
于是取出来的东西带上了半句注释。**注释写给谁看不影响它也会被 grep 到。**
现在取值前先把 `"//":` 那一行滤掉，并且用真实 manifest 与一份改坏的 manifest
各跑一遍 —— 只跑正向用例的话，这个 bug 会一路活到 CI 上。

**GNOME 运行时里启动直接抛 NPE —— 必须给 `-Dswing.systemlaf`。**
换到 GNOME 51 之后第一次启动是这样的：

```
Exception in thread "main" java.lang.NullPointerException:
  Cannot invoke "java.lang.Integer.intValue()"
  at java.desktop/com.sun.java.swing.plaf.gtk.GTKLookAndFeel.initialize(GTKLookAndFeel.java:1473)
  at java.desktop/javax.swing.UIManager.setLookAndFeel(UIManager.java:611)
  at org.jetbrains.skiko.Actuals_awtKt.setSystemLookAndFeel(Actuals.awt.kt:6)
```

**这是 JDK 自己的空指针，不是我们的代码。** `GTKLookAndFeel.initialize()` 里就两行：

```java
int gnomeShellVersion = unixToolkit.getGnomeShellMajorVersion();
shouldApplyTheme = gnomeShellVersion >= 47;
```

`getGnomeShellMajorVersion()` 返回的是 `Integer`，要靠执行 `gnome-shell --version`
才有值。Flatpak 沙箱里没有 `gnome-shell` 这个二进制（它属于 GNOME 会话，不属于
运行时），于是返回 null，自动拆箱当场炸。**没有「保持 GTK 但不炸」的配置项。**

修法是改用 Metal 外观，而属性名有个坑：

> **是 `-Dswing.systemlaf`，不是 `-Dswing.defaultlaf`。**

skiko 选外观就一行（`skiko/src/awtMain/kotlin/org/jetbrains/skiko/Actuals.awt.kt`）：

```kotlin
actual fun setSystemLookAndFeel() =
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
```

而 `UIManager.getSystemLookAndFeelClassName()` 读的系统属性是 **`swing.systemlaf`** ——
先读它，没有才按操作系统猜（Windows → `WindowsLookAndFeel`，
Linux + GNOME → `GTKLookAndFeel`，macOS → `Aqua`）。
另外 `UIManager.setLookAndFeel(String)`（javax/swing/UIManager.java:646）只按传进来的
类名实例化，压根不读 `swing.defaultlaf` —— 写上去会让人以为修好了，实际一点用没有。

所以组装脚本在读来的那批 java-options 之后**追加**一条：

```
-Dswing.systemlaf=javax.swing.plaf.metal.MetalLookAndFeel
```

放在组装脚本而不是 `build.gradle.kts`：jpackage 的 `--java-options` 是全局的，
写进构建脚本会连 Windows 与 macOS 一起改掉，而那两边本来就该用原生外观
（`WindowsLookAndFeel` / `Aqua`），改成 Metal 是纯倒退。
这是**追加**而不是第二份拷贝 —— `.cfg` 里的主类与 java-options 仍然只有那一份。

顺带一提：Flatpak 里那两条 `Failed to load module "canberra-gtk-module"` /
`"pk-gtk-module"` 是 GTK 模块在找宿主上的桌面服务，找不到就打个 Gtk-Message。
用了 Metal 之后 GTK 不再初始化，这两条也会一起消失。

**`org.gnome.Platform.Locale` 与 `org.freedesktop.Platform.GL.default` 不写进 manifest。**
这两条最容易被顺手塞进 `sdk-extensions`，但那会让构建直接失败。三条独立证据：

1. flatpak-builder 的 manifest 只有 `sdk-extensions` 与 `platform-extensions`
   两个扩展相关的键，源码里 `builder_manifest_init_app_dir()` 把**两者**翻译成
   同一句 `flatpak build-init --sdk-extension=`，语义都是「把这个扩展挂进
   **构建沙箱的** `/usr`」。没有第三个键。
2. flatpak 的 metadata 里**根本没有**应用级的「我依赖这些运行时扩展」键。
   `runtime-extensions` 只存在于 `[Instance]` 组，而那是 flatpak 部署时写给
   运行实例看的，不是应用能声明的输入（`common/flatpak-metadata-private.h`
   里能看到的相关键只有 `app-extensions` 与 `runtime-extensions`，都属于 `[Instance]`）。
3. 运行时扩展本来就随运行时一起被拉取与挂载。上面那条实测里，
   `flatpak install org.gnome.Platform//51` 已经把 GL.default 26.08 带下来了 ——
   应用侧不需要再声明一次。

所以 CI 只**查**这两个 ref 存不存在，不装。查是为了让
「这个基线上 GNOME 侧没发布某某扩展」变成一句几秒内可见的报错，
而不是等用户装完、开始菜单点不动才发现少了东西。

**JDK 不自己打包，用 `org.freedesktop.Sdk.Extension.openjdk`。**
这是本文档里最反直觉的一条，值得写清楚：

> **runtime 和 sdk 本身都没有 JVM。**
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

**`sdk-extensions` 声明了不等于装了 —— 必须显式 `flatpak install`。**
manifest 里写 `"sdk-extensions": ["org.freedesktop.Sdk.Extension.openjdk"]`
只是在告诉 flatpak-builder「构建时把这个扩展挂进沙箱的 `/usr`」，它**不会替你装**。
flatpak-builder 1.4.2 实测直接回一句就退出：

```
error: Requested extension org.freedesktop.Sdk.Extension.openjdk/x86_64/26.08 not installed
```

所以工作流在「安装 GNOME 运行时与 SDK」那一步把三个 ref 一起装：
`org.gnome.Platform//51`、`org.gnome.Sdk//51`、`openjdk//26.08`。
底下那层 `org.freedesktop.Platform` / `Sdk` **不用单独装** ——
`org.gnome.Platform` 本身就是架在它们上面的一层扩展，装它会把基线一并带下来。

**装完必须点名核对「装上了」，不能只信退出码。**
两个理由，都不是假设：

- **没写 `shell:` 的步骤，GitHub 用的默认 shell 是 `bash -e {0}`，没有 pipefail。**
  `flatpak install … | tee runtime-install.log` 里前者挂了，退出码会被 `tee` 吃掉，
  步骤照样判绿。而后面 flatpak-builder 只会说 `not installed` ——
  把「装失败」说成「没装」，排查方向正好相反。
  要 pipefail 就得显式 `set -o pipefail`（或者给步骤写 `shell: bash`，
  它的包装器是 `bash --noprofile --norc -eo pipefail {0}`）。
- **`flatpak list --runtime --columns=ref` 的输出是可以逐字比对的**，
  比任何「大概装上了」都硬。所以安装之后立刻核对三个 ref，
  少一个就在这里报，而不是拖到 flatpak-builder 才炸。
- **`--runtime` 这个 flag 不能少，它不是可选的修饰。**
  `flatpak list` 默认**只列应用**；要列运行时必须显式加 `--runtime`。
  少了它，这三个 ref（Platform、Sdk、SDK 扩展，全是 runtime）一个都不会出现，
  三条断言于是**必然全判失败**，并且报出来的是「没装上」——
  而事实上装得好好的。报错内容与事实完全相反时，最容易让人往错的方向查很久。
  这跟上一条是同一个失败模式：**断言自己搞错了，于是把绿灯变成假警报。**

  比对时把 `runtime/` 前缀去掉再比（`short="${r#runtime/}"` + `grep -qF`）：
  那一列带不带前缀会随 flatpak 版本变，带不带都不该让断言翻脸。
  失败时再打前三条与总行数，让人一眼看出「列表是空的」还是「少了一个」。

远端 ref 的预检从三个增加到五个，多出来的两个正是上面那两个只查不装的
运行时扩展（`org.gnome.Platform.Locale//51`、`GL.default//26.08`）。

顺便记一个**假阴性**，它比缺 ref 更危险：检查远端 ref 的那一步原本排在
`flatpak remote-add` **之前**，于是查询时 remote 还不存在，
三个 ref 全报「远端没有」，还带着 `::error::` 级别 —— 看起来像铁证，
实际是查了个空。（同一轮里下一步 `flatpak install` 明明成功装上了 26.08 的
Platform 与 Sdk，两条信息直接矛盾，只是当时没往一处想。）
**诊断步骤的输出必须和它自己的前置条件对照着看；断言放在被断言的东西之前，
等于什么都没断言。** 现在 `remote-add` 提前到那一步里，`--if-not-exists` 幂等。

**构建完要验导出 metadata 里的 runtime 声明。**
「换一个运行时」是一类特别容易被自己骗过去的改动：构建全绿、bundle 也导出来了，
看上去哪儿都没问题，但如果 metadata 里 `runtime=` 仍旧是上一代，
装到用户机器上的就还是旧运行时，而本地和 CI 都永远看不出来。
所以「构建 Flatpak」那一步读完 `flatpak-build/metadata`，逐字比对
`runtime=org.gnome.Platform/<cpu>/51` 与 `sdk=org.gnome.Sdk/<cpu>/51`。
`<cpu>` 是 Flatpak 自己的叫法（`x86_64` / `aarch64`，不是矩阵里的
`amd64` / `arm64`），单列了一项 `matrix.flatpakCpu`，不拿 `matrix.flatpakArch`
顶替 —— 后者直接拼进 ref 会得到一个不存在的架构名，而这种错误一路要到
用户装的时候才现形。这段断言本地拿假的 metadata 喂了四个场景：
两个架构各自正确、runtime 停在旧版本、metadata 文件不存在。

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

**不装 `Platform.GL` / `Platform.Vulkan` / `GL.default` 扩展。**
自 freedesktop 23.08 起，GL / EGL / Mesa 已经并进主运行时，这几个是留给
老应用的兼容件。本应用不需要它们，装上只是平白多几个可能不存在的 ref ——
CI 上就是这一步失败的，而 `flatpak install` 缺 ref 时几秒内就退出，
匿名视角下那几秒的日志偏偏看不到。`GL.default` 虽然由运行时自动带下来，
也不必在 manifest 里点名，理由见上面「只查不装」那一节。

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
2. **并排那个原生库的 ELF 头 `e_machine`**（偏移 `0x12`，小端 2 字节；
   `0x3E` = x86-64，`0xB7` = AArch64）—— 文件名可以是错的，字节不会。

第 2 条查的**不是 jar 里的条目，而是 app 目录下一个普通文件**。
这一点第一版想当然搞错了：以为 `skiko-awt-runtime-*.jar` 里内嵌 `.so`，
结果两条腿一起报 `NO_SO`。真相是 Compose 的 Gradle 插件会**合成**一个
`skiko-awt-runtime-<平台>-<版本>.jar` —— 那个 jar 只有 2 个条目
（本机实测 Windows 产物：293 字节、2 条，纯空壳）—— 真正的库是它从 skiko
发行物里解出来、并排放进 app 目录的 `skiko-<平台>.dll` /
`libskiko-linux-<平台>.so`，旁边还有 `libicudtl.dat`：

```
app/
├── skiko-awt-runtime-linux-x64-0.144.6.jar   ← 合成的空壳，只有名字有意义
├── libskiko-linux-x64.so                     ← 真正的原生库
└── libicudtl.dat
```

所以 classifier 检查的是文件名（证明 Gradle 选对了平台），
架构检查读的是那个并排文件的字节。用 `python3` 读前 20 字节而不是 `readelf`：
`readelf` 属于 binutils，为了读两个字节多装一个包不值得。

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
