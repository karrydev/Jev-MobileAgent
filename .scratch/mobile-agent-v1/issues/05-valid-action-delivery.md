# 05 — 旧目标与重复命令不会产生额外动作

**What to build:** 通过任务入口对模拟页面提交过期、重复、乱序和参数错误动作，用户看到明确拒绝或已有结果，设备只产生合法动作的副作用。

Blocked by: 01

Status: ready-for-agent

Execution: in-progress
Owner: luna-runtime-controls
Branch: codex/v1-runtime-controls
Evidence: 离线行为
Gate: 旧目标与重复命令不会产生额外动作

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 绑定观察与节点引用，页面更新后旧目标拒绝；缺参、未知动作和设备不支持的动作有明确结果。
- [ ] 稳定命令身份与去重规则可观察；同命令重复交付不执行两次，乱序和旧会话命令不越过当前任务。
- [ ] 针对真实不可确认窗口保留 UNKNOWN，不声称物理执行 exactly-once；与恢复切片交界语义形成兼容样例。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：services/sim_loop/、contracts/v1/、tests/test_sim_loop.py 与新的 runtime 测试；同一运行链合批，实现暂停/取消后再接回放。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：Terra 经 localhost HTTP 复现：明确 stale_observation 拒绝后占用未释放；旧 session 可在首个当前动作前抢占。已交新 Luna 修复并补公共边界回归，本票保持 in-progress。
