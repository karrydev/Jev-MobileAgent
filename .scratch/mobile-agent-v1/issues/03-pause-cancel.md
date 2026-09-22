# 03 — 运行中的任务可以暂停和取消

**What to build:** 用户通过任务控制入口暂停或取消模拟任务，状态反馈与设备执行一致，迟到的模型结果不能产生新动作。

Blocked by: 01

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 离线行为
Gate: 运行中的任务可以暂停和取消

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 分别覆盖动作派发前与在途时暂停/取消，清楚报告已执行或未知结果，不承诺撤销既有副作用。
- [ ] 暂停后无新命令；取消是终态；重复控制请求和第二个任务得到稳定结果。
- [ ] 从公开入口验证控制行为与设备副作用，提供 App 可消费的状态与正反交互样例。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
