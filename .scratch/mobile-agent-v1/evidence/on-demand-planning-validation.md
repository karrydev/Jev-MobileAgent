# 25 按需规划真机对照记录

2026-09-25，验收进行中。冻结计划见 [on-demand-planning-plan.md](on-demand-planning-plan.md)。模型统一 gui-plus，Jev选择与树核验开启、影子关闭；只切换按需规划。连接USB准备和取证，不是最终拔线验收。

## 首轮失败

`planning25-off-input01`：1f28e53 / APK SHA-256 `395add0d7cb806ebeb7c00dbaea6c57aed0b7696c78df28a3d05ba906d3a5c21`，中文目标，规划OFF。1次Manager、0次Jev、0动作，¥0.002931。目标输入仍为空；模型响应后 `decision_scene_changed_before_dispatch` 暂停，fresh核对后明确结束CANCELLED，费用不重置。

实际原因：受控页运行状态从单行“已启动”变为两行费用信息，状态栏区域top从2290变2243，滚动区域bottom从2091变2044，开始/暂停/取消按钮上移47像素。新鲜度门禁正确拒绝了几何变化，但变化源自本App的状态排版。修复固定状态区域后采用新编号，原尝试保留；此失败不作为按需规划收益或质量对照。

四项正式配对需使用修复后的同一APK完成；当前不宣称任务25通过，也不宣称调用减少。

## 9633ea3 布局修复后的尝试

两处状态区固定3行后，正常页 off-input02 已能通过费用更新并执行模型动作：10次VLM、1次Jev，估算和预留共¥0.0493435。模型点击坐标[129,696]未聚焦输入框，视觉Reflector误判聚焦成功；之后4步动作验证拒绝，达到5步上限暂停。实际输入仍空，不宣称完成。fresh核对后结束CANCELLED。后续generic invalid历史没有保存具体异常与原始拒绝响应，不能断言为JSON格式错误。

on-input01准备时协调者误开树核验Debug夹具，目标为延迟加载，不属于有效中文ON对照。零模型/零Jev/零动作，费用0；尚未请求就因本App状态节点字体度量差异145→146px暂停，fresh核对后结束。该准备失误和1px布局尾差均记录，修复精确状态区域高度后正常页以新编号开始。

## bec1606 同 APK 四项对照

APK SHA-256 `dfdc4b261b3182be1e2d21907da8feec30e53a32c6d4fbdd039d3db9e7a2ea4c`。精确固定状态区高度后，input03 的六个 RUNNING 观察中状态区 bounds 保持一致，见 [布局证据](runtime-status-layout-validation.json)。

| 尝试 | 规划 | 实际结果 / 试验停止原因 | VLM / Jev | 步 / 动作 | 试验耗时秒 | 费用含预留元 |
| --- | --- | --- | --- | --- | --- | --- |
| [off-input03](planning25-off-input03.json) | OFF | 输入为空；max_steps_reached | 10 / 1 | 5 / 1 | 35.286276 | 0.0492055 |
| [on-input02](planning25-on-input02.json) | ON | 输入为空；repeated_action_failures | 10 / 2 | 5 / 2 | 35.740105 | 0.0616175 |
| [off-visual01](planning25-off-visual01.json) | OFF | 蓝框仍为 ready；overall_goal_not_verified_after_executor_finished | 8 / 3 | 3 / 2 | 26.591111 | 0.0615165 |
| [on-visual01](planning25-on-visual01.json) | ON | 蓝框仍为 ready；同上 | 9 / 5 | 4 / 3 | 32.863560 | 0.0878975 |

四次均经 fresh 核对后由协调者明确结束为 CANCELLED，未核实整体目标完成；模型逐步 SUCCESS 的历史未被改写。耗时使用试验首次停止时记录，不混入人工核对结束的时间。ON 输入记录了3次计划更新、2次复用及动作异常后重规划；ON 蓝框 Manager 2次、Executor 4次、Reflector 3次。失败样本不能证明任务25放行或规划收益。

## gui-plus 协议缺口与后续边界

