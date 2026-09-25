# Jev Android App

本目录承载[独立 Android 运行时](../docs/adr/0003-standalone-android-runtime.md)：App 在手机内观察、规划、执行、核验和记录任务，直接请求模型供应商。产品入口不再配置设备桥地址或 Token。

任务 26 尚在实施和验收。当前已取得真实 VLM 的中文输入与视觉点击 USB 联调记录；它们不是断开 USB 后的独立使用验收。完整中断恢复和最终交付分别由 27、28 验收，状态见[任务索引](../.scratch/mobile-agent-v1/index.md)。

## 构建与安装

使用 JDK 17 和 Android SDK（android-35）。Gradle wrapper 固定版本，SDK 可通过环境变量或标准安装路径发现：

```bash
export JEV_ANDROID_SDK=/path/to/android-sdk
./bootstrap.sh --offline --no-daemon --console plain :app:testDebugUnitTest :app:assembleDebug
"$JEV_ANDROID_SDK/platform-tools/adb" -s <device-serial> \
  install -r app/build/outputs/apk/debug/app-debug.apk
```

`--offline` 要求 wrapper 和依赖已进入本地 Gradle 缓存。首次下载依赖时可省略该参数。ADB 只用于开发安装和验收取证，用户运行任务无需安装开发工具。

## App 配置和操作

1. 在主页面填写 VLM 的供应商、HTTPS endpoint、模型及 API Key，点击保存。当前付费任务只放行已配置费率的 `GUI-Plus` / `gui-plus-2026-02-26`。
2. Jev 配置单独保存，可留空；本阶段仍由 VLM 控制任务，Jev 策略由 22–25 接入。开发者的 env 文件不是用户配置入口。
3. 开启 **Jev local task accessibility** 无障碍服务，并允许通知。通知提供任务启动、暂停和取消入口。
4. 受控验证：打开受控测试页面，选择“中文输入目标”或“视觉手势目标”，点击“开始本地 VLM 任务”。
5. 其他应用：填写目标，点击“准备在其他应用中运行”，切换到目标应用，再从通知点击“开始任务”。

VLM 和 Jev Key 使用 Android Keystore 支持的 AES-GCM 加密保存，界面不回显。端点或供应商变更时须重新输入 Key；同一配置留空则保留。每种配置均可独立清除。旧桥接版本的 VLM 明文设置仅在成功迁移后移除；加密或解密失败会要求重新输入。

任务和证据留在 App 私有目录；普通使用无需读取开发机文件，也不把 Key 转发给设备桥。应用备份关闭。

## 当前边界

- 同时只有一个任务。每任务最多 5 步、25 次模型请求、单请求最多 1024 输出 token；每任务估算上限 ¥1，本地累计上限 ¥10。其他模型可保存，但尚不能通过当前费率门控。
- 请求前按 8192 输入 token 和输出上限估算预留；8192 不是输入硬上限。用量结算超额会停止后续请求，缺少 usage 保留预留并暂停。报告中的费用是标价估算，账户免费额度和实际账单需另核对。
- 模型的完成自述不等于独立终局成功。当前独立判据面向已冻结的受控中文输入和视觉点击目标；未支持的目标或证据不足进入待核对状态。
- 已发生动作不能被暂停撤销。结果未知的动作保留记录和任务占用；完整核对、用户确认恢复流程由任务 27 完成。
- 系统权限撤销、执行服务终止等情况停止新动作；不承诺锁屏自动操作或无限后台常驻。开发真机测试结束后锁屏。

## 移植来源与历史证据

角色和提示词对照 `agent_core/vlm/roles.py`、`agent_core/vlm/orchestration.py`，保留 MobileAgent v3.5 来源。默认角色流程包含 Manager、Executor、Action Reflector；Notetaker 的默认关闭行为保持不变。

旧 Python 设备桥、运行脚本与任务 19/20 的证据保留为开发参考，不构成新 App 的运行依赖，也不替代手机内基线。旧版桥接 App 的构建和操作说明可从 Git 历史及 [App+VLM 历史验证](../docs/app-vlm-validation.md)、[恢复历史验证](../docs/android-recovery-validation.md) 查阅。
