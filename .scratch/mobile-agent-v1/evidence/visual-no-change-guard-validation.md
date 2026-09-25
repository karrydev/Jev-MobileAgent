# 视觉核验不再把静态画面当作事件成功

2026-09-26。真实失败为 c4a718b 的 [off-visual05](planning25-off-visual05.json)：灰色预设按钮已点击，蓝框仍ready，树无可证明后置状态，Jev给FAILURE但置信度不足，VLM把既有蓝边当成新高亮并给SUCCESS。整任务完成检查拒绝，故原自动结果是NEEDS_REVIEW，fresh后CANCELLED；失败不删除。

Luna 实测两组原始PNG：负例App区域1942个不同像素全部位于自身运行状态节点，排除该节点后内容完全一致；正例仍有6992个内容像素变化，位于被选节点外。只比较按钮自身会误拒绝正常的节点外后果，因此实现比较整个可信App内容区。真实输入SHA见[清单](static-border-input-manifest.json)，测试副本删除无关系统通知内容，PNG保持原字节，回调元数据按实际生产schema重建；脱敏和构造边界在test resource README逐项说明。

`VisualChangeGuard`仅在树核验开启后的VLM视觉回退给出事件SUCCESS时接入：可靠同应用、窗口、尺寸与观察/截图关联下，整块App内容像素完全相同才降为UNKNOWN；没有像素阈值，页面有变化也不直接判成功。只屏蔽唯一可信自家运行状态节点。普通观察缺少恢复专用inset时，只接受可追溯SystemUI窗口的唯一精确status_bar资源节点、前后一致且贴顶全宽的边界；其余不比较。原恢复截图策略不变。

局限：不接入legacy tree-OFF Reflector，不改变树规则或Jev直接判定；截图关联/边界不可靠时保持原视觉流程。模型仍可能错误理解发生了变化的内容，整体完成门禁继续独立工作。本检查不是通用视觉真值，也不证明任意点击必有可见变化。

65项定向测试通过：VisualChangeGuardTest 7、LocalTaskControlPolicyTest 28、LocalTaskStoreTest 17、LocalRecoveryControlGateTest 4、TreeActionVerifierTest 9，XML时间2026-09-25T16:33:04Z，零失败/错误。真实miss/hit通过生产frameContext和像素行策略，另覆盖第三方同名状态、身份/尺寸/窗口变化、单像素变化、非事件set_text等边界。Android实际PNG解码和新APK手机路径仍待Luna验收；Sol范围审查和构建结果随后补充。

2026-09-26 最终源码验证：独立 Sol-max 定向审查 CLEAR。取消账本边界收紧后，LocalTaskControlPolicyTest 29 项、LocalTaskStoreTest 17 项通过（2026-09-25T16:42:59Z，零失败/错误）；其余未变的视觉/恢复门禁/树核验 20 项沿用前次通过结果，共 66 项有效定向测试。APK 构建与原位真机验收仍待执行，不能据此关闭票。

真实App生产路径已覆盖负例：8d3bcc5的planning25-on-visual08执行灰色预设按钮，实际蓝框仍ready；VLM回退原本SUCCESS，guard比较2324160个可信App内容像素，前后hash一致，改判UNKNOWN/event_action_success_without_visible_app_change并暂停。它阻止了静态误报，但未修正Manager选错目标；整任务失败保留，25不放行。Android独立真实miss/hit fixture检查另行留证。

Luna补充Android14离线生产代码检查通过：app_process同一ClassLoader加载已安装8d3bcc5 APK（SHA已核对）与只含1个runner类的dex，真实miss为UNCHANGED并将SUCCESS降UNKNOWN，hit为CHANGED并保留SUCCESS；实际经过Base64/BitmapFactory，0 API费用。结果和最小复现源见[Android证据](android-guard-runtime/device-result.json)。这项不冒充普通任务路径；普通任务负例由on-visual08单独证实。
