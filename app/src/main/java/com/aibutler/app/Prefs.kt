package com.aibutler.app

import android.content.Context

data class Vendor(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val models: List<String>,
    val keyUrl: String,
    val keyHint: String
)

object Vendors {
    val list = listOf(
        Vendor(
            "deepseek", "DeepSeek 深度求索",
            "https://api.deepseek.com/v1", "deepseek-chat",
            listOf("deepseek-chat", "deepseek-reasoner"),
            "https://platform.deepseek.com/api_keys",
            "去 platform.deepseek.com 注册 → API Keys → 创建，便宜且好用"
        ),
        Vendor(
            "zhipu", "智谱 GLM（有免费模型）",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash",
            listOf("glm-4.5-flash", "autoglm-phone", "glm-4-flash", "glm-4-flash-250414", "glm-4.5", "glm-4.6", "glm-4-plus"),
            "https://open.bigmodel.cn/usercenter/apikeys",
            "去 open.bigmodel.cn 注册 → API Keys；glm-4.5-flash / glm-4-flash 免费。如果你买的是编程包月套餐（电脑上用 Claude Code/ccswitch 的那种），请选「智谱编程版」"
        ),
        Vendor(
            "zhipu_coding", "智谱 GLM 编程版（包月套餐用这个）",
            "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.3-flash",
            listOf("glm-5.3-flash", "glm-4.6", "glm-4.5", "glm-4.5-flash", "glm-4-flash"),
            "https://open.bigmodel.cn/usercenter/apikeys",
            "电脑上用 Claude Code / ccswitch 等工具的 GLM 包月套餐，额度只在 coding 端点生效；用通用接口会报「余额不足1113」"
        ),
        Vendor(
            "kimi", "Kimi 月之暗面",
            "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            listOf("moonshot-v1-8k", "moonshot-v1-32k", "kimi-latest"),
            "https://platform.moonshot.cn/console/api-keys",
            "去 platform.moonshot.cn 注册 → API Key 管理 → 新建"
        ),
        Vendor(
            "qwen", "通义千问 阿里云",
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            listOf("qwen-turbo", "qwen-plus", "qwen-max"),
            "https://bailian.console.aliyun.com/?apiKey=1",
            "去阿里云百炼控制台开通 → 创建 API Key（新用户有免费额度）"
        ),
        Vendor(
            "siliconflow", "硅基流动 SiliconFlow",
            "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3",
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat"),
            "https://cloud.siliconflow.cn/account/ak",
            "去 cloud.siliconflow.cn 注册 → API 密钥 → 新建（注册送额度）"
        ),
        Vendor(
            "moonshot_openrouter", "OpenRouter（聚合全球模型）",
            "https://openrouter.ai/api/v1", "openai/gpt-4o-mini",
            listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "google/gemini-flash-1.5"),
            "https://openrouter.ai/keys",
            "去 openrouter.ai 注册 → Keys，一个 key 用全球各家模型"
        ),
        Vendor(
            "openai", "OpenAI",
            "https://api.openai.com/v1", "gpt-4o-mini",
            listOf("gpt-4o-mini", "gpt-4o"),
            "https://platform.openai.com/api-keys",
            "去 platform.openai.com 创建 API Key"
        ),
        Vendor(
            "ollama", "Ollama 本地/局域网（免 key）",
            "http://192.168.1.100:11434/v1", "qwen2.5:7b",
            listOf("qwen2.5:7b", "llama3.1:8b", "deepseek-r1:8b"),
            "",
            "在电脑上安装 Ollama 并设置 OLLAMA_HOST=0.0.0.0，把地址改成电脑的局域网 IP，Key 留空即可"
        ),
        Vendor(
            "custom", "自定义（任意 OpenAI 兼容接口）",
            "https://", "",
            emptyList(),
            "",
            "任何兼容 OpenAI /chat/completions 协议的服务都能用"
        )
    )

    fun byId(id: String): Vendor = list.find { it.id == id } ?: list[0]
}

