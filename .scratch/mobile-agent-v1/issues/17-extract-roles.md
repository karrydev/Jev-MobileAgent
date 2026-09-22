# 17 — 提取角色后在参考设备后端保持任务行为

**What to build:** 维护者在参考设备后端运行提取出的四角色编排，得到与原版同条件可比较的任务结果，同时保留可运行原版。

Blocked by: 01, 16

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实 API、参考设备后端
Gate: 提取角色后在参考设备后端保持任务行为

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)、[16 — 分别运行原真机与四角色最小基线](16-original-baselines.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 只提取生产需要的 Manager、Executor、ActionReflector、Notetaker 与状态，解耦 AndroidWorld、固定尺寸和任务特例。
- [ ] 从统一观察到动作的边界运行原有生成式策略；本票不启用 Jev、树核验或按需规划。
- [ ] 先并存新旧实现再对照，任何迁移批次保持原入口可运行；记录来源、变更及许可证。
- [ ] 相同任务配置的实际行为对照通过冻结规则；不能只以导入成功判定提取完成。

## 范围与协调

必要角色的前置重构；若实际影响超出单次上下文，按新旧并存的迁移批次细分后再领取。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
