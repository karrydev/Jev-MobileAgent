# 11 — 离线候选选择可以执行或明确回退

**What to build:** 维护者输入合成观察与目标，经候选构造、回放选择器、动作执行模拟和独立判分得到选择报告；无合法候选时可看到视觉回退请求。

Blocked by: 01, 02

Status: ready-for-agent

Execution: done
Owner: luna-offline-policies
Branch: codex/v1-offline-policies
Evidence: 离线行为
Gate: 离线候选选择可以执行或明确回退

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)、[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 候选只来自当前观察和已知参数，中文与等价动作可接受集合有标注；参考答案和未来帧不进入候选生成。
- [x] 非法 ID、缺参、过期观察、空候选分别报告；自由文本不会被要求从候选 ID 生成。
- [x] 开发集选择规则后冻结再测留出集，区分候选缺失、选择错误、参数错误和回退。
- [x] 选择器通过回放边界可测试；实际 Jev 接口与质量保留给任务 22，不依赖 Jev key 完成本票。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：agent_core/、独立离线 fixtures 与 tests/test_offline_*.py；eval/ 与 contracts/v1 只读，不接入真实运行策略。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：离线选择/核验 13 项测试通过；Terra 复现按单 split 运行时数据隔离不强制、冻结标志无 artifact 校验、缺少时钟仍使用过期观察。新的 Luna 正在修复；未启用真实 Jev。

2026-09-22：验收完成并以 `0516928` 合入 main。Python 3.11 标准库；`python3 -B -m unittest tests.test_offline_selection tests.test_offline_verification -v` 共 20 项通过；主树 `python3 -B -m agent_core selection --split all --output /tmp/jev-offline-selection-report.json` 与 `verification --split all --output /tmp/jev-offline-verification-report.json` 均通过。新 Luna 集中修复后，Terra 定向复审 PASS：全量跨分区身份检查、策略/replay SHA-256 冻结校验、无时钟明确回退、缺前图强制暂停均有负向证据。版本化合成 fixtures 与独立 eval 判分覆盖候选错误、四态和任务终局；报告与原始合成数据可复现。仅关闭离线行为；未调用真实 Jev、未宣称模型质量或成本收益、未启用生产策略。
