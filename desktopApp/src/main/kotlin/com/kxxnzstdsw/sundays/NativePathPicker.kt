package com.kxxnzstdsw.sundays

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 「输出到哪里」的路径选择器。
 *
 * ## 为什么需要它
 *
 * 导出对话框原先只有两个自由文本框，让用户自己敲绝对路径。抄一段
 * `C:\Users\xxx\...` 出来既慢又容易打错，而打错的后果是「文件不知道去哪了」。
 * 引擎侧 `ExportRunRequest.output_dir` 收的确实是**绝对路径**、引擎自己没得挑 ——
 * 这个交互欠用户的，只能由桌面应用补上。所以两条路都给：
 *
 * - **选择器**（本类）：点一下就能挑
 * - **文本框**：网络路径 / UNC / 刚复制来的路径照粘不误，
 *   无头与 CI 环境下压根弹不出对话框时，这是唯一走得通的路
 *
 * 把文本框拿掉等于把后两种场景整个堵死。
 *
 * ## 为什么用 [JFileChooser] 而不是 `java.awt.FileDialog`（系统那套）
 *
 * 一度用的是 `FileDialog`——它在 Windows 上映射到资源管理器的原生对话框，观感确实更像
 * 「系统的」。但**实测它拿不到用户选的真实路径**：
 *
 * ```
 * 用户在原生对话框里进到 build\ 文件夹、文件名保持 export.csv、点确定
 *   → FileDialog.getFile() == "export.csv"     ← 只有文件名，没有目录
 *   → FileDialog.getDirectory()               ← 仍是打开时的那个目录，导航后不回填
 * ```
 *
 * 于是「我明明在 build 里选的」被解析成了进程工作目录下的 `export.csv`，
 * 输出目录框**纹丝不动**。写错路径比没有选择器更糟——用户会以为文件在 build 里，
 * 实际上根本不在。所以选**返回值可靠**的那个：`JFileChooser.getSelectedFile()`
 * 给的是完整绝对路径。
 *
 * ## 线程
 *
 * 两个入口都**阻塞**，所以必须离开 Compose 的 UI 线程；
 * 用 `Dispatchers.IO` + `invokeAndWait` 交给 AWT 自己的事件泵，
 * 既不卡界面，也不会在 Skiko 的渲染线程上开第二个事件循环。
 */
object NativePathPicker {

    /** 选出来的结果：`dir` 一定是绝对路径；`name` 可能为空（只选了目录）。 */
    data class Picked(val dir: String, val name: String)

    /**
     * 选一个**完整的输出文件**（目录 + 文件名一起）。
     *
     * @param initialName 起始文件名 —— 预填进去，用户多半只需要改扩展名
     * @return 用户取消返回 `null`
     */
    suspend fun pickFile(initialDir: String, initialName: String): Picked? =
        withContext(Dispatchers.IO) {
            val dir = File(initialDir.takeIf { it.isNotBlank() } ?: userHome())
            if (dir.isDirectory) dir.mkdirs()
            val chosen = choose(
                start = dir,
                fileName = initialName,
                title = "选择导出文件",
                approveText = "保存",
                saveMode = true,
            ) { it.fileSelectionMode = JFileChooser.FILES_ONLY } ?: return@withContext null
            val f = File(chosen)
            if (f.isDirectory) {
                // 用户挑中了文件夹：只定目录，文件名沿用当前值
                Picked(dir = f.absolutePath, name = initialName)
            } else {
                Picked(dir = absoluteDir(f), name = f.name)
            }
        }

    /** 只选一个**目录**。 */
    suspend fun pickDirectory(initialDir: String, initialName: String = ""): Picked? =
        withContext(Dispatchers.IO) {
            val start = File(initialDir.takeIf { it.isNotBlank() } ?: userHome())
            if (start.isDirectory) start.mkdirs()
            val chosen = choose(
                start = start,
                fileName = initialName,
                title = "选择输出目录",
                approveText = "选择",
                saveMode = false,
            ) { it.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY } ?: return@withContext null
            Picked(dir = File(chosen).absolutePath, name = initialName)
        }

    /**
     * 弹一次选择器，返回**绝对路径**或 `null`（取消 / 无头不可用）。
     *
     * `configure` 在弹之前拿到 chooser，用于切文件/目录模式。
     */
    private fun choose(
        start: File,
        fileName: String,
        title: String,
        approveText: String,
        saveMode: Boolean,
        configure: (JFileChooser) -> Unit,
    ): String? = try {
        val out = arrayOfNulls<File>(1)
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser(start).apply {
                dialogTitle = title
                approveButtonText = approveText
                isAcceptAllFileFilterUsed = false
                configure(this)
                // 只在「保存」模式下预填文件名：目录模式下预填一个文件名
                // 会让用户以为自己已经选好了文件
                if (fileName.isNotBlank() && saveMode) {
                    selectedFile = File(start, fileName)
                }
            }
            if (chooser.showDialog(null, approveText) == JFileChooser.APPROVE_OPTION) {
                out[0] = chooser.selectedFile
            }
        }
        out[0]?.absolutePath
    } catch (t: Throwable) {
        // 无头环境（CI / headless JVM）或 AWT 起不来：安静地返回 null，
        // 调用方保留「手动输入路径」那条路 —— 这只是辅助功能，不该让应用崩
        null
    }

    private fun absoluteDir(f: File): String =
        (if (f.isDirectory) f else f.parentFile ?: f).absoluteFile.absolutePath

    private fun userHome(): String = System.getProperty("user.home") ?: "."
}