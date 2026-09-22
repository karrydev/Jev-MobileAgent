# 04 — 断线和重启后核对并手动恢复模拟任务

**What to build:** 中断模拟任务、重启任一端后，用户看到待核对状态；重新观察与核对历史后，只有显式恢复才继续任务。

Blocked by: 03

Status: ready-for-agent

Execution: done
Owner: luna-recovery
Branch: codex/v1-recovery
Evidence: 离线行为
Gate: 断线和重启后核对并手动恢复模拟任务

## 前置与规格

[03 — 运行中的任务可以暂停和取消](03-pause-cancel.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 在发送前、执行后回执前、回执后核验前三个时点注入故障；重连均保持暂停。
- [x] 选择并实现最小持久化边界；命令结果为已执行/未执行/未知时分别处理，未知不重放。
- [x] 明确恢复资格与现场版本绑定；核对后现场再次改变、旧连接或迟到响应不能绕过确认。
- [x] 演示重启后的任务最终状态与副作用数量；证据不足时保持暂停并给出原因。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：前置已验收，协调者领取。新 Luna-max 实现、Terra-max 风险审查。04 唯一拥有 sim_loop 运行状态/恢复及 contracts/v1；10 只新增独立任务报告适配器与测试，以运行入口读取现有轨迹，不修改 sim_loop、eval 引擎或共享契约。主 Agent 统一合入与 Git。

2026-09-22：Luna 已留下持久化恢复实现；会话中断后主 Agent 复跑 7 项 recovery 测试通过，进入原运行控制回归与 Terra 审查。当前会话达到子 Agent 总数上限，无法继续按技能创建新角色；沿用已有 Luna-max/Terra-max 串行承担后续实现/审查，主 Agent 仍只负责调度、验证与 Git。此为工具限制的执行调整，不免除验收门禁。

2026-09-22：39 项相关测试通过后，Terra 定向复现两项阻断：恢复 confirmed 字符串可绕过显式 boolean 确认；设备单端重启丢失非持久 ledger 后将未知动作错误判 NOT_EXECUTED 并可重放。等待 Luna 修复严格输入类型与未知历史判据，并补负测；本票保持 in-progress，不合入未通过实现。

2026-09-22：实现 `f2b24b1` 经 Terra 定向复审 PASS 后合入。Python 3.11 / schema 1.0，`python3 -B -m unittest tests.test_recovery -v` 14 项通过，既有 `tests.test_sim_loop tests.test_runtime_controls` 32 项通过，schema-check 6 正例 / 3 反例。原子 JSON 检查点支持 service/device 状态重建；三故障点重连保持暂停，核对 EXECUTED/NOT_EXECUTED/UNKNOWN，显式 boolean 确认绑定观察版本与单次 token，现场变化使资格失效。设备单端重启无 durable history 的旧动作保持 UNKNOWN，旧设备副作用 1、新设备 0；取消终态不复活。仅关闭模拟持久化/恢复，不表示真机恢复或物理 exactly-once。
