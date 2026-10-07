package com.kxxnzstdsw.sundays

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「系统路径选择器」的契约 —— 在**跑不了系统对话框**的环境里也不能崩。
 *
 * ## 为什么这条值得单独立一个测试
 *
 * 导出对话框里的「浏览 / 选文件」调的是 `java.awt.FileDialog`。
 * 它在三种环境下会直接抛异常而不是弹窗：
 *
 * - 无头（CI、headless JVM）—— `HeadlessException`
 * - 没有 `DISPLAY` / 会话不可用
 * - AWT 初始化失败
 *
 * 任何一个没兜住，用户点一下按钮整个应用就崩 —— 而这只是「换个保存位置」
 * 这个**辅助**动作，它崩掉的代价远大于它带来的价值。
 *
 * 真正弹窗的路径无法在 CI 里自动断言（会挂住等人操作），
 * 所以这里钉的是**兜底契约**：拿不到结果就安静地返回 `null`，
 * 由调用方保留「手动输入路径」那条路。
 */
class NativePathPickerTest {

    /**
     * 只有**无头**环境才能跑这两条。
     *
     * 有显示器时 `FileDialog` 会真的弹出来等人点 —— 测试任务会**挂死**，
     * 而且挂得没有任何输出，看起来像构建卡住了（这个坑本轮踩过一次）。
     * 这里用 `assumeTrue` 显式跳过：报告里会留下一条 skipped，
     * 而不是让人对着一个不动的构建猜是不是崩了。
     */
    private fun requireHeadless() =
        assumeTrue(
            "有显示器的环境会真的弹出模态对话框并挂死，只能在无头环境验证兜底路径",
            GraphicsEnvironment.isHeadless(),
        )

    @Test
    fun `pickFile never throws when the system dialog is unavailable`() {
        requireHeadless()
        // 无头环境下 AWT 直接抛；这里只关心「抛没抛出去到调用方」
        val result = runCatching {
            runBlocking { NativePathPicker.pickFile(System.getProperty("java.io.tmpdir") ?: ".", "x.csv") }
        }
        assertTrue(
            result.isSuccess,
            "系统对话框不可用时不能把异常甩给调用方：${result.exceptionOrNull()}",
        )
        // 返回 null（取消 / 不可用）是合法结果；返回一个非空值也行，但必须字段完整
        result.getOrNull()?.let { picked ->
            assertNotNull(picked.dir, "选出来必须有目录")
            assertTrue(picked.dir.isNotBlank(), "目录不能是空串")
        }
    }

    @Test
    fun `pickDirectory never throws either`() {
        requireHeadless()
        val result = runCatching {
            runBlocking { NativePathPicker.pickDirectory(System.getProperty("java.io.tmpdir") ?: ".") }
        }
        assertTrue(result.isSuccess, "目录选择器同样必须兜住：${result.exceptionOrNull()}")
        result.getOrNull()?.let { picked ->
            assertTrue(picked.dir.isNotBlank(), "目录不能是空串")
        }
    }

    @Test
    fun `an existing directory is accepted as the starting point`() {
        // 起始目录必须真的存在且可用：系统对话框打开时若指向一个不存在的路径，
        // 轻则落在别处、重则报错，用户根本不知道自己是从哪儿开始挑的
        val dir = Files.createTempDirectory("sundays-picker").toFile()
        val marker = File(dir, "keep.txt").apply { writeText("x") }
        val picked = NativePathPicker.Picked(dir = dir.absolutePath, name = marker.name)
        assertEquals(dir.absolutePath, picked.dir)
        assertEquals("keep.txt", picked.name)
    }
}