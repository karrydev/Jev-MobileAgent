# 27 恢复风险审查

2026-09-25，独立 Sol-max 固定审查 f9109ea（基线6a9e980），聚焦恢复持久化、通知入口、确认中断与预算边界。Luna已有24项定向测试通过和APK构建成功；以下缺陷说明离线通过不能代替真机入口。

## 首轮阻塞

1. 恢复 review/confirm 传 `RECOVERY`，底层本地截图守卫只接受 BEFORE/AFTER，所有恢复截图必失败。主协调首轮真机复核已得到 observation_mismatch；未产生复核记录，未新增模型费用。
2. 通知service action未收起通知栏或校验目标窗口，就立即观察与截图。实拍确认shade仍覆盖；放开截图类型后仍不能把系统通知栏当目标复核。复核、确认均须返回原目标并fresh检查，不得启动MainActivity销毁目标。
3. reviewRunning时暂停/锁屏只设置controlCommand；确认截图成功回调之后未重新检查便提交恢复，finally启动循环又会清控制，存在吞暂停竞态。复核保存及确认提交均须受有效代次与中断保护。

另外整PNG哈希受光标/时钟影响可能拒绝合法确认，主协调已有静止目标六图四hash证据；作为同一入口可用性风险修复并真机复核。没有扩展其他理论阻塞。三项已集中交新Luna修复，未据首轮测试宣称27完成。

## 46a9c6c 定向复审与真机第二轮

12项窗口/中断门控定向测试及APK构建通过。真机实际通知入口仍返回target_window_unavailable，未生成复核，费用不变。ROM的无障碍窗口列表只有全屏active/focused TYPE_SYSTEM、title=null，不暴露被遮住的目标窗口；要求hasTargetApplicationWindow与NotificationShad名称的前提不适用于该ROM。独立分支集中修正识别前提，保留专用dismiss后严格fresh target校验；不以手动收shade绕过产品入口。原截图类型与暂停门控继续定向复审。

46a9c6c定向静态复审确认RECOVERY在采集与存储入口均已接纳；runIfCurrent及提交后启动前的generation检查关闭原pause竞态路径，仍待真机注入。新增限制：当前target_application_package来自首次有效application观察；跨应用A→B后在B中断的恢复未覆盖且可能要求返回A。本轮单应用受控演练不得外推跨应用恢复成功，最终报告保留该限制。

## c53d687 集成及第三轮入口

9f3ff46窗口补丁与79ff5d8独立夹具合成c53d687，APK cc490e8c5754a7fec6e6747d672aba8df4fa9278dad62a278e02d1a7157bd08e。第三轮真实通知操作已由产品收起shade并显示原页面，但复核仍target_window_unavailable，尚无新RECOVERY截图或review。随后只读dumpsys确认目标application window active/focused、全屏，状态栏103px。未将“返回页面”当恢复通过；需窗口过渡有界等待并记录失败元数据，保留该次零费用尝试。

## e031bed 第四轮入口诊断

全套 Android 单元测试97项通过。真机通知栏已收起，2.5秒有界等待仍拒绝复核；新增元数据明确显示目标 AVAILABLE、active/focused，拒绝原因是 target_window_covered。位于顶部的匿名 TYPE_SYSTEM 状态栏为1080×103px，被错误视为覆盖目标；继续延长等待不能解决。下一补丁须使用平台真实 system bar insets 同时修正遮挡判断与截图裁切，保留其他覆盖窗口拒绝。此轮模型调用、Jev次数及费用均不变，未生成复核，详见 review04 JSON。

## d48e0d8 真机零调用门禁通过

14项定向测试和APK构建通过。WindowMetrics实际返回top=103/bottom=48，真实通知入口生成fresh复核。人工点击视觉面板后旧确认失效；确认开始约238ms时锁屏阻止提交（只覆盖目标窗口等待阶段，不冒称截图后回调竞态）。解锁未自动运行，fresh复核后明确结束为ENDED_WITH_UNRESOLVED，active_task_id释放；原actions与requests逐项相等、UNKNOWN效果及费用原样保留。全程VLM/Jev新增0、费用新增0。证据见 recovery27-zero-call-gates.json。
