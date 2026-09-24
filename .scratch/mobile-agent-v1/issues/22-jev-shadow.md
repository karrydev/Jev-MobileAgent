# 22 — 真实 Jev 在 VLM 任务旁提供可评估建议

**What to build:** 真实 VLM 仍控制手机，Jev 对当前观察生成候选选择建议；维护者能看中文兼容性、错误、回退和建议差异报告。

Blocked by: 11, 21, 26

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实模型、物理手机影子运行
Gate: 真实 Jev 在 VLM 任务旁提供可评估建议

## 前置与规格

[11 — 离线候选选择可以执行或明确回退](11-offline-selection.md)、[26 — 手机内 VLM 闭环](26-app-local-vlm.md)、[21 — 验证 Jev 实测访问条件](21-human-jev-access.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 候选构造、Jev HTTPS 客户端与影子报告在 App 内运行；使用 App 中的 Jev 配置，不通过 Python 编排服务。复用任务 11 的候选语义与样例，并以任务 26 的独立 App+VLM 为对照。

- [ ] 先用有限探针验证真实 Jev 输入、返回 ID、错误与 usage，再加入影子任务；无效结果不进入执行。
- [ ] 冻结候选与阈值后测留出集，区分候选缺失、选择错误与合法等价动作。
- [ ] 记录完整尝试和费用；明确影子报告不证明 Jev 实际控制后的成功率。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-25：按 ADR-0003 改为手机内策略实现与独立 App 对照；旧 Python 实现可作参考，不作为本票产品运行通道。
