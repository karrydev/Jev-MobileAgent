# 仅 VLM 的 App 闭环

Status: ready-for-agent
Execution: pending
Blocked by: 07, 09, 10, 11
Owner: unassigned
Branch: unassigned
Workstream: Agent 与集成
Gate: 首个 App 设备通道完整任务可用

## 目标

首个 App 设备通道完整任务可用。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [上游裁剪与复验](07-prune-upstream.md)：需在集成分支上完成并有验收证据。
- [Android 执行、截图与任务控制](09-android-execution.md)：需在集成分支上完成并有验收证据。
- [服务端连接与任务会话](10-service-session.md)：需在集成分支上完成并有验收证据。
- [轨迹、回放与费用统计](11-trace-replay.md)：需在集成分支上完成并有验收证据。

## 修改范围

Agent 与 App DeviceBridge 适配；集成配置；eval 集成证据。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 让提取后的 VLM 角色使用 App 观察/执行，保持既有规划与视觉核验策略，不启用 Jev 和按需规划。
2. 核对截图、观察、坐标、动作与回执的一致性；完成从用户任务到独立判分的整条链。
3. 与提取后参考设备基线对照，定位设备通道带来的变化，产出下一阶段真实轨迹。

## 验收

- [ ] 目标设备完成已冻结的可控与中文任务，失败案例也保留；每个动作均有可关联的后续观察。
- [ ] App 执行链不通过宿主 ADB 偷跑；此阶段可在开发机托管编排，最终脱离开发机另验。
- [ ] 可以回退到参考后端并复现问题；任务成功由独立判据确认。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [阶段与评测设计](../../../docs/research/2026-09-22-stages-evaluation-and-flows.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
