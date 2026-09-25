# 视觉核验目标定位与效果归因修复

2026-09-25。源问题为 b84d1b8 的 planning25-on-visual06：两次实际点击灰色预设按钮，目标蓝框仍 ready；VLM Reflector 把 Agent 底部 RUNNING 状态误读成目标变化，返回 SUCCESS。独立整体目标门禁仍正确拒绝完成。

调用链确认 lastSummary 只是动作描述，executor 明确禁止在该字段描述预期结果，旧 Reflector 却把它标为 Expected behavior。Jev 的 prompt-facing action 只保留候选 ID 与描述，候选绑定节点的真实标签、角色和位置没有送入视觉核验。

本批从绑定节点快照复制标签、角色、屏幕边界及原屏幕尺寸到反思输入；明确其与上传图的比例关系，执行契约和实际坐标不变。核验区分目标应用内容与 Agent 自身运行/费用状态，并区分静态状态目标和点击等事件目标：目标仍可见不能证明事件已经发生，缺证据保持 UNKNOWN。

14项 MobileAgentVlmRolesTest 通过；临时导出测试另1项通过后移除。Sol 定向审查无阻塞缺陷，确认快照、坐标帧、调用顺序、历史/恢复路径；未重复全项目审计。

主协调用同一真实截图对做4个独立host-only请求，gui-plus、max_tokens=1024；不操作手机、不调用Jev。旧提示词复现错误SUCCESS；首版只加强状态归因仍错误SUCCESS，保留为失败。最终补齐目标位置和事件语义后，误点负例UNKNOWN，真实蓝框命中正例SUCCESS。共计标价估算¥0.0198735，均已入全局及规划阶段账本。原图按相同Pillow bilinear路径缩放672×1484；与App Android Bitmap维度一致但不声称字节相同。详情、完整提示词、图片哈希和usage见 [诊断证据](visual-reflector-diagnosis.json)。

这些单次正反例支持本次缺陷修复，不证明任意应用/模型质量，也不替代同最终APK的规划OFF/ON手机验收。25/27仍in-progress，28仍等待。无障碍树旧文字问题独立取证中。
