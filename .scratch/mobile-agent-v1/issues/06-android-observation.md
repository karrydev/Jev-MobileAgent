# 06 — 在模拟器中连接 App 并查看当前观察

**What to build:** 开发者启动 Android App，完成测试环境配对后，在服务端看到真实 Accessibility 观察与能力状态，并在 App 看到连接和权限反馈。

Blocked by: 01

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: Android 模拟器
Gate: 在模拟器中连接 App 并查看当前观察

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 在 Android 模拟器受控页面验证文字、状态、窗口、尺寸、旋转、时间与观察版本；不是以手写树代替 App 采集。
- [ ] 权限撤销、空树和连接失败有可见原因，不能显示过期观察仍有效。
- [ ] 提供可构建安装包、最小连接/权限界面与服务端读取入口，复用任务 01 契约。
- [ ] 由 Agent 检查并准备可用本地工具链与模拟器；环境实际不可用则记录阻塞并继续离线任务，本票不凭构建成功关闭。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
