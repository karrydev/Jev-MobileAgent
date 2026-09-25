# 27 修复与 25/27 集成定向复审

2026-09-25。只读复审 `535f38f..65b06e7` 的任务 27 场景门禁修复，以及合并提交 `26732adfdf0ae7aac7ed30140d82a494f1a4d179` 中任务 25/27 的共享运行接口。任务 25 已有 `on-demand-planning-review.md` 的源码 clear 结论，本次只核对实际合并边界。未作 Git 变更，未操作手机、ADB、模型 API、凭据、预算或源码，未重复测试。沿用集成记录 `integrated25-27-build.json`：123 项 Android unit test 零失败，`assembleDebug` 成功，APK SHA-256 `b01acfd8e8ae16bd25ceabbb50df787e895f5b50b2c2ba7d3dfe9d584e54ea14`。

## 2026-09-25 定向复审：唯一 P1 已关闭，源码 clear

只核对 `26732ad..2bba3d0` 中 `LocalTaskControlPolicy.java` 与 `LocalTaskControlPolicyTest.java` 的单一修复 diff。`appendPackageArray()` 现在仅当活跃应用包名与节点包名都精确为 `com.jev.mobileagent`、节点 `content_description` 精确为 `Local VLM task status` 时，排除该节点的易变 `text` 和 `state_description`；窗口、其他节点及该节点其余字段继续参加指纹（`LocalTaskControlPolicy.java:13-16,539-565`）。因此费用预留/结算引起的自身状态文字刷新仍可触发一次 fresh 检查，却不会仅因文字不同而暂停；派发 runnable 使用同一 `sceneFingerprint()`，也获得相同修复。其他目标文字变化或他包同名描述仍拒绝。恢复确认仍比较完整截图哈希，状态文字豁免不会放宽截图不匹配的最终判定。

实现者报告 19 项相关策略测试通过，主协调报告新 APK `assembleDebug` 成功；新增测试覆盖本包状态文字改变仍同场景、真实目标文字改变拒绝、他包同名描述无豁免、恢复截图改变拒绝。本复审未重复运行测试，也未触碰新 APK/手机。**定向源码结论 clear，未发现剩余范围内阻塞；真机受控页完整请求至动作路径及任务 25/27 整体验收仍由主协调记录。**

## 初审阻塞（上述修复已关闭）

**[P1] App 自己的费用状态刷新会被新场景门禁当作目标页面变化，使正常任务在付费模型请求后暂停。** `ControlledPageActivity` 前台时每 750ms 执行 `refreshTaskStatus()`，并将任务状态、本任务和全局 `accounted_cost_cny` 写进可访问的 `taskStatus` 文本（`ControlledPageActivity.java:57-65,198-217,318-335`）。每次 Manager/Executor 请求的 `reserveRequest()` 立即增加该费用，`finishRequest()` 又用实际 usage 结算（`LocalTaskStore.java:1138-1150,1187-1205`；`LocalVlmTaskService.java:1910-2010`）。无障碍观察包含节点文本（`ObservationAccessibilityService.java:1884-1905`）；新增 `sceneFingerprint()` 对当前应用的节点 `text` 取指纹（`LocalTaskControlPolicy.java:80-103`）。状态文字变化会产生 `TYPE_WINDOW_CONTENT_CHANGED` 等事件，触发 fresh 对比（`ObservationAccessibilityService.java:186-214`）。在 Manager/Executor 响应后的 `ensureDecisionSceneCurrent()` 中，新文本与请求前观察不同就调用 `requestControl("pause", "decision_scene_changed_before_dispatch")`（`LocalVlmTaskService.java:859,885,948-951,1872-1904`）；即便事件漏报，动作派发 runnable 仍无条件重新采集并比较同一指纹，也会拒绝动作（`LocalVlmTaskService.java:1073-1079`；`ObservationAccessibilityService.java:1085-1116`）。因此受控真机页面保持不变、无人接管时，任务自己的费用刷新足以触发暂停，且模型调用已计费。这违反任务 25 的正常任务质量与任务 27 的“现场变化才使旧决策失效”语义。建议让决策场景比对忽略 App 自有的任务状态/预算展示，同时继续比对真实目标内容、窗口、焦点及派发前 fresh 场景；用该受控页完整 Manager→Executor→动作路径验证不会自触发暂停，另保留真实目标改变时拒绝派发的用例。本次为源码可到达的高概率回归，尚未用当前 APK 真机复现；123 项 JVM 测试和构建不覆盖 Activity 定时刷新与无障碍事件的交错。

## 已关闭的旧意见及集成检查

- `recovery27-scene-review.md` 的暂停/目标写入竞态：`persistObservation()` 通过 `recoveryControl.runIfCurrent()` 在同一代次门禁内保存运行观察；暂停请求递增代次，失效时只落普通观察，不更新可信恢复目标（`LocalVlmTaskService.java:1844-1869`；`LocalRecoveryControlGate.java:17-41`）。本轮未见原竞态仍可在“控制请求已被门禁接受后”更新目标的路径。
- 该旧意见的 RECOVERY 重采样缺帧：确认循环对**每一帧**先调用 `persistRecoveryAuditObservation()`，再记录 RECOVERY 截图、分类；`saveObservation(..., false)` 不重绑运行目标（`LocalVlmTaskService.java:500-538`；`LocalTaskStore.java:1417-1463`）。只有完整复核场景（含截图哈希）匹配才可应用恢复决策，语义相同仅允许有界重采样（`LocalTaskControlPolicy.java:158-216`）。
- 旧跨应用记录：`resolveLegacyRecoveryTarget()` 按 BEFORE/AFTER 截图 `observation_id` 关联运行观察，排除 RECOVERY，晚于可信关联且指向另一应用的未关联观察判为歧义并拒绝目标；已标记的新记录直接取最后可信运行目标（`LocalTaskStore.java:234-336`）。正常单应用旧记录仍可核对。对应 `LocalTaskStoreTest` 覆盖关联与歧义分支。
- 新场景事件只触发 fresh 语义比较；来自 Agent 自己的页面事件若目标语义未变，不会仅凭事件序号暂停。真正动作派发 runnable 再做 fresh 比对，旁路观察不重绑节点句柄或动作观察版本（`LocalVlmTaskService.java:1872-1905`；`ObservationAccessibilityService.java:309-328,1095-1116,1691-1699`）。上述阻塞是**自有可访问文本确实变了**，不同于“仅事件触发”的误报。
- 合并后的 Manager 请求仍被前后 `ensureDecisionSceneCurrent()` 包裹，且保留任务 25 的规划失败事件；Executor 使用 `executorPrompt(onDemandPlanning)` 后先做场景检查，再消费完成提示。任务 25 的恢复入口从持久规划事件恢复连续失败、循环重规划状态，明确让旧计划失效并在 fresh 观察后重新规划；任务 27 的确认只显式启动新代次，不重置请求、费用或历史（`LocalVlmTaskService.java:763-785,859-915,948-960` 与恢复启动路径；`on-demand-planning-review.md`）。未见合并冲突引入的第二项接口阻塞。

`recovery27-manual01.json` 的原始触摸只是点击已聚焦输入框，页面语义未变化；它不能作为“真实现场已改变但仍派发旧动作”的失败证据。本复审也不把源码审查、unit test 或已安装 APK 视为任务 25/27 的完整物理手机验收。
