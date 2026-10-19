package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 原子写：保证任何时刻正式文件都是完整内容，且不留 .tmp 残留 */
class AtomicFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assertNoTmpLeft() {
        val leftovers = tmp.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("留下 .tmp 残留: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun 首次写入创建文件内容完整() {
        val dir = tmp.newFolder("mem")
        val file = File(dir, "notes.json")
        AtomicFiles.write(file, "v1")
        assertEquals("v1", file.readText())
        assertNoTmpLeft()
    }

    @Test
    fun 覆盖写入替换旧内容且无残留() {
        val file = File(tmp.root, "data.json")
        AtomicFiles.write(file, "v1")
        AtomicFiles.write(file, "v2-更长的覆盖内容")
        assertEquals("v2-更长的覆盖内容", file.readText())
        assertNoTmpLeft()
    }

    @Test
    fun 连续多次写入内容始终完整() {
        val file = File(tmp.root, "loop.json")
        for (i in 1..20) AtomicFiles.write(file, "payload-$i")
        assertEquals("payload-20", file.readText())
        assertNoTmpLeft()
    }
}