[百炼官方说明](https://help.aliyun.com/zh/model-studio/gui-automation)明确两版系统提示不能共用：日期版移动端示例使用0–1000坐标，精确 gui-plus 示例使用处理后图像宽高与像素坐标。现有 Android 路径统一提示并换算0–1000，未实现模型专属上传图像/坐标适配。这是已确认的协议适配缺口，尚不能据上述失败评价 gui-plus 的固有能力。

输入任务历史中的[120,290]被换算为屏幕[129,696]；蓝框任务[486,990]被换算为[525,2376]，实际目标未被命中。这些坐标仅是失败证据，不据此猜测缩放或拟合目标答案。后续修复应明确上传图像尺寸、坐标单位和到原屏幕的映射；保留原观察身份及安全核对，日期版原协议保持独立。

适配改变模型输入与动作映射，修复后必须采用新尝试编号与新 APK；上述原始配对、失败及费用继续保留。截至本轮四项结束，Jev 已用35/40次，只余5次；累计费用仍按全局总账计算，不能仅以当前阶段费用或手机累计值替代。任务25保持进行中。

## 717b174 协议修复后的有界验证

[适配说明](gui-plus-coordinate-validation.md)记录实现、135项单元测试与Sol范围审查。

`planning25-off-input04` 的名称原意为OFF，但任务快照实际ON；协调者在滚动后未验证开关状态就启动，不作为OFF配对。代码中的开关会立即保存，不需要另点保存VLM。2次VLM、1次Jev、¥0.017248。VLM动作映射匹配中文输入框候选，Jev改选set_text，实际目标文本写入；动作后的早采样旧文本和稍后回显更新被场景门禁拦截，NEEDS_REVIEW，0个完成步骤。fresh两次核对后明确结束COMPLETED_ON_REVIEW，保留原动作EXECUTED/后置UNKNOWN。这证明图片适配实际调用与真实输入发生，不证明全自动完成或独立VLM坐标点击。

`planning25-on-visual02`，同APK，实际ON，7次VLM、4次Jev、¥0.0693325。执行的两次动作均为Jev候选“视觉手势目标”预设按钮，蓝框仍ready；VLM Reflector两次误报SUCCESS，整体完成门禁拒绝，fresh核对后CANCELLED。没有实际coordinate_tap蓝框证据，不能宣称协议修复已恢复全部任务质量。

两项新请求元数据均记录原1080×2400、上传672×1484及原观察关联；Android PNG缩放/编码和真实HTTP200路径已发生。Jev累计40次后停止相关调用，用户随后明确提高到70次；累计费用含预留¥3.3533675，原历史不重置。动作后短暂更新误暂停继续作为范围内缺陷修复，任务25与最终拔线验收仍未通过。

`planning25-on-visual03-vlm-only` 是独立VLM诊断，不属于正式25配对：Jev选择/树核验/影子均关闭，规划ON，源及APK仍717b174。7次VLM、0次Jev、¥0.0290955。前2次返回[486,990]并按672×1484映射到屏幕[781,1601]，确实点到预设按钮；第3次屏幕[540,1010]由VLM直接派发，真实蓝框变为`coordinate tap completed`。成功receipt后又因动作期正常文本回显的AFTER采样不稳定而NEEDS_REVIEW；fresh两重核对后COMPLETED_ON_REVIEW。此结果证明gui-plus坐标适配后可直接命中真实视觉目标，也保留前两次选错目标和自动核验中断，不宣称一击成功或整体自动完成。Jev已用仍40，费用含预留累计¥3.382463。

## 2915ed9 同 APK 四项配对

APK SHA-256 `d7bc17eaa45b7a3351152e4885b16367a193ad79cc20e9c906b9faaae92afb00`。选择ON、树核验ON、影子OFF；每次开始前从手机偏好读回规划开关和全局次数。

|尝试|自动结束|VLM/Jev|耗时秒|费用与预留 CNY|
|---|---|---|---|---|
|[planning25-off-input05](planning25-off-input05.json)|SUCCEEDED|3/1|13.506|0.0207295|
|[planning25-on-input03](planning25-on-input03.json)|NEEDS_REVIEW|2/1|9.750|0.0172480|
|[planning25-off-visual02](planning25-off-visual02.json)|NEEDS_REVIEW|2/1|9.597|0.0172975|
|[planning25-on-visual04](planning25-on-visual04.json)|NEEDS_REVIEW|4/3|18.941|0.0465090|

中文OFF实际文本正确、tree_rule及整体目标通过。中文ON已输入，但终图期间新事件使采样配额用完；fresh双重核对后COMPLETED_ON_REVIEW，不计自动成功。两项蓝框均只点击了预设按钮，蓝框实际仍ready；OFF因终图事件耗尽采样、ON第二动作因结构变化暂停，最终均ENDED_WITH_UNRESOLVED。ON第一次按钮动作的VLM Reflector SUCCESS不等于蓝框或整体目标成功。

ON蓝框初始obs3929与采样obs3930：screen/windows/page_state及目标App节点保持一致，仅系统状态栏9个节点横坐标移动1px，触发严格结构拒绝。另两项event_during_screenshot的事件来源没有日志，不能确定为人工变化或App状态刷新。源码ControlledPageActivity每750ms重复setText可产生无效刷新，但当前仅为待验证因素，未据此改门禁或放宽场景一致要求。

本批未满足冻结质量放行，25保持in-progress、按需规划恢复默认OFF，27/28最终独立运行仍未完成；不宣称优化收益，不用fresh确认替换原始自动结果。所有失败和费用保留，不继续无源码/环境变化的付费重试。完成清理后无活动任务，手机Dozing。OFF蓝框在自动运行结束后的清理期间曾误触目标字段打开键盘；随后关闭键盘再fresh核对并结束，未改变已冻结trial-end结果或蓝框状态。
