# 成功回执后的观察关联修复

2026-09-25。范围为成功动作回执之后的 AFTER 取样；不更改动作前现场检查、动作效果判分或整体任务完成条件。Jev 全局调用上限按用户明确授权由 40 提高至 70，已有调用和费用继续累计，单任务 5 次及累计 ¥10、每任务 ¥1、阶段 ¥2 保持不变。

## 真实故障证据

717b174 的 input04 与 visual03 均有实际副作用和成功回执，但最初 AFTER 树仍保留旧文本。随后约 0.4 秒内取得的新树反映真实效果，原现场检查因此暂停。两组观察的屏幕、窗口和 27 个节点结构一致，只存在文本/语义变化。详见 [脱敏复现](post-action-early-sample-reproduction.json) 与 [协议验证](gui-plus-coordinate-validation.md)。通过 fresh 核对完成的历史任务保留原 UNKNOWN，不计为自动运行成功。

## 修复和验证状态

已实现从成功回执起至少 700ms 等待，再获取间隔至少 100ms 的两棵一致纯树；整个 AFTER 流程限 2 秒、最多 3 次额外树采样，等待每 50ms 检查控制。流程，保留初始观察/截图及完整回执，再将最终关联观察交给原 verifier。包名、窗口、几何、节点结构、焦点或可操作性变化仍拒绝；控制代次和迟到事件仍检查。等待不能证明任意未来界面不会变化。

初版 Sol 审查发现：无事件快速路径及过早的两帧一致，仍可能接受迟到更新前的旧树。两条入口属于同一缺口，已交由新 Luna 集中修复，未将存在缺口的 APK 安装用于付费验收。修复后记录定向测试、最终构建哈希、审查结论及四项同 APK 真机配对。

此前全量单元检查运行 141 项，1 项 page_state 比较位置检查失败；修正后受影响 Policy 的 23 项通过。最终等待时序修复后的测试与真机结果待补，不以此记录宣称最终版本全部通过。

最新定向测试：`LocalTaskControlPolicyTest` 24 项通过（原常量检查替换为两项使用生产采样策略的时序行为测试，净增 1）。`postActionSamplerWaitsAndResamplesEvenWhenNoEventArrives` 覆盖无事件仍等待重采；`earlyMatchingOldFramesAfterEventCannotReleasePostActionScene` 覆盖早期两棵旧树不能放行。初始 AFTER 观察与截图也传入回执起剩余时限，耗尽时拒绝继续。`git diff --check` 通过。定向复审、打包和真机结果待补。

Sol 定向复审确认原两条提前接受路径关闭；发现终帧截图保存后缺少截止复查，后由新 Luna 追加 `sampling_deadline_after_screenshot` 拒绝分支。主协调核对该最小补丁：超过总时限会保留终帧 trace、返回既有 deadline 拒绝结果，不进入 verifier。该路径不改变已通过的纯策略测试；最终 APK 重新构建，真机验收继续。
