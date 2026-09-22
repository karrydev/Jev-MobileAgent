# 08 — 手势与截图支持完成受控页面任务

**What to build:** 当受控页面需要滑动、长按、系统导航或坐标动作时，测试策略可经 App 完成操作并获取关联截图，旋转后仍能核对结果。

Blocked by: 07

Status: ready-for-agent

Execution: in-progress
Owner: luna-android-visual-task
Branch: codex/v1-android-visual-task
Evidence: 物理手机受控页面
Gate: 手势与截图支持完成受控页面任务

## 前置与规格

[07 — 从 App 发起中文输入任务并控制执行](07-android-node-task.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 用固定的小型页面集验证所需手势/系统动作；坐标经过旋转、缩放、系统栏/窗口偏移后命中正确区域。
- [ ] 按需上传的前后图关联观察，动作前图真实预留；分别记录截图采集与上传，无法截图返回原因。
- [ ] 截图缺失、输入法、多窗口和语义缺失分支可演示；不把受理回执当成页面效果。
- [ ] 产出能安装的 App 与手机授权步骤，真实 ROM 验收由后续真机任务完成。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：07 已验收，领取手势/截图完整切片，按用户要求在已授权物理手机验证。修改范围 Android App、Android bridge、Android 契约与直接测试；沿用既有控制和节点绑定保护，不改原版角色或模拟运行器。由于运行环境已达子 Agent 总创建上限，复用现有 Luna-max 与 Terra-max，主 Agent 统一 Git 和验收。
