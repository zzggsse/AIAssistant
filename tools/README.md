# Code Harness

`code_harness.py` 是给 AI 编码助手用的工程级护栏，按当前 Git 变更集执行六层检查：上下文、约束、受控工具、评估、反馈、清理。它不替代人工审查，也不能绕过 `AGENTS.md`。

## 常用命令

```powershell
python tools\code_harness.py context "新增通用任务完成判定"
python tools\code_harness.py check --json
python tools\code_harness.py cleanup
python tools\test_code_harness.py
```

`check` 会先做静态护栏，再运行 `gradlew testDebugUnitTest` 和 `gradlew assembleDebug`。提交前先执行 `git add`，否则新文件不会进入变更集。

## 固定护栏

- `versionCode` 必须正好加一；`versionName` 只允许 patch、minor 或 major 语义递进。
- 修改 `AgentEngine.kt`、`DeviceTools.kt`、`AssistantAccessibilityService.kt` 时必须有 Kotlin 回归测试变更。
- 拒绝疑似自动点击付款、转账、确认下单、验证码控件的代码。
- 拒绝 `_tmp/`、`测试结果/`、密钥文件和内嵌长凭证。
- 拒绝 `eval`、`exec`、`os.system`、PowerShell EncodedCommand 等高危动态执行入口。
- 输出结构化结果，失败项可直接作为下一轮修复反馈。
