# 05 — 旧目标与重复命令不会产生额外动作

**What to build:** 通过任务入口对模拟页面提交过期、重复、乱序和参数错误动作，用户看到明确拒绝或已有结果，设备只产生合法动作的副作用。

Blocked by: 01

Status: ready-for-agent

Execution: done
Owner: luna-runtime-controls
Branch: codex/v1-runtime-controls
Evidence: 离线行为
Gate: 旧目标与重复命令不会产生额外动作

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 绑定观察与节点引用，页面更新后旧目标拒绝；缺参、未知动作和设备不支持的动作有明确结果。
- [x] 稳定命令身份与去重规则可观察；同命令重复交付不执行两次，乱序和旧会话命令不越过当前任务。
- [x] 针对真实不可确认窗口保留 UNKNOWN，不声称物理执行 exactly-once；与恢复切片交界语义形成兼容样例。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：services/sim_loop/、contracts/v1/、tests/test_sim_loop.py 与新的 runtime 测试；同一运行链合批，实现暂停/取消后再接回放。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：Terra 经 localhost HTTP 复现：明确 stale_observation 拒绝后占用未释放；旧 session 可在首个当前动作前抢占。已交新 Luna 修复并补公共边界回归，本票保持 in-progress。

2026-09-22：实现 `2bc92fb` 经 Terra 定向复审 PASS 后合入 main。Python 3.11 / schema 1.0；`python3 -B -m unittest tests.test_runtime_controls tests.test_sim_loop` 32 项通过；主树 schema-check 正例 6 / 反例 3，中文双图 replay 成功。控制前禁止下发、在途结果保守核对；可信设备明确拒绝释放占用，超时/代理 5xx 保留 UNKNOWN，观察阶段绑定 session 防旧命令抢占。真实模型探针已提供 endpoint/model/credential_env 配置和显式 `--execute` 请求入口，本地 HTTP fixture 验证请求、usage、429；未读取真实凭据或调用供应商，不表示真实 API 兼容。恢复持久化留给 04。
