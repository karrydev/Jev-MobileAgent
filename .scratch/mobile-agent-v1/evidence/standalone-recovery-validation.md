# 27 独立 App 恢复实测记录

2026-09-25，尚未完成全部验收。OnePlus 8T / Android14；手机内编排、直连供应商HTTPS，USB用于准备和取证。本报告不是拔线验收。模型费用为标价估算加未知请求预留，非账单实扣。

## 已覆盖

| 场景 | 实际结果 | 证据 |
|---|---|---|
| 旧24已执行但效果未知 | fresh核对后显式结束为 ENDED_WITH_UNRESOLVED；原动作、请求和费用逐项不变，零新增API | recovery27-zero-call-gates.json |
| 核对后现场改变 / 确认期间锁屏 | 原确认失效；锁屏阻止提交。锁屏发生在窗口等待阶段，不能替代截图回调后竞态验证 | 同上 |
| intent已落盘、派发前进程中断 | 首动作NOT_EXECUTED，目标为空；重开不自动续跑，fresh核对和明确恢复后真实输入成功 | recovery27-before-dispatch01.json |
| 聚焦回执后中断 | EXECUTED回执保留、后置条件UNKNOWN；明确结束保留未决效果。此首轮不是文字输入窗口 | recovery27-after-receipt01.json |
| 真实文字输入回执后、核验前进程中断 | 重开目标真实文字存在，回执EXECUTED、核验仍未知；两次fresh目标核验和明确确认后COMPLETED_ON_REVIEW，不增加动作/请求，不改历史 | recovery27-after-receipt02.json |
| 模型请求期间断网 | usage_unknown/network_error暂停；恢复网络和解锁不自动续跑。fresh核对后显式恢复，真实目标完成，未知¥0.016896预留仍在 | recovery27-network01.json |
| 模型请求期间锁屏 | screen_locked暂停，无动作派发；解锁不自动执行，原请求后得到403但未知费用保留 | recovery27-lock01.json、recovery27-lock-permission-reboot.json |
| 暂停期间系统UI撤销/重授无障碍 | 撤销后恢复核对明确拒绝，重新授权不自动续跑。不是RUNNING状态撤权触发停止的证据 | recovery27-lock-permission-reboot.json |
| 运行中系统UI撤销无障碍 | 实际Manager请求在途时撤权，accessibility_permission_lost暂停；迟到HTTP200仅结算usage，零Executor/动作；重新授权不自动续跑，fresh核对后显式结束 | recovery27-permission01.json |
| 手机真实重启 | boot_id变化，重启前后任务/动作/请求/费用保留；解锁和重开App不自动续跑；fresh核对后显式结束CANCELLED | 同上 |

d48e0d8 APK：3ca2bc9b93232b34b1687e5aa109cf26059bf32ec54a8bcc555bb7ed47c2c8e3，用于零调用门禁、派发前、聚焦回执和网络。d54ad05 APK：e3b2f049fc22f80dda3e59161930a25090982d9699decaaa0fd162884d37697d，Debug两个执行后注入点仅匹配真实set_text，用于文字回执后、锁屏、权限与重启。

## 模型额度与切换

日期版 gui-plus-2026-02-26 的回执前窗口首轮在模型请求阶段即HTTP403，动作0、中断点仍armed，不能视为覆盖（recovery27-after-sideeffect01.json）。单次独立诊断确认 AllocationQuota.FreeTierOnly 免费额度耗尽（recovery27-provider-probe01.json）。用户随后明确要求改用 gui-plus；单次真实诊断HTTP200，usage1302输入/16输出，估算¥0.002025（recovery27-provider-probe02.json）。诊断不是手机任务验收，全部请求与未知预留计入同一总账。

App已通过UI保存 gui-plus，精确模型费率白名单补丁已构建安装，并通过真实输入恢复任务。官方当前两模型输入¥1.5/百万token、输出¥4.5/百万token：[价格与模型说明](https://help.aliyun.com/zh/model-studio/gui-automation)。不修改既有日期版记录，不把模型差异归因为调度收益。

## 仍需完成

- 回执落盘前窗口已在 gui-plus / 076008a 新编号 after-sideeffect02 补齐：真实输入存在，durable intent pending 且 result 缺失；fresh review执行事实UNKNOWN，禁止恢复/普通结束，仅双次目标VERIFIED后的明确完成可释放，历史action/request逐项不变。3次模型请求，估算¥0.0113115。首轮403保留。
- 人工介入、恢复代次边界的剩余真实证据。运行中撤权及迟到HTTP200已由 permission01 覆盖，1次请求估算¥0.002913。
- 跨应用A→B恢复目标绑定与输入光标闪烁误拒：前者当前记录首次应用，后者真实确认仅369个caret像素变化仍被拒绝。正在定向修复，不能写为已解决；网络01通过明确重开页面去焦点、重新核对完成。
- 修复后定向复核、25/27集成与最终当前APK物理拔线恢复；后者需用户物理操作，未用USB连接状态冒充。

所有阶段结束或失败后锁屏；预算和任务历史不清零。25、27、28各自保持尚未满足的验收条件，不由构建/单测/单个成功目标代替完整交付。

076008a 隔离构建通过 LocalVlmBudgetPolicyTest 2/2、assembleDebug；APK bcda5d67444025546c263945488b0231aac9898c3b023378794dd9cbb8fe6a39 已安装并完成上述回执前窗口。此APK不含进行中的跨应用/光标恢复修复。

manual01：模型Manager在途时通过ADB触摸输入框，1.5秒后仍RUNNING；安全锁屏才触发screen_locked，后续核对并显式结束。零动作，1次请求¥0.002949。此项不是人工介入自动停止通过，也不冒充物理手指触摸，缺陷与实际事件覆盖边界进入定向修复（recovery27-manual01.json）。

manual01范围补充：夹具启动前已聚焦输入框，且关闭聚焦软键盘；再次触摸可能没有语义现场变化。因此不能据此判定“现场改变后旧动作会被派发”，也不把原始触摸无事件直接等同于已发生不安全动作。后续将用实际页面切换覆盖新鲜度门禁。
