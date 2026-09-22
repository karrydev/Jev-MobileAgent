# 03 — 运行中的任务可以暂停和取消

**What to build:** 用户通过任务控制入口暂停或取消模拟任务，状态反馈与设备执行一致，迟到的模型结果不能产生新动作。

Blocked by: 01

Status: ready-for-agent

Execution: done
Owner: luna-runtime-controls
Branch: codex/v1-runtime-controls
Evidence: 离线行为
Gate: 运行中的任务可以暂停和取消

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 分别覆盖动作派发前与在途时暂停/取消，清楚报告已执行或未知结果，不承诺撤销既有副作用。
- [x] 暂停后无新命令；取消是终态；重复控制请求和第二个任务得到稳定结果。
- [x] 从公开入口验证控制行为与设备副作用，提供 App 可消费的状态与正反交互样例。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：services/sim_loop/、contracts/v1/、tests/test_sim_loop.py 与新的 runtime 测试；同一运行链合批，实现暂停/取消后再接回放。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：运行控制批次的 26 项测试通过；Terra 审查发现设备明确拒绝被归为 UNKNOWN、旧 session 首命令可抢占以及真实探针未实现，正在由新的 Luna 集中修复。未验收合入；在途动作的交付承诺点已明确，不承诺暂停撤回已交付动作。

2026-09-22：实现 `2bc92fb` 经 Terra 定向复审 PASS 后合入 main。Python 3.11 / schema 1.0；`python3 -B -m unittest tests.test_runtime_controls tests.test_sim_loop` 32 项通过；主树 schema-check 正例 6 / 反例 3，中文双图 replay 成功。控制前禁止下发、在途结果保守核对；可信设备明确拒绝释放占用，超时/代理 5xx 保留 UNKNOWN，观察阶段绑定 session 防旧命令抢占。真实模型探针已提供 endpoint/model/credential_env 配置和显式 `--execute` 请求入口，本地 HTTP fixture 验证请求、usage、429；未读取真实凭据或调用供应商，不表示真实 API 兼容。恢复持久化留给 04。
