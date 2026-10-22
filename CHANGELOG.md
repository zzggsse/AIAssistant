# 更新日志

## v1.4.2（2026-10-22）
- 修复奖励任务遇到倒计时和下载挽留层时的空转问题：倒计时会自动等待，挽留层优先点明确退出控件，连续失败立即交还用户。

## v1.4.1（versionCode 9）（2026-10-22）

- 修复一键抓日志脚本在未配置本机工具路径时无法启动的问题；抓取时固定使用 USB 设备，避免同一手机的 Wi-Fi 连接导致冲突。

## v1.4.0（versionCode 8）（2026-10-21）

- 新增 NativeTaskRouter：单步系统任务本地确定性路由，不再交给模型猜工具
- 新增 ContextCompressor：按字符预算压缩 LLM 对话历史
- 新增 TaskAppGuard / PageGuard / ExitMemory：任务期锁定目标应用、页面守卫、退出路线记忆
- 新增 CrashLogger、VisualSampleListDialog；气泡/图标/布局/配色等界面样式迭代
- 工程收编：gradle wrapper、独立 test 模块入库；新增 AGENTS.md 开发规则与本更新日志

## v1.3.0（versionCode 7）— 上一基线

- 建立日志时补记的基线版本，此前历史改动见 git 提交记录。
- 今后每次版本变更在顶部按 `## vX.Y.Z（YYYY-MM-DD）` 格式追加。
