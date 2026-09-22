# 24 — 树核验参与真实任务且证据不足可回退

**What to build:** 用户的动作先按规则、必要时 Jev、信息不足时视觉进行四态核验；任务等待、继续或暂停与实际结果一致。

Blocked by: 12, 23

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实模型与物理手机
Gate: 树核验参与真实任务且证据不足可回退

## 前置与规格

[12 — 四态核验驱动等待、回退或停止](12-offline-verification.md)、[23 — Jev 选择参与真实任务执行](23-jev-controlled.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 补足真实操作的逐步标签，回执与终局判分均不能直接当四态真值。
- [ ] 实际验证加载、无效点击、相似文字、树外变化和截图不可用，报告误报成功与四态混淆。
- [ ] 前后图需求、有限等待及 UNKNOWN 分支有效；保持前阶段选择与规划设置，只改变核验策略。
- [ ] 同条件实际对照满足放行规则，记录所有模型调用与截图采集/上传。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
