# uitest — 真机自动化回归工具

不接大模型、不写测试代码，用 adb 驱动真机跑完整任务链路，验证「视觉脑(AutoGLM) 操作纯图形界面 App」的能力。

## run_task.ps1 — 注入中文任务并监控执行

```powershell
.\uitest\run_task.ps1 -Task '打开快手极速版，去签到领金币'
.\uitest\run_task.ps1 -Task '打开抖音极速版' -Polls 6 -IntervalSec 15
```

做什么：
1. 临时把输入法切到设备上的 **ADBKeyboard**（`am broadcast ADB_INPUT_TEXT` 注入中文）
2. 在 AI小助手 输入框写入任务、点发送
3. 轮询：前台 Activity + `AIButler` 日志（AGLM 轮次/动作/finish 验证关/收尾）+ 截图
4. 结束后检查 crash buffer、进程存活，产物存 `_tmp\uitest\<时间戳>\`
5. 还原原输入法

## 已知坑（2026-02 实测）

- **QQ拼音会吃字符**：`adb shell input text` 的英文字母会被拼音 composing 转成中文
  （曾把模型名 `glm-4.5-flash` 变成 `glm-4.5-flas过了没`）。凡是要往输入框打字，
  先切 ADBKeyboard。设置页填模型名同理。
- **`ime set` 偶发不生效**：切完必须回读 `settings get default_input_method` 验证并重试，
  否则中文注入会静默打到原输入法上被吞掉（run_task.ps1 已内置验证+重试+注入回读）。
- **adb daemon 冷启动竞态**：daemon 刚拉起时前几条 `settings put` 可能静默失败，
  写完必须回读验证（参考 一键增量部署.bat 的做法 + 重试）。
- **ColorOS 异步回收无障碍**：OPPO 系统会在 App 更新（install -r）后**过一段时间**
  才把侧载应用的 a11y 服务从启用列表摘掉——部署脚本"写完立刻读"验证不到这种回收。
  run_task.ps1 每次开跑前自查自愈；部署后过几分钟真机再验一次更稳。
- **ColorOS 投屏授权是两步且焦点会说谎**：第一步"录制或投放"页选**整个屏幕**
  （用户明确要求，跳过单应用选择器）；焦点在整个弹窗期间都可能显示为我们的
  App（mCurrentFocus 不可信），必须靠 dump 探测。脚本用 Unicode 码位拼中文匹配
  （[char]0x6574...），保持文件 ASCII-only。
- **僵尸授权请求**：授权弹窗被系统超时收回=静默拒绝，引擎会永远停在
  "正在请求屏幕截图权限"。遇到时 force-stop App 重来，不要重发消息。
- **SettingsActivity 未导出**：不能 `am start` 直达，要走主界面抽屉
  （`btnMenu` → `btnDrawerSettings`）。
- **finish 验证关**：视觉脑声称完成任务时 App 会要求出示完成证据再收尾，
  属正常流程（日志关键词「验证关」「收尾」）。
- **悬浮停止球抓不到**：停止气泡是无障碍 overlay，uiautomator dump 看不见；
  自动化叫停的兜底手段是 `am force-stop` 进程后重启。
- **抖音极速版读屏不透明**：视频/赚钱流页面几乎不暴露 a11y 文本节点，
  外部 dump/录像抓不到操作路径；教路径优先用 App 内「学习模式」
  （无障碍事件能看到点击），或让用户文字描述后种入经验库。

## 回归基线（已验证通过）

| 任务 | 结果 |
|---|---|
| 打开抖音极速版 | ✅ 打开 → finish 带界面描述 → 验证关二轮确认 |
| 打开快手极速版，去签到领金币 | ✅ 28轮：开App→签到→激励视频广告→领到952金币，零崩溃 |
