# 12 — 四态核验驱动等待、回退或停止

**What to build:** 维护者通过模拟任务输入动作前后观察，规则与回放分类器输出四态，并让任务进入正确的继续、等待、视觉回退或暂停分支。

Blocked by: 01, 02

Status: ready-for-agent

Execution: in-progress
Owner: luna-offline-policies
Branch: codex/v1-offline-policies
Evidence: 离线行为
Gate: 四态核验驱动等待、回退或停止

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)、[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 覆盖 SUCCESS、FAILURE、PENDING、UNKNOWN 的独立标签及误报成功反例，动作后置条件与任务终局分别判定。
- [ ] 有界等待达到上限后明确处理；树/截图缺失不能报成功；视觉所需前图不可补造。
- [ ] 分类器可回放、报告四态混淆及回退；本票不将策略启用于真实生产链路。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：agent_core/、独立离线 fixtures 与 tests/test_offline_*.py；eval/ 与 contracts/v1 只读，不接入真实运行策略。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。
