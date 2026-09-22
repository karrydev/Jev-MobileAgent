# 09 — 回放模型响应驱动任务并处理服务错误

**What to build:** 开发者用合成模型响应驱动一个模拟任务；多图、中文、无效动作、超时及限流得到可追踪结果，缺凭据时明确提示真实模式不可用。

Blocked by: 01

Status: ready-for-agent

Execution: in-progress
Owner: luna-runtime-controls
Branch: codex/v1-runtime-controls
Evidence: 离线行为
Gate: 回放模型响应驱动任务并处理服务错误

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 实现模型边界与回放入口，角色请求/响应、错误、尝试和 usage 可关联到任务。
- [ ] 非法格式与缺参不执行；限流、超时和重试有边界，暂停/取消后响应不得派发。
- [ ] 真实探针的启动方式、无密钥配置样例与最小请求已就绪；合成结果不表述为供应商协议已兼容。
- [ ] 只新增隔离适配与测试材料，不在原版实际基线前提取或改写上游角色行为。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：services/sim_loop/、contracts/v1/、tests/test_sim_loop.py 与新的 runtime 测试；同一运行链合批，实现暂停/取消后再接回放。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。
