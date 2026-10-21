package com.aibutler.app

/** 任务启动后锁定目标包；执行期只允许回到同一个应用，防止模型误开无关 App。 */
object TaskAppGuard {
    fun isAllowed(requestedPackage: String?, allowedPackage: String?): Boolean {
        if (allowedPackage.isNullOrBlank()) return true
        if (requestedPackage.isNullOrBlank()) return true
        return requestedPackage == allowedPackage
    }
}
