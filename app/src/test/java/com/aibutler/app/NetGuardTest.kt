package com.aibutler.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 明文流量守卫：https 全放行；http 只放行本机/局域网（Ollama 场景），外网一律拒绝 */
class NetGuardTest {

    @Test
    fun https一律放行() {
        assertNull(NetGuard.cleartextProblem("https://api.deepseek.com/v1"))
        assertNull(NetGuard.cleartextProblem("https://open.bigmodel.cn/api/paas/v4"))
        assertNull(NetGuard.cleartextProblem("https://api.siliconflow.cn/v1"))
    }

    @Test
    fun 明文http只放行本机和局域网() {
        assertNull(NetGuard.cleartextProblem("http://192.168.1.100:11434/v1"))
        assertNull(NetGuard.cleartextProblem("http://localhost:11434/v1"))
        assertNull(NetGuard.cleartextProblem("http://127.0.0.1:8080"))
        assertNull(NetGuard.cleartextProblem("http://10.0.0.5/v1"))
        assertNull(NetGuard.cleartextProblem("http://172.16.0.1"))
        assertNull(NetGuard.cleartextProblem("http://172.31.255.255"))
        assertNull(NetGuard.cleartextProblem("http://mypc:11434/v1"))   // 单标签主机名（局域网）
    }

    @Test
    fun 明文http访问外网一律拒绝() {
        assertNotNull(NetGuard.cleartextProblem("http://api.deepseek.com/v1"))
        assertNotNull(NetGuard.cleartextProblem("http://8.8.8.8"))
        assertNotNull(NetGuard.cleartextProblem("http://172.32.0.1"))   // 172.16-31 私网段之外
        assertNotNull(NetGuard.cleartextProblem("http://192.169.0.1"))  // 不是 192.168 段
    }

    @Test
    fun 非法输入直接拒绝并给出原因() {
        assertNotNull(NetGuard.cleartextProblem(""))
        assertNotNull(NetGuard.cleartextProblem("   "))
        assertNotNull(NetGuard.cleartextProblem("ftp://x"))
        assertNotNull(NetGuard.cleartextProblem("api.example.com"))     // 没有 scheme
        assertNotNull(NetGuard.cleartextProblem("http://"))             // 没有主机名
    }
}
