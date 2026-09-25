# 8d3bcc5 修复后的同 APK 规划对照

2026-09-26，Luna-max 独占 OnePlus8T Android14，原位升级 APK SHA-256 `9c66e641c1c61e29889703ff423a8a053074767dafe4f4a58dcd2bad1fc84a1a`，源码 `8d3bcc52aa10c86188f8d4b2b2ab09159e55de8e`。gui-plus / jev-1.13.0，选择ON、树核验ON、影子OFF，仅规划调度变化；USB准备与取证，不是拔线验收。严格按中文OFF、中文ON、蓝框OFF、蓝框ON运行，四项自动结果已取得，最后一项未完成，清理与证据归档继续。

|试次|自动结果|VLM / Jev|自动耗时秒|费用含预留 CNY|实际效果|
|---|---|---|---|---|---|
|[off-input10](planning25-off-input10.json)|SUCCEEDED|3 / 1|18.878007|0.0208240|目标框与回显均为独立手机测试成功|
|[on-input07](planning25-on-input07.json)|SUCCEEDED|3 / 1|14.845014|0.0209155|目标框与回显均为独立手机测试成功|
|[off-visual06](planning25-off-visual06.json)|SUCCEEDED|3 / 1|14.535445|0.0206170|coordinate_tap(540,1010)，蓝框实际完成|
|[on-visual08](planning25-on-visual08.json)|NEEDS_REVIEW|3 / 2|15.251637|0.0325865|错误点击灰色预设；蓝框仍ready|

两侧中文各执行一次set_text，Manager2/Executor1。ON在Jev选择different_action且核验SUCCESS后以jev_action_changed重新规划，没有重复输入；此对照没有减少模型调用，单次耗时差不能外推性能收益。全部自动停止快照先冻结，之后锁屏并导出；失败和后续核对将分别记录。

取消与跨应用恢复另见[取消回归](no-dispatch-cancel-validation.md)和[正常跨应用](normal-crossapp-recovery-validation.md)。当前记录不表示25已通过或28已解锁。

蓝框OFF由Jev直接SUCCESS，未进入VLM视觉回退守卫；不将此试次当作该分支的Android覆盖。前三项新增费用与预留¥0.0623565，阶段累计¥0.990064，第四项仍需独立预算检查。

第四项真实生产守卫已触发：VLM原SUCCESS，整个可信App内容在排除唯一自家运行状态区域后像素hash完全相同（2324160像素），守卫降为UNKNOWN/event_action_success_without_visible_app_change并暂停。修复避免静态误报，但未使任务完成；本批ON出现错误动作和质量下降，不符合冻结放行，25保持in-progress。错误已出现在Manager初始计划，丢失原请求的颜色/视觉限定；此前OFF也发生过同类错误，不能仅凭本样本归因于按需调度。范围内通用目标约束修复继续按Luna-max/Sol-max执行。

第四项已fresh核对并明确结束为ENDED_WITH_UNRESOLVED，原自动NEEDS_REVIEW仍单独保留。四项新增费用与预留¥0.0949430，规划阶段累计¥1.0226505；剩余¥0.9773495不足现有每任务¥1完整预留，后续真实复测尚未获准启动。累计账本¥3.998464，Jev72/150，原金额上限没有提高。
