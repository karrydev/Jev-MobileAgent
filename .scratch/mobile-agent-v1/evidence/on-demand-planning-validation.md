# 25 按需规划真机对照记录

2026-09-25，验收进行中。冻结计划见 [on-demand-planning-plan.md](on-demand-planning-plan.md)。模型统一 gui-plus，Jev选择与树核验开启、影子关闭；只切换按需规划。连接USB准备和取证，不是最终拔线验收。

## 首轮失败

`planning25-off-input01`：1f28e53 / APK SHA-256 `395add0d7cb806ebeb7c00dbaea6c57aed0b7696c78df28a3d05ba906d3a5c21`，中文目标，规划OFF。1次Manager、0次Jev、0动作，¥0.002931。目标输入仍为空；模型响应后 `decision_scene_changed_before_dispatch` 暂停，fresh核对后明确结束CANCELLED，费用不重置。

实际原因：受控页运行状态从单行“已启动”变为两行费用信息，状态栏区域top从2290变2243，滚动区域bottom从2091变2044，开始/暂停/取消按钮上移47像素。新鲜度门禁正确拒绝了几何变化，但变化源自本App的状态排版。修复固定状态区域后采用新编号，原尝试保留；此失败不作为按需规划收益或质量对照。

四项正式配对需使用修复后的同一APK完成；当前不宣称任务25通过，也不宣称调用减少。
