# 24 阶段实测记录（尚未完成验收）

2026-09-25。源码 `8c112d5`，Debug APK SHA-256 `e76edabec353c38b13ed0071cbf552c3dfce1e164985bf4f9ea4da88d2748b14`。OnePlus8T/Android14，GUI-Plus gui-plus-2026-02-26 与 TypeSafe jev-1.13.0。USB只用于准备和取证，不是拔线验收；凭据沿用App加密配置，未向APK或报告注入Key。

实现与审查见 [审查记录](tree24-review.md)。初版全量75项通过，追加测试后定向24项通过；修复后受影响14项通过，均零失败。构建使用 Android SDK `/Users/liangkairui/Library/Android/sdk`，Gradle `--offline --no-daemon --console plain :app:testDebugUnitTest`（对应测试类过滤）及 `:app:assembleDebug`；`git diff --check`通过。不同阶段测试数不可相加当独立总覆盖数。

## 同版本正式配对

均保持Jev选择开、影子关、原规划频率。初始空输入/蓝框ready，真实任务模型驱动，主协调没有代点目标。

| 用例 | 树核验 | 结果 | VLM/Jev请求 | 截图采集/图片上传 | 秒 | VLM估算+Jev预留CNY |
|---|---|---|---|---|---|---|
| off-input02 | 关 | SUCCEEDED | 5/1 | 3/6 | 14.64 | 0.040552 |
| on-input01 | 开 | SUCCEEDED | 4/1 | 3/4 | 13.40 | 0.032194 |
| off-visual01 | 关 | SUCCEEDED | 5/0 | 3/6 | 13.75 | 0.030555 |
| on-visual01 | 开 | SUCCEEDED | 6/1 | 4/7 | 19.05 | 0.047275 |

中文真实结果精确匹配。开启组中文由tree_rule SUCCESS，省一次Reflector；视觉开启组先拒绝一条stale动作未派发，实际动作规则UNKNOWN、Jev UNKNOWN（0.68）后视觉SUCCESS。正常页面有可访问视觉状态，不能将该样例扩称为所有树证据缺失。各组仅一次，不能证明统计收益；视觉开销反增，不宣称整体提升。

`off-input01`在旧APK d3a5f8c上开关操作失误，实际selection=false，排除配对；8 VLM、¥0.0500805保留在总账与JSON。

## 真实边界及独立标签

- `loading01`：实际只点击一次。初始和wait_1捕获加载中，规则两次PENDING；wait_2树无通用后置条件为UNKNOWN，Jev看到加载完成给SUCCESS（0.85）。等待两次、600ms间隔有界。3 VLM+2 Jev，同一步选择和核验均发送；5采集/3上传。通用Debug终局判据不足，整任务NEEDS_REVIEW；已解析动作允许显式取消释放。
- `neartext01`：LengthFilter(7)使目标八字只显示“独立手机测试成”。四次实际set_text各判FAILURE，无误报SUCCESS；原规划重复直到max_steps暂停。9 VLM+3 Jev，9采集/9上传，显式取消释放；反复失败的规划处理留25。
- `noeffect01`：真实click受理但页面没有可见变化。规则UNKNOWN、Jev UNKNOWN（低置信0.32）、两张真实图片的VLM核验仍UNKNOWN；没有把回执当成功。3 VLM+2 Jev，2采集/4上传。任务NEEDS_REVIEW，显式取消后仍保留未决动作与占用，手机已锁屏。

已运行的实际动作最终标签：SUCCESS 3（中文、视觉、加载）、FAILURE 4（近似输入）、UNKNOWN 1（无效果）；相应独立可见后置条件/证据不足均一致，观察到误报SUCCESS为0。规则中间轨迹另有PENDING 2，不能用最终SUCCESS掩盖等待。树UNKNOWN后的Jev或视觉决定单独记录，非规则判断正确率。

## 尚未验证与合入边界

截图不可用用例还未运行：无效果动作未知后，旧入口不能核对并释放；不能清空记录绕过。保留task `8e7f485f-b810-465b-9b49-5d7a6a25a9a5`作为27恢复输入。Debug视觉结果已从Accessibility隐藏（源码审查），该独立树外变化页面的真机核验仍需补充。

默认树核验开关保持关闭的产品默认值。按协作约定可提前合入未启用模块以让27接入审查后的持久化契约，但24保持in-progress、25不放行；27完成核对入口后补截图失败与Debug树外变化，必要时重做受影响配对。没有未经验证就标done。

每项完整请求、动作、四态、截图采集和usage见同目录tree24-*.json；全量费用/预留见上级live-model-budget.json。所有原始失败和配置无效尝试均保留，实际账单未验证，Jev以CNY预留呈现，不虚构汇率。
