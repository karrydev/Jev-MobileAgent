# Jev 改变动作后的按需规划交接修复

2026-09-25。前置真机结果保留于 [352835e 配对](scene-scope-repair-validation.md)。中文 ON `planning25-on-input04` 首次 Executor 提议 tap，Jev 实际选择 set_text，独立树核验 exact_text SUCCESS；策略抑制了原动作的完成提示，却未把 different_action 传给规划决策，留下空 pending reason，随后复用旧计划并重复输入。第二动作观察不稳定导致暂停是独立问题，本修复不扩大采样预算。

红测试：`JAVA_HOME=corretto-17.0.13 ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests com.jev.mobileagent.OnDemandPlanningPolicyTest --offline --no-daemon --console plain`，13项中1项失败，预期 `jev_action_changed` 实际空。初次未提供 SDK 的命令只在配置阶段失败，不计红复现。

已按排序核查三种解释：实际选择关系丢失（调用链及红测证实）；仅协议名不同而非计划变化（现场 tap 与 set_text 及实际文本副作用反驳）；第二动作现场不稳定单独导致暂停（成立，但不能解释第一次成功后为何重复旧计划）。

修复只在 SUCCESS + Jev已选 + different_action 时设置独立的计划失效理由 `jev_action_changed`，交接及持久规划事件使用同一输入；该理由不代表子目标或整体完成。UNKNOWN/PENDING仍暂停，FAILURE沿用action_exception，相同动作与未使用Jev不强制重规划，规划OFF原调度不变。补充现场executorComplete=false及true两种不同动作正例，保留同动作/no-Jev/UNKNOWN/FAILURE负例。

同条定向命令修复后13项全过，0失败/错误/跳过。APK构建、Sol范围审查及新版真机证据待补；本文件不关闭25/27/28。

主协调构建 `:app:assembleDebug --offline --no-daemon --console plain` 成功（9秒），并核对13项测试XML均通过。尚未安装新版或调用模型。

Sol-max定向审查无阻塞：两条成功核验路径、pending reason与持久scheduler state一致；OFF、相同动作、未选Jev、UNKNOWN/PENDING、FAILURE及恢复语义保持原规则。允许集成，手机实测尚待补。
