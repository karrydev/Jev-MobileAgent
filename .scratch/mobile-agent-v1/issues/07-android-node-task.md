# 07 — 从 App 发起中文输入任务并控制执行

**What to build:** 用户在 App 输入目标，使用确定性测试策略经服务端完成受控页面的一次节点点击或中文输入；能看到状态并暂停、取消。

Blocked by: 03, 05, 06

Status: ready-for-agent

Execution: done
Owner: luna-android-node-task
Branch: codex/v1-android-node-task
Evidence: 物理手机受控页面
Gate: 从 App 发起中文输入任务并控制执行

## 前置与规格

[03 — 运行中的任务可以暂停和取消](03-pause-cancel.md)、[05 — 旧目标与重复命令不会产生额外动作](05-valid-action-delivery.md)、[06 — 在模拟器中连接 App 并查看当前观察](06-android-observation.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 目标输入、任务状态、节点动作、重新观察与独立页面检查形成完整链路；无需真实模型凭据。
- [x] 中文文本完整且来源可追溯；支持集之外明确拒绝，节点过期、遮挡或权限撤销不猜测执行。
- [x] 在实际 App 通道重测重复命令、单任务限制、暂停与取消后迟到响应，设备副作用符合控制结果。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：03/05/06 已验收；按用户最新要求，后续优先在已授权 OnePlus 8T 真机的项目受控页面测试。领取范围 android-app/、services/android_bridge/、contracts/android/ 和独立 Android 测试；不得修改 04 独占的 sim_loop/contracts/v1。复用公开运行/控制语义，新增真实 App 执行适配。独占真机，所有 ADB 指定设备；系统授权可由 Agent UI 操作，密码等不可代办步骤才请求用户。

2026-09-22：初版构建成功、真机安装指纹 `e926c7b7384174b605b70424795e1ad03dd83e3a9413bf3c0fc22a23b87dc9c2`。中文输入已实跑 obs141→EXECUTED→obs142→SUCCESS/input_applied。协调者复测节点点击 click-3 为 obs246→App 执行→obs247→SUCCESS/postcondition_met；click-2 则 FAILED/stale_observation，未发生点击。Terra 指出任务按钮自身触发的自动观察刷新与绑定动作有竞态，需修复，不以单次成功验收。暂停/取消/重复投递的真机负向仍待验证。代码冻结在独立分支，未合入。

2026-09-22：实现 `280e82e` 已合入。Terra 定向复审关闭自动观察竞态、真实节点身份/窗口遮挡、after 关联及控制迟到路径。协调者复跑 `python3 -B -m unittest tests.test_android_bridge` 13/13，最后迟到回执修复由 Luna 运行扩展后的 14/14，Terra 定向两条通过；Gradle 构建与 `git diff --check` 通过。

物理手机验收：OnePlus 8T / Android 14；最终 APK SHA-256 `d24a96bcda54cb0d36e309cc7ddb33c511973a7ffeaa27dfa7227265348ea821` 与设备一致。相同执行逻辑版本两次点击（obs908→909、922→923）与两次完整中文输入（932→933、955→956）均 SUCCEEDED；最终 UI 修正版重新验证点击。快速暂停/取消均保持页面 ready，暂停后可从 App 取消。故障注入前三次回执上传返回 503、同一 action 共 6 次状态返回，最终仅保留一次执行回执与正确页面效果。动作已执行、回执迟到时：取消保持 CANCELLED/CANCELLED，暂停保持 PAUSED/PAUSED 且第二任务 409 task_active，随后 UI 取消释放占用。没有把取消解释为撤销已发生动作。

原始 JSON、截图和故障注入脚本保留本机 `/tmp/jev-result-*`、`/tmp/jev-post-*`、`/tmp/jev-phone-fault-bridge.py`；仅操作项目受控页面。旧节点、未知目标、中文保真、显式前后观察关联由协议负例及设备端身份/当前窗口检查覆盖。当前是确定性测试策略、USB 调试传输，不代表真实 VLM、多 ROM 或脱离 ADB 验收。

2026-09-22：主树 `bde8ffe` 集成基准：`python3 -B -m unittest discover -s tests` 94/94，`python3 -B -m unittest discover -s eval/tests` 8/8，共 102 项通过。任务 08 后仅需重验相关 Android 集成面，除非发现新的跨模块影响。
