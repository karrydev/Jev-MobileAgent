# 19 — 用户通过 App 完成仅 VLM 的真实任务

**What to build:** 用户在物理手机 App 发起已确认任务，由提取后的 VLM 策略经 App 观察和执行，看到完整状态、独立结果与任务报告。

Blocked by: 08, 10, 14, 18

Status: ready-for-agent

Execution: pending
Owner: unassigned
Branch: unassigned
Evidence: 真实 API 与物理手机
Gate: 用户通过 App 完成仅 VLM 的真实任务

## 前置与规格

[08 — 手势与截图支持完成受控页面任务](08-android-visual-task.md)、[10 — 运行一次任务即可获得轨迹、判分和费用报告](10-task-evidence.md)、[14 — 提供物理手机与首轮任务授权](14-human-phone-access.md)、[18 — 裁剪后仍能复现已建立的对照](18-prune-with-regression.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 真实树、输入/节点/手势、截图和设备能力与 ROM 一起验证；实际手机问题不能用模拟器结果掩盖。
- [ ] 与参考后端使用同条件任务比较，首次只改变设备通道，不加入 Jev 或新核验/规划策略。
- [ ] 动作回执、动作效果与任务完成分开；暂停、取消、截图不可用和权限失效均产生明确结果。
- [ ] 留下可复现真实任务与失败报告，手机操作走 App，开发机此阶段仍可运行编排。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。
