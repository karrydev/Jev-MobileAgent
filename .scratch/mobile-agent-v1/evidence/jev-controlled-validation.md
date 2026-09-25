# 任务 23：手机内 Jev 受控选择

2026-09-25。实现 `0d01ec5`，APK SHA-256 `119595789d05b7c84c2a3451755ee8dc7629e907a6e3e03e143d5b70cc769666`。同一 APK、OnePlus 8T / Android 14、GUI-Plus gui-plus-2026-02-26、TypeSafe jev-1.13.0。按[冻结规则](jev-controlled-plan.md)各执行一次，没有额外重跑。

## 配对结果

| 场景/模式 | 独立结果 | VLM / Jev 请求 | 任务耗时 | VLM 标价估算 | Jev 保守占用 |
|---|---|---|---|---|---|
| [中文仅 VLM](jev23-vlm-input01.json) | 输入精确匹配并正确结束 | 8 / 0 | 20.672s | ¥0.0498975 | ¥0 |
| [中文 Jev-on](jev23-on-input01.json) | Jev 实际执行 set_text，输入精确匹配并正确结束 | 5 / 1 | 14.806s | ¥0.030588 | ¥0.01 |
| [蓝框仅 VLM](jev23-vlm-visual01.json) | 页面 coordinate tap completed，正确结束 | 5 / 0 | 14.439s | ¥0.0311115 | ¥0 |
| [蓝框 Jev-on](jev23-on-visual01.json) | 候选缺失回退视觉，页面完成并正确结束 | 6 / 0 | 17.310s | ¥0.0373275 | ¥0 |

四项满足受控放行规则。中文 Jev 选择将 VLM 的聚焦草案替换为完整输入候选；报告正确标为 different_action，实际派发来源为 jev-controlled-candidate-v1，Reflector/history 对应实际动作。蓝框两步均 candidate_coverage_vlm_candidate_missing，在请求前回退；没有执行错误预设按钮。两种模式的蓝框首个动作均被 stale_observation 拒绝，后续新观察执行成功；失败动作保留，未剔除费用。

VLM Executor 仍按原频率生成覆盖依据，Manager/Reflector 调度未改。一次中文任务少了聚焦步骤，并不证明稳定的费用/延迟收益；视觉任务本轮反而多一次 VLM 请求。样本仅证明两个冻结受控目标，不外推任意跨 App 成功率。

## 验证与边界

[Sol-max审查](jev23-review.md)未发现范围内阻塞，含任务22候选修复相关范围。20项定向 JVM 测试通过（JevCandidateBuilderTest / JevShadowTest / MobileAgentVlmRolesTest），APK构建成功，diff检查通过。命令：在 android-app 设置 ANDROID_HOME 和 ANDROID_SDK_ROOT 为本机 SDK，执行 ./gradlew --offline --no-daemon --console plain :app:testDebugUnitTest 并以 --tests 筛选三类；随后 :app:assembleDebug。

模型由 App 已加密配置直连，没有读取开发 env 或通过项目 Python 服务执行。USB 只用于安装、UI准备、启动及只读取证，运行中没有宿主动作派发；本轮不是拔线验收，27/28仍需完成恢复和最终独立交付。原始截图/观察留手机私有目录及本机 /tmp/jev23-evidence，公开JSON仅为受控页面脱敏事实。

本轮 VLM 新增估算 ¥0.1489245，Jev新增一次保留 ¥0.01；累计已知估算 ¥1.145361，含历史未知费用保守占用合计 ¥2.292308，低于¥10。Jev累计12/20，未重置计数、预算或虚构汇率，实际账单未知。详见[总账](../live-model-budget.json)。主机读取记录遇到一次原子替换解析失败，后续重试读取正常；没有把取证失败解释为产品失败，也未额外发起任务。

测试后通过 App UI 关闭受控选择和影子开关，无活动任务；手机已锁屏 Dozing。

最终合入：`59e328b013bdf86efb4419b28265ec9991300e10`。Android 内容与已验证实现相同，保留原 APK/模型/运行证据。
