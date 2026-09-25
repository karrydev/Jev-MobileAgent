# 现场范围与终图事件修复验证（进行中）

基线 `51a8076` / APK源 `2915ed9`。原四项配对及全部费用保留，详见 post-action-scene-validation.md。

红复现使用生产 LocalTaskControlPolicy.samePostActionSceneContextAndStructure：仅非交互SystemUI状态栏9个节点横移1px，目标应用及窗口几何未变，却被拒绝。`LocalTaskControlPolicyTest.postActionSamplingAllowsOnlyDecorativeSystemStatusBarOnePixelDrift` 在修复前失败（AssertionError，测试文件478行）；同批 JevShadowBudgetPolicyTest 通过。命令：`./gradlew --offline :app:testDebugUnitTest --tests com.jev.mobileagent.LocalTaskControlPolicyTest.postActionSamplingAllowsOnlyDecorativeSystemStatusBarOnePixelDrift --tests com.jev.mobileagent.JevShadowBudgetPolicyTest`。

按证据排序验证：1. 装饰状态栏节点精确边界比较引起误暂停（已复现）；2. 每750ms同值状态setText造成额外事件（尚未归因）；3. 截图期间事件是否对应真实目标/窗口变化，需在剩余采样和时限内重新观察判断。不得把尚未证实的事件来源写成结论。

保留独立判分、初始AFTER与receipt、事件/控制代次检查、2秒总时限和3次额外采样。目标节点、窗口身份/几何/层级、焦点、键盘或遮挡变化仍应拒绝；不通过隐藏测试按钮、泄露目标答案或修改判分条件解决模型误点。

Jev总上限已获用户授权150，原46次保留；全部金额上限不变。后续构建、审查和真机证据待补。

调用链核对：既有sameDecisionScene/fingerprint只纳入active package窗口/节点，已有applicationSceneIgnoresSystemStatusNoise相关覆盖；本批不改sceneFingerprint、sameDecisionScene或pre-action gate，仅修动作后结构比较并补一致性负例，避免扩散派发前语义。

修复后定向回归：LocalTaskControlPolicyTest 26项、JevShadowBudgetPolicyTest 1项，均0失败/0错误/0跳过。包含状态栏1px正例，以及更大位移、目标节点几何、窗口属性/几何、交互节点、系统语义、宽系统窗口和新键盘负例；截图事件后需fresh树和其自身终图重新通过，三次采样后不增额。未修改重复状态setText，第二假设未归因。

APK构建：`JAVA_HOME=corretto-17.0.13 ANDROID_HOME=~/Library/Android/sdk ./gradlew --offline --no-daemon --console plain :app:assembleDebug` 成功（13秒）。范围审查与新版真机配对尚待完成，不据离线结果关闭25/27。

Sol-max聚焦审查：无证据性阻塞，可集成进入有界真机验证。派发前场景边界、窗口集合、目标节点结构与控制/时间/采样预算未放宽；150仅改变累计调用门槛，不重置持久计数或费用。截图事件来源仍未归因，旧日志不证明新路径必然成功。25/27/28尚未通过，不以审查替代真机独立判分。

## 352835e 同APK实测

APK SHA-256 `bb1c2d1c42e06079f7447ceda52e02bf2b41186303bd02299b2383b272430fe5`，安装升级保留原46次调用，界面已显示累计150。四项同条件配对如下，人工核对后的结束不计自动成功。

|尝试|自动结束|VLM/Jev|耗时秒|费用与预留 CNY|
|---|---|---|---|---|
|[planning25-off-input06](planning25-off-input06.json)|SUCCEEDED|3/1|13.644|0.0206785|
|[planning25-on-input04](planning25-on-input04.json)|NEEDS_REVIEW|3/2|15.308|0.0318140|
|[planning25-off-visual03](planning25-off-visual03.json)|NEEDS_REVIEW|2/1|11.727|0.0171850|
|[planning25-on-visual05](planning25-on-visual05.json)|NEEDS_REVIEW|2/1|9.821|0.0173920|

中文OFF自动成功。中文ON第一次set_text经树核验SUCCESS：第2次终图遇事件52→53，第3次fresh树与新终图通过，1543ms内完成，证明本次恢复关联修复在真实路径生效；之后原计划仍被复用，Jev再次选择set_text，第二动作在第2次树观察期间事件变化、剩余样本不足而暂停。最终fresh双重观察后COMPLETED_ON_REVIEW，不计自动成功。

蓝框OFF第2次截图遇事件，第3次树匹配但截图不可用（仅保存screenshot_unavailable，缺少平台数值原因），树关联成功但视觉核验保持UNKNOWN并暂停。蓝框ON同样因树观察期间事件导致样本不足暂停。两项实际蓝框仍ready，最终ENDED_WITH_UNRESOLVED；未将点击预设按钮判为整体完成。截图不可用根因未确认，不由154ms间隔直接推断平台限流。

本批总新增5次Jev，当前51/150；按需规划已恢复OFF、选择/树核验ON、影子OFF，无活动任务，手机Dozing。25/27未放行，28仍待依赖完成；停止无变更付费重试，后续只对新证据证明的范围内缺口作离线诊断。
