# 20 — 真实手机中断后可核对并手动恢复

> 2026-09-25 范围说明：本票 done 仅指原 App + Python 设备桥架构的验收，历史证据与复用价值保留。独立 App 的移植及恢复另由 [26](26-app-local-vlm.md)/[27](27-standalone-recovery.md) 验收，本票不证明最终产品已独立运行。

**What to build:** 用户在真实任务断线、任一端重启或手动介入后看到暂停与核对结果，确认后才恢复，不确定的动作不会重放。

Blocked by: 04, 19

Status: ready-for-agent

Execution: done
Owner: luna-physical-recovery
Branch: codex/v1-physical-recovery
Evidence: 真实 API 与物理手机
Gate: 真实手机中断后可核对并手动恢复

## 前置与规格

[04 — 断线和重启后核对并手动恢复模拟任务](04-reconcile-resume.md)、[19 — 用户通过 App 完成仅 VLM 的真实任务](19-vlm-app-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 在发送前、已执行但回执缺失、回执后核验前演练中断，核对设备真实副作用而非仅看服务端日志。
- [x] 验证人工介入、旧连接和迟到模型响应；记录能够检测和仍有限制的介入场景，证据不足保持暂停。
- [x] 手机端与服务端持久化记录可重建恢复资格，重连不自动续跑；明确暂停无法撤销已发生的动作。

- [x] 在本地演练服务启动、健康检查、重启与回退，产出部署配置和安装说明；服务器访问条件尚未提供时也可完成这些准备。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-24：04/19已验收，领取真实手机恢复与本地部署准备。按新版code-this由gpt-6-luna max实现、gpt-6-sol max按实际风险审查；主Agent独占真机/API/Git。复用04恢复语义和当前Android桥，只补双端必要持久记录、核对/显式恢复、旧响应/人工介入保护，不新建通用运行框架。服务器访问与Jev凭据继续延后。

2026-09-24：本地部署 fixture 已完成 start/health/restart/switch/rollback/stop 实际演练，checkpoint 始终保留 PAUSED、health 待核对数为 1；记录见 [local-deployment.json](../evidence/local-deployment.json)。修复预检查 socket 未复用地址导致的 TIME_WAIT 重启误报，真实监听端口仍拒绝启动。回退使用同实现的临时源码副本，仅证明路径切换/记录保留；尚不代表远端部署或真机恢复通过。部署定向审查进行中。

2026-09-24：离线 Python 集成 163 项通过（修复审查项前版本，见 [recovery-offline-integration.json](../evidence/recovery-offline-integration.json)）。Sol 恢复链路审查发现 App 拒绝 SUCCEEDED 恢复终态，以及派发前恢复缺少持久客户端 intent 能力依据；新 Luna 正集中修复，旧客户端/历史不明保持 UNKNOWN。真机仅安装与恢复按钮检查完成，尚未通过完整恢复验收。

2026-09-24：真实 GUI-Plus 派发前中断通过未执行/重启暂停/核对资格，但确认后暴露旧动作被手机 stale_observation 拒绝（presend01，保留失败与费用未知请求）；已用动作代次过滤修复并经 Sol 复审。受控页节点确认按钮差异修复后，receipt02 与 verify01 两种真实手机中断均手动确认成功、无重复点击、ledger 清除（对应 evidence JSON）。manual01 暴露确认前现场未刷新及 node 请求误带 model，正在修复；不据部分通过关闭本票。

2026-09-24：验收完成。实现提交 `34fb858`，最终源代码合入 `5ea6545885bdb769d3134a2137138136d2594518`；后续提交仅补证据文档。最终相关 40 项 Python 回归通过，APK SHA `e7396fb78703474233ca59f1dcd92e3778c636382fdfba0101ae2b4ab6887057`，Sol 风险审查及具体缺陷定向复审通过。

| 验收路径 | 证据与结果 |
| --- | --- |
| 真实 GUI-Plus 派发前中断、服务重启、显式恢复 | [presend02](../evidence/recovery-presend02.json)：重启 PAUSED；核对 NOT_EXECUTED；确认后新动作成功，真实界面 completed；累计 7 次调用，不重置预算 |
| 已执行但回执缺失 / 回执后核验前 | [receipt02](../evidence/recovery-receipt02.json)、[verify01](../evidence/recovery-verify01.json)：实际目标 ready→completed；恢复不重复点击；两次使用修复 fresh-confirm 前 APK，执行历史路径未被后续修复改变 |
| 人工介入 / 取消后释放占用 | [manual03](../evidence/recovery-manual03.json)：旧确认失效；再核对确认成功，目标 SUCCESS 与 Agent NOT_EXECUTED 分开；[manual02 清理复验](../evidence/recovery-manual02.json)：CANCELLED+NOT_EXECUTED 清除本地占用 |
| 旧连接 / 迟到响应 | [旧会话](../evidence/recovery-old-session.json) 409 且状态不变；[late01](../evidence/recovery-late01.json) 真实 App 暂停后释放测试模型响应，无新动作；迟到响应是显式回放，0 次供应商调用 |
| 手机和服务端重启、不确定动作 | [process01](../evidence/recovery-process01.json)：手机 PENDING 记录持久化，重新授权连接核对后 UNKNOWN/不可恢复，保持暂停，无自动操作 |
| 本地部署生命周期与回退 | [部署演练](../evidence/local-deployment.json)：start/health/restart/switch/rollback/stop 通过，同实现源码路径切换，不宣称跨格式迁移或远端无 ADB 已验证 |
| 最终相关检查 | [40 项回归与文件哈希](../evidence/recovery-final-checks.json)；前一轮 163 项全集证据保留，后续只重跑受影响测试 |

限制：仅 OnePlus 8T Android 14 的受控页面；不能检测“变更后又恢复成相同可观察状态”的所有外部副作用。强制停止 App 会在该 ROM 关闭无障碍服务，需重新授权；初始页面重建不能证明历史动作未执行。process01 测试记录有意保留暂停，服务停止、手机锁屏；未知用量按未知保留。尚未完成真实 Jev、远端部署和脱离 ADB 验收。失败的 presend01、receipt01、manual01/02 原始结论保留，没有覆盖为通过。
