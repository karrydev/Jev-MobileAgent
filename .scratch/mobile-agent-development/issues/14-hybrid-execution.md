# Jev 影子与受控执行

Status: ready-for-agent
Execution: pending
Blocked by: 12, 13
Owner: unassigned
Branch: unassigned
Workstream: Agent 与模型
Gate: JevSelector 接入有可比较的真实证据

## 目标

JevSelector 接入有可比较的真实证据。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [仅 VLM 的 App 闭环](12-vlm-device-loop.md)：需在集成分支上完成并有验收证据。
- [候选构造与 Jev 离线选择](13-candidate-selector.md)：需在集成分支上完成并有验收证据。

## 修改范围

agent-core/ 的混合执行路由与配置开关；eval 对照。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 先在 VLM 闭环中影子记录 Jev 建议；增加实际采集轨迹的候选覆盖与中文对照。
2. 冻结启用条件后受控开启选择策略，保留 VLM 回退；核验器与规划频率保持不变。
3. 将候选缺失、错误选择、参数、设备执行和模型回退区分归因。

## 验收

- [ ] 影子与受控执行分别报告；只有真正执行 Jev 建议的任务可用于效果结论。
- [ ] 策略开关关闭后恢复前一阶段行为；回退时前面的 Jev 调用成本仍计入。
- [ ] 按冻结任务和预算取得独立判分，未满足放行规则时保持默认 VLM 并明确原因。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [阶段与评测设计](../../../docs/research/2026-09-22-stages-evaluation-and-flows.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
