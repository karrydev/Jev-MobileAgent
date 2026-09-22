# 12 — 四态核验驱动等待、回退或停止

**What to build:** 维护者通过模拟任务输入动作前后观察，规则与回放分类器输出四态，并让任务进入正确的继续、等待、视觉回退或暂停分支。

Blocked by: 01, 02

Status: ready-for-agent

Execution: done
Owner: luna-offline-policies
Branch: codex/v1-offline-policies
Evidence: 离线行为
Gate: 四态核验驱动等待、回退或停止

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)、[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 覆盖 SUCCESS、FAILURE、PENDING、UNKNOWN 的独立标签及误报成功反例，动作后置条件与任务终局分别判定。
- [x] 有界等待达到上限后明确处理；树/截图缺失不能报成功；视觉所需前图不可补造。
- [x] 分类器可回放、报告四态混淆及回退；本票不将策略启用于真实生产链路。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：agent_core/、独立离线 fixtures 与 tests/test_offline_*.py；eval/ 与 contracts/v1 只读，不接入真实运行策略。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：Terra 复现缺失动作前截图时仍请求视觉回退，前图无法事后补造；正在补充原因感知的暂停决策，以及实际数据集隔离/冻结校验。离线测试不代表真实分类效果。

2026-09-22：验收完成并以 `0516928` 合入 main。Python 3.11 标准库；`python3 -B -m unittest tests.test_offline_selection tests.test_offline_verification -v` 共 20 项通过；主树 `python3 -B -m agent_core selection --split all --output /tmp/jev-offline-selection-report.json` 与 `verification --split all --output /tmp/jev-offline-verification-report.json` 均通过。新 Luna 集中修复后，Terra 定向复审 PASS：全量跨分区身份检查、策略/replay SHA-256 冻结校验、无时钟明确回退、缺前图强制暂停均有负向证据。版本化合成 fixtures 与独立 eval 判分覆盖候选错误、四态和任务终局；报告与原始合成数据可复现。仅关闭离线行为；未调用真实 Jev、未宣称模型质量或成本收益、未启用生产策略。