object Prefs {
    private const val FILE = "settings"
    private const val THINKING_VISIBLE_MIGRATION = "thinkingVisibleMigration"

    // 敏感项（API Key / 锁屏 PIN）经 SecureStore(Keystore AES-GCM) 加密后存这些键；
    // 旧版本的明文键在首次读取时自动迁移并删除
    private const val KEY_API_KEY = "apiKey_enc"
    private const val KEY_API_KEY_LEGACY = "apiKey"
    private const val KEY_PIN = "lockPin_enc"
    private const val KEY_PIN_LEGACY = "lockPin"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(c: Context): android.content.SharedPreferences = sp(c)

    fun fastMode(c: Context): Boolean = get(c).getBoolean("fastMode", false)
    fun setFastMode(c: Context, v: Boolean) {
        get(c).edit().putBoolean("fastMode", v).apply()
    }

    fun autoGlm(c: Context): Boolean = get(c).getBoolean("autoGlm", false)
    fun setAutoGlm(c: Context, v: Boolean) {
        get(c).edit().putBoolean("autoGlm", v).apply()
    }

    /** 是否在聊天里显示模型的思考过程（只展示，不回传接口） */
    fun showThinking(c: Context): Boolean = get(c).getBoolean("showThinking", true)
    fun setShowThinking(c: Context, v: Boolean) {
        get(c).edit().putBoolean("showThinking", v).apply()
    }

    fun developerMode(c: Context): Boolean = get(c).getBoolean("developerMode", false)
    fun setDeveloperMode(c: Context, v: Boolean) {
        get(c).edit().putBoolean("developerMode", v).apply()
    }

    /** 把旧版本里关闭的思考展示和快速模式切到可见状态；用户之后仍可在设置里改。 */
    fun ensureThinkingVisible(c: Context) {
        val sp = get(c)
        if (sp.getBoolean(THINKING_VISIBLE_MIGRATION, false)) return
        sp.edit()
            .putBoolean("showThinking", true)
            .putBoolean("fastMode", false)
            .putBoolean(THINKING_VISIBLE_MIGRATION, true)
            .apply()
    }

    fun pin(c: Context): String {
        val sp = get(c)
        sp.getString(KEY_PIN, null)?.let { return SecureStore.decrypt(it) }
        // 旧版本明文 PIN → 首次读取自动迁移为加密存储
        val legacy = sp.getString(KEY_PIN_LEGACY, null) ?: return ""
        sp.edit().putString(KEY_PIN, SecureStore.encrypt(legacy)).remove(KEY_PIN_LEGACY).apply()
        return legacy
    }

    fun setPin(c: Context, v: String) {
        get(c).edit().putString(KEY_PIN, SecureStore.encrypt(v)).remove(KEY_PIN_LEGACY).apply()
    }

    fun save(c: Context, vendorId: String, baseUrl: String, model: String, apiKey: String) {
        sp(c).edit()
            .putString("vendorId", vendorId)
            .putString("baseUrl", baseUrl)
            .putString("model", model)
            .putString(KEY_API_KEY, SecureStore.encrypt(apiKey))
            .remove(KEY_API_KEY_LEGACY)
            .apply()
    }

    fun vendorId(c: Context) = sp(c).getString("vendorId", "") ?: ""
    fun baseUrl(c: Context) = sp(c).getString("baseUrl", "") ?: ""
    fun model(c: Context) = sp(c).getString("model", "") ?: ""

    fun apiKey(c: Context): String {
        val sp = sp(c)
        sp.getString(KEY_API_KEY, null)?.let { return SecureStore.decrypt(it) }
        // 旧版本明文 Key → 首次读取自动迁移为加密存储
        val legacy = sp.getString(KEY_API_KEY_LEGACY, null) ?: return ""
        sp.edit().putString(KEY_API_KEY, SecureStore.encrypt(legacy)).remove(KEY_API_KEY_LEGACY).apply()
        return legacy
    }

    fun configured(c: Context) = baseUrl(c).startsWith("http") && model(c).isNotBlank()
}
