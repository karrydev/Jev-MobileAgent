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

## 补充边界与阶段放行

27恢复入口的零调用复核已释放旧任务。2026-09-25补测使用源码d48e0d8、APK 3ca2bc9b93232b34b1687e5aa109cf26059bf32ec54a8bcc555bb7ed47c2c8e3；只补先前缺失边界，不混入8c112d5同版本正式配对。树核验、选择与正常规划代码保持原版本行为，新增恢复路径只在暂停后的显式操作中运行。

- `purevisual01`：蓝框和视觉结果均不进入树，真实coordinate_tap后屏幕显示完成。规则UNKNOWN、Jev UNKNOWN（0.68）、真实前后图的VLM SUCCESS；4 VLM/1 Jev，费用及预留¥0.0339535。整体目标通用判据仍UNKNOWN，NEEDS_REVIEW，不虚报整任务成功。经fresh复核后明确结束CANCELLED，保留逐步SUCCESS与全部费用。
- `failshot01`：首个stale动作未派发；第二个真实坐标点击后，一次性Debug AFTER故障生效，真实BEFORE仍保留。规则UNKNOWN、最终screenshot_unavailable UNKNOWN并NEEDS_REVIEW，没有发无图视觉核验、没有把回执当SUCCESS；3 VLM/0 Jev，¥0.0167805。fresh复核后结束为ENDED_WITH_UNRESOLVED，不改UNKNOWN历史。

新增视觉边界针对正常visual页面仍有可访问状态这一证据缺口；原首批用例、配置失误及补测均独立编号，全部纳入同一¥2阶段预算，阶段累计费用及预留¥0.410456。累计Jev23/40，实际供应商账单尚未验证。

实际已派发动作最终标签累计SUCCESS 4、FAILURE 4、UNKNOWN 2；规则中间PENDING仍为2。截图失败例的真实副作用已发生，但产品缺少后图时正确弃权，不将UNKNOWN视为事实失败。stale未派发动作另列，不能加入已执行混淆表。受控样本观察到误报SUCCESS为0；样本小、视觉开销反增，不宣称普遍性能收益。正式同版本配对与五类边界齐全，24阶段完成，产品默认仍关闭；25可按依赖领取。27完整恢复/拔线和28最终集成仍待验收。

每项请求、动作、四态、截图采集/上传与usage见tree24-*.json；费用及预留见live-model-budget.json。USB用于准备和取证，此处不是拔线验收。
