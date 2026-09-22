# 15 — 真实 VLM 能完成所需角色协议请求

**What to build:** 维护者用固定的小型探针获得真实单模型与四角色协议的响应报告，明确可用与不兼容能力，再把一个有效动作交给模拟设备执行。

Blocked by: 09, 13

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实模型 API
Gate: 真实 VLM 能完成所需角色协议请求

## 前置与规格

[09 — 回放模型响应驱动任务并处理服务错误](09-model-replay-task.md)、[13 — 提供真实 VLM 最小访问条件](13-human-vlm-access.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 实际验证所选模型的中文、多图、动作/规划/反思/记忆所需格式与 usage；不把 SDK 兼容等同于角色兼容。
- [ ] 记录模型、配置、提示词、响应、错误与真实消耗，遵守用户允许的试跑预算。
- [ ] 最小适配或上游阻塞修复单独记录，静态 file URI/动作覆盖/坐标疑点先复现，不凭猜测改写基线。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
