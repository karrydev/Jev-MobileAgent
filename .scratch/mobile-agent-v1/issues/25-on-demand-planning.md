# 25 — 按需规划完成任务并处理计划失效

**What to build:** 任务开始、子目标切换或异常时重新规划，其他步骤复用计划；用户遇到停滞、循环或过期计划时仍能得到明确处理。

Blocked by: 24

Status: ready-for-agent

Execution: in-progress
Owner: luna-on-demand-planning
Branch: codex/v1-on-demand-planning
Evidence: 真实模型与物理手机
Gate: 按需规划完成任务并处理计划失效

## 前置与规格

[24 — 树核验参与真实任务且证据不足可回退](24-tree-verification.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 本票策略在 App 内运行，直连配置的模型；仅 VLM 回退也在手机内，不调用项目 Python 服务。沿用任务 26 的本地状态、预算、轨迹与控制入口，不另建第二套运行框架。

- [ ] 保持逐步核验，覆盖子目标完成、计划失效、反复失败、循环与预算耗尽。
- [ ] 与任务 24 同条件配对比较，只有规划调度变化，调用减少不能掩盖完成质量下降。
- [ ] 通过开关可回到原规划策略，报告任务结果、延迟与全角色消耗，满足冻结放行规则。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-25：按 ADR-0003 改为手机内策略实现与独立 App 对照；旧 Python 实现可作参考，不作为本票产品运行通道。

2026-09-25：24验收记录已在bf278d1主线完成，领取本票。25为Android运行/持久化的唯一源码实现者；27已固定d48e0d8 APK，由主协调继续真机验收，不并发修改共享源码。按需规划默认关闭，保持24选择与核验设置；实现者不接触真机、凭据/API、总账或Git。

2026-09-25：gui-plus 在 bec1606 APK 上完成四项同条件 OFF/ON 有界对照，实际输入与蓝框均未达目标，不能放行。查明旧版 gui-plus 与日期版坐标协议不同，而 Android 统一按0–1000处理；正在进行精确模型适配，旧失败与费用保留。详见 [真实对照记录](../evidence/on-demand-planning-validation.md)。

2026-09-25：2915ed9新增有界post-action观察关联与Jev70上限，Policy24定向通过，assemble成功，Sol时序意见已修复。四项同APK复测仅中文OFF自动SUCCEEDED，中文ON需fresh确认、两项蓝框未命中且已结束；仍不放行。状态栏节点1px移动导致结构拒绝及截图期间事件需后续定向处理，详见[最新证据](../evidence/post-action-scene-validation.md)。已恢复规划OFF，Jev46/70，费用上限不变。

2026-09-25：352835e升级已保留计数并启用用户授权150总上限，27项定向测试/构建/聚焦Sol审查通过。四项同APK复测中文OFF自动成功，中文ON首次输入成功但重复输入后暂停，两蓝框未完成；51/150调用，费用上限未变。全部原始失败和人工结束分别保留，见[最新实测](../evidence/scene-scope-repair-validation.md)。未关闭本票，手机已锁屏、规划OFF。

2026-09-25：b84d1b8补齐Jev不同动作经SUCCESS后的计划失效交接，13项定向测试/构建/Sol审查通过。新版四配对中文均需fresh人工确认，蓝框OFF自动成功、ON未命中；仍不放行。Jev60/150，累计估算与未知预留¥3.7455340、规划阶段¥0.7912585；全部费用限额不变。见[规划交接及最新实测](../evidence/planning-selected-action-validation.md)。

2026-09-25继续排障：09e65b0补齐Jev所选节点的真实可视位置并纠正动作描述/后置状态混用，14项定向单测与Sol审查通过。保留旧prompt和首版prompt两次错误SUCCESS；最终相同截图负例UNKNOWN、真实命中正例SUCCESS，4次host-only探针共¥0.0198735，未新增Jev。见[定位与效果归因](../evidence/visual-reflector-grounding-validation.md)。这不是手机任务验收；无障碍输入节点陈旧仍在正式路径取证，状态不变，无需新增额度或配置授权。

2026-09-26：c4a718b已修复Android无障碍子节点缓存，149项单测、构建、Sol定向审查通过；同APK两侧中文和ON蓝框自动成功，OFF蓝框仍被模型误读既有蓝边，整体门禁拒绝并fresh结束。Jev66/150，金额限额不变，见[四项结果](../evidence/fresh-capture-paired-validation.md)。视觉限制正在有界诊断；27新增无可信目标且零动作任务不能取消的死锁，保留原任务等待源码修复，不清数据绕过。

2026-09-26：8d3bcc5同APK严格四配对中文OFF/ON和蓝框OFF自动成功，蓝框ON的Manager误把视觉限定目标认作灰色同名预设，错误点击后新视觉守卫在真实Android路径拦截静态SUCCESS，NEEDS_REVIEW后明确未决结束。失败不改写，质量门槛未过；见[四配对记录](../evidence/visual-cancel-final-paired-validation.md)。Jev72/150，规划阶段¥1.0226505，剩余不足下一完整¥1预留；按code-this继续离线目标约束修复，不越过金额门禁。
