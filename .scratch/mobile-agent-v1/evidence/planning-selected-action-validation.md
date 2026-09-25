# Jev 改变动作后的按需规划交接修复

2026-09-25。前置真机结果保留于 [352835e 配对](scene-scope-repair-validation.md)。中文 ON `planning25-on-input04` 首次 Executor 提议 tap，Jev 实际选择 set_text，独立树核验 exact_text SUCCESS；策略抑制了原动作的完成提示，却未把 different_action 传给规划决策，留下空 pending reason，随后复用旧计划并重复输入。第二动作观察不稳定导致暂停是独立问题，本修复不扩大采样预算。

红测试：`JAVA_HOME=corretto-17.0.13 ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.jev.mobileagent.OnDemandPlanningPolicyTest --offline --no-daemon --console plain`，13项中1项失败，预期 `jev_action_changed` 实际空。初次未提供 SDK 的命令只在配置阶段失败，不计红复现。

已按排序核查三种解释：实际选择关系丢失（调用链及红测证实）；仅协议名不同而非计划变化（现场 tap 与 set_text 及实际文本副作用反驳）；第二动作现场不稳定单独导致暂停（成立，但不能解释第一次成功后为何重复旧计划）。

修复只在 SUCCESS + Jev已选 + different_action 时设置独立的计划失效理由 `jev_action_changed`，交接及持久规划事件使用同一输入；该理由不代表子目标或整体完成。UNKNOWN/PENDING仍暂停，FAILURE沿用action_exception，相同动作与未使用Jev不强制重规划，规划OFF原调度不变。补充现场executorComplete=false及true两种不同动作正例，保留同动作/no-Jev/UNKNOWN/FAILURE负例。

同条定向命令修复后13项全过，0失败/错误/跳过。APK构建、Sol范围审查及新版真机证据待补；本文件不关闭25/27/28。

主协调构建 `:app:assembleDebug --offline --no-daemon --console plain` 成功（9秒），并核对13项测试XML均通过。尚未安装新版或调用模型。

Sol-max定向审查无阻塞：两条成功核验路径、pending reason与持久scheduler state一致；OFF、相同动作、未选Jev、UNKNOWN/PENDING、FAILURE及恢复语义保持原规则。允许集成，手机实测尚待补。

## b84d1b8 真机配对

APK SHA-256 `f17c1d8ab039031962f1ff5b40216313e64190ce71297cdf1052aa094d3d9caa`，安装升级保留51次调用。选择/树核验ON、影子OFF，只有规划开关变化。

|尝试|自动结束|VLM/Jev|耗时秒|费用与预留 CNY|
|---|---|---|---|---|
|[planning25-off-input07](planning25-off-input07.json)|NEEDS_REVIEW|4/1|18.843|0.0254995|
|[planning25-on-input05](planning25-on-input05.json)|NEEDS_REVIEW|3/1|14.054|0.0210430|
|[planning25-off-visual04](planning25-off-visual04.json)|SUCCEEDED|7/3|31.347|0.0584340|
|[planning25-on-visual06](planning25-on-visual06.json)|NEEDS_REVIEW|7/4|29.275|0.0692410|

两项中文均实际写入目标文字，但动作后稳定树仍显示原提示“请输入中文”，tree_rule FAILURE；Manager/Executor随后自述完成时，整体目标判分仍拒绝，fresh双重核对后才COMPLETED_ON_REVIEW。本批ON没有走SUCCESS/jev_action_changed分支，不能声称该分支已获真机证明。旧树的缓存或更新延迟尚未归因；普通capture无显式节点refresh，而执行前绑定校验有refresh，只是进一步诊断线索，不是已证实根因。

蓝框OFF首次点击预设按钮，随后回退VLM坐标动作[540,1010]实际命中，整体目标核验SUCCEEDED，独立状态coordinate tap completed。蓝框ON两次点击预设按钮，记录plan_current复用与plan_expired重规划；逐步SUCCESS不等于目标达成，整体门禁拒绝模型answer，最终fresh核对后CANCELLED，蓝框仍ready。ON未达到同条件质量要求，保持默认OFF，不宣称节省调用或通过25。

本批新增9次Jev，当前60/150。所有失败、费用和人工核对记录保留；25/27仍in-progress，28依赖未解除。现场已无活动任务，选择/树核验ON、影子/规划OFF，手机Dozing。后续先诊断读取旧树与模型误选目标的原因，禁止无变化重复付费尝试或降低冻结判分。
