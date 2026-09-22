# 16 — 分别运行原真机与四角色最小基线

**What to build:** 维护者分别运行一个固定小型真机单模型任务集与 AndroidWorld 四角色任务集，得到可复现的独立完成判分及轨迹。

Blocked by: 02, 14, 15

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实 API、AndroidWorld、物理手机
Gate: 分别运行原真机与四角色最小基线

## 前置与规格

[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)、[14 — 提供物理手机与首轮任务授权](14-human-phone-access.md)、[15 — 真实 VLM 能完成所需角色协议请求](15-live-vlm-compatibility.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 固定两入口各自代码、模型、提示词、设备/模拟器、任务、重复与预算，不能混成同一组收益数据。
- [ ] 原版或最小修复版可运行，修复分开提交；报告全部失败和环境限制。
- [ ] 使用具体 AndroidWorld 任务判据，基类默认返回成功不能当判分；真机按已确认 rubrics 验证。
- [ ] 实际 AndroidWorld 环境由 Agent 优先准备；无法运行时保持本票未完成，禁止以离线回放代替基线。

## 范围与协调

原版双入口有限小样本，不在本票扩成大规模 benchmark。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
