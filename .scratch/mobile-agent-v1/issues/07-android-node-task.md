# 07 — 从 App 发起中文输入任务并控制执行

**What to build:** 用户在 App 输入目标，使用确定性测试策略经服务端完成受控页面的一次节点点击或中文输入；能看到状态并暂停、取消。

Blocked by: 03, 05, 06

Status: ready-for-agent

Execution: in-progress
Owner: luna-android-node-task
Branch: codex/v1-android-node-task
Evidence: 物理手机受控页面
Gate: 从 App 发起中文输入任务并控制执行

## 前置与规格

[03 — 运行中的任务可以暂停和取消](03-pause-cancel.md)、[05 — 旧目标与重复命令不会产生额外动作](05-valid-action-delivery.md)、[06 — 在模拟器中连接 App 并查看当前观察](06-android-observation.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 目标输入、任务状态、节点动作、重新观察与独立页面检查形成完整链路；无需真实模型凭据。
- [ ] 中文文本完整且来源可追溯；支持集之外明确拒绝，节点过期、遮挡或权限撤销不猜测执行。
- [ ] 在实际 App 通道重测重复命令、单任务限制、暂停与取消后迟到响应，设备副作用符合控制结果。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：03/05/06 已验收；按用户最新要求，后续优先在已授权 OnePlus 8T 真机的项目受控页面测试。领取范围 android-app/、services/android_bridge/、contracts/android/ 和独立 Android 测试；不得修改 04 独占的 sim_loop/contracts/v1。复用公开运行/控制语义，新增真实 App 执行适配。独占真机，所有 ADB 指定设备；系统授权可由 Agent UI 操作，密码等不可代办步骤才请求用户。
