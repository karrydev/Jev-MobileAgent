# 27 — 拔掉 USB 后从手机完成服务器任务

**What to build:** 用户手机连接普通服务器，拔掉 USB、停止开发机 ADB 与本地编排后仍可发起并完成约定任务及一次恢复演练。

Blocked by: 20, 26

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 服务器与物理手机
Gate: 拔掉 USB 后从手机完成服务器任务

## 前置与规格

[20 — 真实手机中断后可核对并手动恢复](20-real-device-recovery.md)、[26 — 提供最终服务器与网络访问条件](26-human-server-access.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 在授权服务器部署明确版本并验证 App 连接、任务结果、服务重启与手动恢复，留存实际证据。
- [ ] 模型调用与任务状态由服务器承载，最终观察/操作通道不依赖 ADB；评测辅助与生产通道清楚分离。
- [ ] 记录部署、App、模型与配置版本及回退步骤；本票可先验收仅 VLM 版本，完整策略最终在任务 28 重新验收。

## 范围与协调

Agent 在等待访问条件时可以先准备配置与产物；没有实际部署证据不关闭本票。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
