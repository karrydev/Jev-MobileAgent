# 独立 App + VLM 基线

2026-09-25；任务 26 验收。实现提交 `373da44`，APK SHA-256 `800dc859a29f9a8990ded0c28abbfd4dd2fe0f8fedb514f671ed168956e8ac7e`；回连读取已安装 APK 的散列一致。设备 OnePlus 8T / Android 14，模型 GUI-Plus `gui-plus-2026-02-26`；直接 HTTPS、App Keystore 配置。默认 Manager/Executor/Action Reflector，Notetaker 关闭。

| 冻结任务 | 结果 | 请求/步数 | 标价估算 | 证据 |
|---|---|---|---|---|
| 精确输入“独立手机测试成功” | SUCCEEDED；新观察文本精确一致，answer 被保留 | 9 / 4 | ¥0.056130 | [中文](standalone-usb-input01.json) |
| 点击蓝色视觉目标 | SUCCEEDED；(545,991) 落在蓝框内，页面副作用正确 | 5 / 2 | ¥0.0307665 | [视觉](standalone-usb-visual01.json) |

执行前无项目 Python 进程、8765/18771 无监听，ADB reverse 为空、无线调试关闭。用户物理拔线并在手机发起两次任务，确认完成后重新连接；拔线事实由用户确认，运行窗口无宿主控制或连续采集。回连后核对本地请求、动作、反思、终局新观察及屏幕；输入和视觉结果均保留，随后锁屏。

首次坐标因观察失效被拒绝，后续新观察动作成功；未删除失败或重试。两项仅证明冻结受控任务，不证明任意 App 成功率或 Jev 收益。所有先前失败、审查修复、设置/权限/控制结果见 [联调记录](standalone-runtime-review01.md)；完整恢复由 27、最终策略对照由 28 承接。旧 19/20 仍是 Python 桥接历史基线。

既有客户端/密钥与运行时定向检查按代码未变化范围沿用；最后 answer 改动五项定向测试与 assembleDebug 通过，Sol 复审无阻塞。不因仅文档合并重新请求付费模型。

角色/提示词文件 SHA-256：`8e73aa836a501ae9626b12c468a1def723ddb8d0c5fb220bf453c065bc8537b3`。
