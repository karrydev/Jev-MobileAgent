# 新鲜观察修复后的同 APK 对照

2026-09-26；源码 `c4a718b2fe532eb79e4c7fc3963ad04e2f51cc1e`，APK SHA-256 `3094239aec5da8c162ce4eb1a0da2ff6d3c415d3a2ed7cf035b5e92d835a937b`。OnePlus 8T / Android14 API34，gui-plus / jev-1.13.0，选择与树核验ON、影子OFF。每次预检读回规划开关，初始输入为空、蓝框ready；手机内HTTPS闭环，USB仅准备取证。本批实际顺序为输入OFF、输入ON、蓝框ON、蓝框OFF，与原方案最后两项顺序不同；没有改动两侧配置或初始页面，不隐瞒顺序差异。

|尝试|自动结果|Manager/Executor/Reflector|Jev|试验秒|费用含未知预留 CNY|
|---|---|---|---|---|---|
|[off-input09](planning25-off-input09.json)|SUCCEEDED|2/1/0|1|29.981430|0.0208540|
|[on-input06](planning25-on-input06.json)|SUCCEEDED|2/1/0|1|13.841825|0.0209470|
|[off-visual05](planning25-off-visual05.json)|NEEDS_REVIEW|2/1/1|2|17.338443|0.0360440|
|[on-visual07](planning25-on-visual07.json)|SUCCEEDED|1/2/0|1|13.707526|0.0215905|

两侧输入均只执行一次set_text，输入节点与回显节点均正确，树规则与整体目标通过，不需fresh人工确认。ON真实记录Jev different_action后的`jev_action_changed`重新规划，未重复输入。此结果补齐缓存修复的真实路径及计划交接，不以历史诊断钩子替代。

ON蓝框为VLM坐标动作[540,1015]一次命中，最终`coordinate tap completed`，计划复用一次并自动结束。OFF点中灰色“视觉手势目标”预设按钮，蓝框仍ready；Jev核验FAILURE、confidence .45进入视觉回退，Reflector将既有蓝边误判为新高亮而给SUCCESS。整体目标门禁拒绝模型结束，fresh两次核对后明确结束CANCELLED，原逐步错误SUCCESS和自动NEEDS_REVIEW保留。最终设备Dozing、无活动任务、规划OFF、Jev66/150。

ON本批没有新增错误动作或质量下降，但OFF暴露视觉回退仍会误读静态外观；交由有界源码诊断，暂不关闭25或声称普遍优化收益。所有旧失败保留，149项既有单测/构建/Sol审查不替代真实质量证据。累计估价与未知预留¥3.8819830，规划阶段¥0.9277075，费用限额未提高。
