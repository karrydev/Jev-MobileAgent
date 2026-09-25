# 正常 Agent 跨应用后的恢复验证

2026-09-26，8d3bcc52aa10c86188f8d4b2b2ab09159e55de8e / APK SHA-256 9c66e641c1c61e29889703ff423a8a053074767dafe4f4a58dcd2bad1fc84a1a，OnePlus8T Android14，Luna-max 真机操作。USB仅准备与取证；不替代最终拔线。

正常 Main 页面准备英文目标“Tap the Open Accessibility Settings button. Do not change any permission or setting.”，由通知开始并收起。首个模型滑动因 stale_observation 未派发；下一动作由 Jev 选择真实“打开无障碍设置”按钮，receipt success，页面实际进入 com.android.settings。测试者未代点目标按钮，也未改变设置。

进入设置时观察4262暂时没有可信应用，4263已为Settings；post-action场景门禁保守暂停为 NEEDS_REVIEW/context_or_structure_changed，未把回执当作效果核验。4263在任务日志记录可信运行目标Settings。锁屏再解锁后动作、请求、费用不变，没有自动续跑。

通知重新观察得到4267，recovery_review.valid=true且target_application_package=com.android.settings；两条动作分别NOT_EXECUTED和EXECUTED，后置均UNKNOWN。明确结束时再次读取现场，终态ENDED_WITH_UNRESOLVED，active slot释放，整体结果保持UNKNOWN。历史未重写，结束后Dozing。自动停止、fresh与最终快照分别保存。

3次VLM、1次Jev，标价估算¥0.011538，加Jev保守预留¥0.01，总¥0.021538；全局Jev67/150。证明正常Agent切换后的恢复目标迁移及明确结束，不证明此通用目标自动完成或跨应用始终不中断。完整脱敏字段见[实测记录](recovery27-crossapp02.json)。前一零动作准备偏差和原位取消回归另记[取消验证](no-dispatch-cancel-validation.md)。
