# 任务 25 聚焦风险审查

2026-09-25。初审基线 `09a662e` 上的全部未提交 Android 改动：`OnDemandPlanningPolicy` 与测试、`LocalVlmTaskService`、`LocalTaskStore`、`MainActivity`、`MobileAgentVlmRoles` 与测试。只读代码审查；未接触手机、模型 API、凭据，未提交源码。实现者已报告相关单测与 `assembleDebug` 通过，未重复运行；`git diff --check` 通过。下方初审阻塞项与恢复风险保留作历史记录，修复后的结论见定向复审。当前工作树尚无任务 27 的最新恢复源码，不作为对未合入源码的审查结论。

## 2026-09-25 定向复审结论

**任务 25 源码范围 clear，无剩余阻塞意见。** 此结论针对初审两项阻塞及其引出的规划状态恢复路径，不代替任务 25 的真实 API、物理手机同条件配对验收，也不审查未合入的任务 27 源码。复审只读；沿用实现者报告的 12 项策略测试、全量 unit test 与 `assembleDebug` 通过记录，未重复运行。

- **成功重复动作：已关闭。** `OnDemandPlanningPolicy.hasActionLoop()` 现在读取角色动作结果与 `verified_step_outcome`，最近重复动作若核验为 `SUCCESS` 或观察确有变化，不会仅凭相同动作触发循环。`LocalVlmTaskService` 在成功或观察变化后清除 `loopReplanAttempted`；`threeVerifiedNextPageActionsWithObservedProgressDoNotStopAsALoop` 覆盖连续三次合法重复动作。因此初审所述“成功点击下一页三次却暂停”的路径已断开。
- **Jev 非等价改选：已关闭。** 服务只在 Jev 实际选中并转换候选动作后，按该次选择报告的 `comparison.status` 调用 `executorCompletionHintApplies()`；仅 `same_action` / `legal_equivalent_action` 保留 Executor 的 `COMPLETE` 提示，`different_action` 等情况清除。树核验和原视觉核验两条 `SUCCESS` 路径都使用过滤后的提示计算 `nextPlanReason`。对应单测断言非等价改选后继续复用计划；初审所述“点击输入框成功却按原输入完成提示换子目标”的路径已断开。
- **任务 25 的恢复状态：已关闭。** 每个已核验步骤写入 `verified_step_outcome` 的下一规划原因、连续失败次数、循环重规划标记及动作后观察 SHA-256；新计划、决策与恢复失效也有调度检查点。重新进入服务时从持久事件和角色历史恢复三个调度状态，用当前新观察重新规划，同时保留失败次数与循环限制；不会直接复用旧 `Finished` 计划。`confirmedResumeKeepsFailureAndLoopLimitsButRequestsFreshPlan`、`terminalAnswerOutcomeDoesNotResetTheRestoredFailureLimit` 覆盖这些规则。任务 27 的实际恢复入口及两树集成仍由其独立复审与最终验收确认。
- **OFF 回退：保持原决策路径。** 开关默认 `false`，建任务时快照；OFF 仍走 `shouldSkipManager(roles)` 与原 `executorPrompt()` 正文。新增规划事件在 OFF 下为 best effort，不参与旧调度判断。

复审 `git diff --check` 通过。CodeGraph 在此独立 worktree 未初始化，用户已明确不初始化；本次按已知改动文件直接检查真实调用路径。

## 初审阻塞项（定向复审均已关闭）

1. **循环判断会把成功的重复操作判成停滞并暂停。** `OnDemandPlanningPolicy.java:34-46` 只比较最近动作的字符串，不看 `actionOutcomes`、页面变化或核验结果；`LocalVlmTaskService.java:254-268` 在首次检测到两个相同动作时重新规划，在下一步仍相同时直接暂停。真实路径为：同一个稳定节点上的“下一页”按钮连续点击三次，每次页面前后核验均为 `SUCCESS`；第 2 次后触发 `action_loop` 重规划，第 3 次后以 `planning_loop_persisted` 暂停。VLM 坐标动作相同、或 Jev 候选的节点与参数不变时均可达到。这样会中断仍在前进的长任务，违反“循环时处理、其余复用”和不降低完成质量的门槛。**最小修复：**将循环判定与已记录的核验结果或前后观察进展关联；连续 `SUCCESS` 且页面确有进展时不得仅凭动作相同而停机，并补一条三次合法重复动作的行为测试。

2. **Jev 改选动作后仍用原 Executor 的完成提示触发重规划。** `LocalVlmTaskService.java:371-374` 先按 VLM 输出取得 `executorSubgoalCompleteHint`，`433-446` 允许 Jev 选出另一候选并替换实际 `command`，但 `575-576` 或 `624-625` 仍把原提示与被改选动作的 `SUCCESS` 核验组合成 `subgoal_completed`。`JevShadow.java:242-250` 只要求原 VLM 动作被候选覆盖、所选 ID 合法，不要求所选候选与原动作相同；因此可达场景是 VLM 提议完成输入并标 `COMPLETE`，Jev 改为点击输入框，点击核验成功后下一步提前重规划，尽管原子目标尚未完成。任务 25 的冻结配对配置明确开启 Jev 受控选择。**最小修复：**在 Jev 实际改选时，对照已记录的 `same_action` / `legal_equivalent_action`；非等价动作清除该完成提示，或对实际子目标另作核验。增加“VLM 标 COMPLETE、Jev 选不同动作且其核验 SUCCESS”用例，确认继续复用当前计划。

## 初审与任务 27 合入时必须核对（任务 25 状态恢复已关闭）

- `LocalVlmTaskService.java:233-243` 只恢复 `roles.history` 和 `planning_plan_step`，而 `pendingPlanningReason`、连续失败次数、循环重规划标记在每次进入运行循环时重置。`verified_step_outcome` 已落盘（`1394-1408`），但尚无从事件恢复调度状态的入口。任务 27 引入手动恢复后，如果上一步已核验 `SUCCESS` 且 Executor 标记子目标完成，恢复可能忽略待执行的 `subgoal_completed` 重规划；一次失败后恢复又可能绕开“两次连续失败停机”。另一个具体边界是 Manager 报 `Finished` 而整体目标核验未通过时，ON 分支先把 `Finished` 和计划步数落盘（`315-365`）；若恢复原样复用该计划，会跳过必要的重新规划。合入时应在新观察、核对和用户确认后，按持久事件重建待处理原因与失败计数，或显式使旧计划失效；恢复 reset 不能无意清掉计划/事件/预算，并用中断后恢复场景验证。此树目前没有 27 的恢复路径，不能据此断言合并后已发生回归。

## 其余审查结论

- 开关由 `LocalTaskStore` 默认 `false` 并在建任务时快照；OFF 的 `shouldSkipManager` 选择路径与 `executorPrompt()` 原正文保留。ON 的初始规划、失败重规划、两步过期、预算门禁和各角色请求仍走同一 `LocalVlmTaskService` 与 `LocalTaskStore`。
- 树核验仍逐动作执行；`UNKNOWN/PENDING` 进入待核对暂停，未把动作回执当成功。计划、调度原因、步骤结果、角色请求和费用沿任务私有记录保存。现有静态审查无法代替任务 25 的真实 API/手机同条件配对验收。
