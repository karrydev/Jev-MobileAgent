# 版本化实测索引（最终集成结果待补）

历史结果按运行入口、模型和任务分别保留，不能跨版本直接计算优化收益。费用是标价估算及未知预留，完整失败见统一 live-model-budget.json。

| 阶段 | 固定版本 | 实际结论 | 原始证据 |
|---|---|---|---|
| 上游原版手机入口 | 上游11cea575；文件与提示词哈希在JSON | controlled state目标成功，2请求；原失败尝试保留 | original-baseline-mobile04.json及mobile03 |
| 上游原版AndroidWorld | 上游文件哈希在JSON | SystemBrightnessMax 5步未成功，15请求 | original-baseline-androidworld01.json |
| 提取后参考角色 | source_base 94fb54a；模块哈希在JSON | 同亮度目标5步未成功，15请求 | extracted-baseline-androidworld01.json |
| 裁剪后参考角色 | source_base 170f30c；模块哈希在JSON | 同亮度目标5步未成功，15请求 | pruned-baseline-androidworld01.json |
| 旧桥接App+VLM | 2ed36fb历史实现 | ref01和app03都完成受控任务，7/5请求；需要Python桥 | docs/app-vlm-validation.md、任务19记录 |
| 独立App仅VLM | 373da44；APK800dc859… | 用户物理拔线，中文/蓝框成功，9/5请求 | standalone-baseline.md、standalone-usb-*.json |
| Jev受控选择 | 0d01ec5；APK11959578… | 同APK4项配对均成功；中文Jev实际输入，视觉因候选不足回退 | jev-controlled-validation.md、jev23-*.json |
| 树优先核验 | 8c112d5；APKe76edabe… | 同APK4项配对成功；中文少一次Reflector，视觉开销反增；五类边界另记 | tree-verification-validation.md、tree24-*.json |
| 按需规划 | 8d3bcc5；APK9c66e641…；后续目标约束候选待复测 | 两侧中文和OFF蓝框成功，ON蓝框错误预设被新guard拦截为UNKNOWN；质量门槛未过 | visual-cancel-final-paired-validation.md、request-grounding-validation.md |
| 独立恢复 | 多个精确版本见各JSON，最终修复待集成 | 三种进程窗口、断网、锁屏、撤权和真实重启已留证；锁屏停止及显式恢复已留证，原始触摸覆盖有限；输入旧树缓存已修；8d3bcc5正常Agent跨应用fresh明确结束及零动作取消回归已通过，最终拔线待补 | standalone-recovery-validation.md、recovery27-*.json |

历史主模型为gui-plus-2026-02-26，因免费额度限制，用户明确改用gui-plus；新配对两侧统一gui-plus。模型变化、任务变化和设备通道变化均不能混算为规划/Jev收益。所有受控成功只证明对应目标；AndroidWorld真实失败、未知结果和费用不得从最终报告删除。最终留出及拔线版本尚未完成，不能用上述历史成功替代。
