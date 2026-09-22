# 10 — 运行一次任务即可获得轨迹、判分和费用报告

**What to build:** 维护者从任务运行入口拿到关联的观察、动作、回执、核验、独立终局结果与消耗报告，能够回放一次失败。

Blocked by: 02, 09

Status: ready-for-agent

Execution: done
Owner: luna-task-evidence
Branch: codex/v1-task-evidence
Evidence: 离线行为
Gate: 运行一次任务即可获得轨迹、判分和费用报告

## 前置与规格

[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)、[09 — 回放模型响应驱动任务并处理服务错误](09-model-replay-task.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 把任务 02 判分入口接入现有运行轨迹，成功、失败、取消、重试与回退全部纳入总尝试。
- [x] 延迟与按角色/模型 usage 可追溯；缺失 usage、无法估算费用明确标注，价格依据注明版本。
- [x] 原始材料与公开脱敏报告分离，凭据和个人内容不进入提交或常规日志。
- [x] 一条可复现运行方式输出报告与版本清单；篡改或缺失关键事件不能被悄悄判为成功。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：前置已验收，协调者领取。新 Luna-max 实现、Terra-max 风险审查。04 唯一拥有 sim_loop 运行状态/恢复及 contracts/v1；10 只新增独立任务报告适配器与测试，以运行入口读取现有轨迹，不修改 sim_loop、eval 引擎或共享契约。主 Agent 统一合入与 Git。

2026-09-22：初版 8 项报告测试通过，但 Terra 复现两项阻断：修改 raw scenario_flavor 可改变重放独立判分；关键 action/verification link 未完全验证且公共报告透传不受控 links。尚未验收合入，需显式独立 truth 与严格链接/公开匿名化修复。

2026-09-22：集中修复后，独立 truth 改为来自 SimulatedDevice 的单独 sidecar，错误关联/公开 links 泄露的新增 5 条用例通过（总 13 项）。Terra 确认原两根因已关闭，但复现重复 receipt 事件可被 event_positions 字典覆盖，导致错误的先回执后动作顺序仍被判成功；仅剩该关键事件唯一性缺口待修，未合入。

2026-09-22：验收完成。实现 `654751b`，合入 `3b62a5a`。重复关键实体事件在计算因果位置前拒绝，Terra 定向复验通过。主树合并任务 04 后运行 `python3 -B -m unittest tests.test_task_evidence`，14/14 通过。独立 truth、轨迹关联及事件唯一性负例、公开报告脱敏、usage 缺失与未定价标记均已覆盖。入口见 `services/task_evidence/README.md`；证据来自 localhost 模拟设备和回放模型，价格版本 `local-unpriced-v1`，不代表真实供应商费用或真机任务完成。
