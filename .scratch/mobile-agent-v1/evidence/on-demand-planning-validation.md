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

APK SHA-256 `dfdc4b261b3182be1e2d21907da8feec30e53a32c6d4fbdd039d3db9e7a2ea4c`。精确固定状态区高度后，input03 的六个 RUNNING 观察中状态与控制区 bounds 保持一致，见 [布局证据](runtime-status-layout-validation.json)。

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
