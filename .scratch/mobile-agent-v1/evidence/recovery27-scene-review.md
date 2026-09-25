# 27 scene 修复定向复审

2026-09-25。只读审查 `076008ab09a29564eb697edd0625c145de308641` 上未提交的 `LocalTaskStore`、`LocalVlmTaskService`、`LocalTaskControlPolicy` 与三个相关测试；未运行 Git 变更、ADB、API、凭据或重复构建/测试。以下是仍需关闭的范围内问题。

## 阻塞项

1. **暂停请求与恢复目标写入没有原子门禁。** `LocalVlmTaskService.persistObservation()` 先在 1496 行调用 `hasControlRequest()`，随后在 1499 行调用 `saveRunningObservation()`；两步之间未持有 `recoveryControl` 锁。人工触摸在 `ObservationAccessibilityService.java:190-197` 异步发送暂停 Intent。若主循环刚得到另一个前台 APPLICATION 的可用观察，而暂停请求在判定后、目标写入前到达，`requestControl()` 只更新 gate，运行中的任务状态到主循环 `finally` 才由 `RUNNING` 改为 `PAUSED`（`LocalVlmTaskService.java:281-330,1005-1015`）。因此 `LocalTaskStore.java:1275-1292` 的 `rememberRunningTargetPackage()` 仍通过 `RUNNING` 检查（`LocalTaskControlPolicy.java:187-205`），把人工切换后的应用写成 `last_running_target_application_package`；之后 `recoveryTargetPackage()` 优先使用它（`LocalTaskStore.java:228-233`）。触发路径可由“任务在 A 运行，用户触摸切到 C 并暂停，观察 C 与暂停 Intent 交错”到达。应在同一控制门禁内原子判断并持久化可信运行观察；人工介入的异步暂停信号也需在目标绑定前可见。

2. **新增重采样的中间帧缺失可回放的观察记录。** 每轮 `captureScreenshot()` 已把 PNG 和 `screenshot_captures` 条目落盘，条目带 `observation_id`/`observation_version`（`LocalVlmTaskService.java:1130-1165,1176-1190`；`LocalTaskStore.java:1301-1327,870-884`），而确认循环仅在最终 MATCH 或 REJECT 后保存一次 `confirmation`（`LocalVlmTaskService.java:476-540`）。例如第 1 帧光标亮导致全图不匹配、语义相同，第 2 帧匹配：第 1 帧有截图条目但对应 `observation-N.json` 和 `observations` 元数据均不存在，无法复核它为何获准继续采样。`captureRecoveryFrame()` 只返回 JSON 并不保存（`ObservationAccessibilityService.java:1730-1773`）；当前没有另一份完整观察存档。任务 27 要求本机记录可重建观察身份及恢复事实，故应逐帧用不重绑定目标的 `saveObservation()` 保存，仍保持最终新鲜全图哈希精确相等才提交。

## 历史记录兼容边界

旧版已暂停的 A→B 任务没有 `last_running_target_application_package` 或 `trusted_running_target_application_package`。现有 `recoveryTargetPackage()` 在 `target_application_package=A` 非空时直接返回 A（`LocalTaskStore.java:228-242`），即使最后一次主循环 `AFTER` 观察是 B。可构造旧任务：`state=PAUSED`、`target_application_package=A`、无新标记、`observations` 最后运行条目 `active_application_package=B`、相应 `screenshot_captures` 条目 `capture_type=AFTER`、全部动作执行事实已知且后置条件已决。用户回到 A 后，review 在 A 生成合法复核，confirm 若再次得到同一 A 全图，`applyRecoveryDecision()` 的场景和决策门禁可将任务设为 `RUNNING`（`LocalTaskStore.java:372-395,431-451,490-494`；`LocalTaskControlPolicy.java:57-73`）；它没有与历史最后运行 B 核对。

旧数据并非一概无法区分：`screenshot_captures` 的 `BEFORE`/`AFTER` 与 `observation_id` 可以关联到旧 `observations` 的运行观察，`RECOVERY` 类型可排除（`LocalVlmTaskService.java:1176-1190`）。但若进程在保存观察之后、截图尝试记录之前终止，来源就不可证明。应仅对能证明的旧运行观察迁移最后目标；无法证明的 A→B 旧记录 fail closed，并清楚说明不能在旧目标 A 上恢复。正常单应用旧任务仍可核对/结束，无需对所有旧任务禁用恢复。

## 已确认的门禁与剩余验证

`sameReviewedSemantics()` 仅允许最多 5 帧、2 秒的后续采样；`classifyRecoveryConfirmationSample()` 只有完整 scene 指纹（含截图哈希）相等才返回 MATCH，`applyRecoveryDecision()` 在持久化时再次核对完整指纹（`LocalTaskControlPolicy.java:121-175`；`LocalTaskStore.java:431-451`）。代码审查未发现语义相同直接放行恢复的路径。每轮观察与截图前后有代次、控制、锁屏/权限检查（`LocalVlmTaskService.java:476-511,598-614`）；真机光标场景可用性、交错暂停和旧记录迁移仍需针对最终改动验证。三个新增测试只覆盖策略值和 gate 状态，未覆盖上述持久化/服务交错路径。
