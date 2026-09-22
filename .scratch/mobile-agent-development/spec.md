# Jev-MobileAgent 首版开发任务

本目录是实施任务索引，使用本地 Markdown 追踪。产品范围、阶段和验收以[开发路线](../../docs/development-roadmap.md)为准，领取与合入以[并行协作约定](../../docs/parallel-development.md)为准；本索引不重复保存技术决策。

## 当前起点

- 公开 Fork 与工程初始化已完成；上游起点为 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`。
- 所有下列实施任务均未开始；表格只表达依赖，不表示能力已经存在。
- 首版单用户、单手机、单任务；中断后核对状态并由用户确认恢复。

## 任务与依赖

| 任务 | 工作线 | 前置任务 |
| --- | --- | --- |
| [共享契约与工程布局](issues/01-shared-contracts.md) | 契约与集成 | 无 |
| [模型协议兼容性验证](issues/02-model-compatibility.md) | Agent 与模型 | [API 与设备访问条件](issues/04-human-access.md) |
| [任务样本与独立判分设计](issues/03-evaluation-design.md) | 评测与证据 | 无 |
| [API 与设备访问条件](issues/04-human-access.md) | 用户与集成 | 无 |
| [原版双入口基线](issues/05-upstream-baselines.md) | Agent 与模型 | [模型协议兼容性验证](issues/02-model-compatibility.md)、[任务样本与独立判分设计](issues/03-evaluation-design.md) |
| [角色提取与行为对照](issues/06-extract-roles.md) | Agent 与模型 | [共享契约与工程布局](issues/01-shared-contracts.md)、[原版双入口基线](issues/05-upstream-baselines.md) |
| [上游裁剪与复验](issues/07-prune-upstream.md) | Agent 与集成 | [角色提取与行为对照](issues/06-extract-roles.md) |
| [Android 观察与能力上报](issues/08-android-observation.md) | Android | [共享契约与工程布局](issues/01-shared-contracts.md)、[API 与设备访问条件](issues/04-human-access.md) |
| [Android 执行、截图与任务控制](issues/09-android-execution.md) | Android | [Android 观察与能力上报](issues/08-android-observation.md) |
| [服务端连接与任务会话](issues/10-service-session.md) | 服务端与会话 | [共享契约与工程布局](issues/01-shared-contracts.md) |
| [轨迹、回放与费用统计](issues/11-trace-replay.md) | 评测与证据 | [共享契约与工程布局](issues/01-shared-contracts.md)、[任务样本与独立判分设计](issues/03-evaluation-design.md) |
| [仅 VLM 的 App 闭环](issues/12-vlm-device-loop.md) | Agent 与集成 | [上游裁剪与复验](issues/07-prune-upstream.md)、[Android 执行、截图与任务控制](issues/09-android-execution.md)、[服务端连接与任务会话](issues/10-service-session.md)、[轨迹、回放与费用统计](issues/11-trace-replay.md) |
| [候选构造与 Jev 离线选择](issues/13-candidate-selector.md) | Agent 与模型 | [共享契约与工程布局](issues/01-shared-contracts.md)、[模型协议兼容性验证](issues/02-model-compatibility.md)、[任务样本与独立判分设计](issues/03-evaluation-design.md) |
| [Jev 影子与受控执行](issues/14-hybrid-execution.md) | Agent 与模型 | [仅 VLM 的 App 闭环](issues/12-vlm-device-loop.md)、[候选构造与 Jev 离线选择](issues/13-candidate-selector.md) |
| [四态数据与核验模块](issues/15-verification-module.md) | 评测与 Agent | [模型协议兼容性验证](issues/02-model-compatibility.md)、[轨迹、回放与费用统计](issues/11-trace-replay.md)、[仅 VLM 的 App 闭环](issues/12-vlm-device-loop.md) |
| [树核验接入与对照](issues/16-verification-integration.md) | Agent 与集成 | [Jev 影子与受控执行](issues/14-hybrid-execution.md)、[四态数据与核验模块](issues/15-verification-module.md) |
| [按需规划调度与对照](issues/17-planning-scheduler.md) | Agent 与模型 | [树核验接入与对照](issues/16-verification-integration.md) |
| [故障恢复与用户接管](issues/18-recovery.md) | 服务端与 Android | [仅 VLM 的 App 闭环](issues/12-vlm-device-loop.md) |
| [服务器与测试网络准备](issues/19-server-access.md) | 用户与服务端 | 无 |
| [服务器部署与脱离 ADB 验收](issues/20-independent-deployment.md) | 服务端与集成 | [故障恢复与用户接管](issues/18-recovery.md)、[服务器与测试网络准备](issues/19-server-access.md) |
| [完整对照与交付复核](issues/21-final-acceptance.md) | 评测与集成 | [按需规划调度与对照](issues/17-planning-scheduler.md)、[服务器部署与脱离 ADB 验收](issues/20-independent-deployment.md) |

## 第一批工作

Agent 可以分别领取“共享契约与工程布局”和“任务样本与独立判分设计”。用户并行准备“API 与设备访问条件”；“服务器与测试网络准备”可稍后进行，不阻塞最初的 App 本地联调。

每张任务的 Status 只表达 triage 分类，实际可领取条件还包括 Execution、Owner 和全部依赖。当前状态以任务文件为准，不在本索引维护第二份进度表。

## 完成条件

“完整对照与交付复核”任务在最终集成提交上通过；所有首版必需依赖都有对应证据。没有实际 API、设备或用户确认的结果，不以 Mock 或代码存在代替。
