# 无障碍观察新鲜度根因与验证

2026-09-25。原 b84d1b8 两次输入任务中，实际截图及旁边回显已是正确中文，但同帧可编辑节点仍为旧提示“请输入中文”，导致树规则误报输入失败。三个零模型实验（Debug夹具、补即时AFTER采样、标准页8字目标）都未复现，未据此改产品。

一次完整任务路径取证 `planning25-off-input08-diagnostic` 在观察4170复现：同帧回显正确、serialized/raw child旧；对同一节点 `refresh()` 成功，1ms后文字正确，节点path/window/platform identity/bounds一致，事件序号24→24→24不变。SDK实现确认 `getChild()` 可以读客户端缓存，而refresh绕过缓存；据此确认是客户端子节点缓存陈旧，不是动作失败或服务端仍未更新。

诊断APK以09e65b0为基底加临时hook，源码patch SHA和APK SHA保存在[完整证据](accessibility-freshness-diagnosis.json)。hook不改当帧JSON，但刷新会影响后续缓存，所以本次不属于正式OFF/ON验收。原任务输入生效但后置核验FAILURE，下一次决策现场比较暂停；随后两次fresh核对加明确确认结束COMPLETED_ON_REVIEW，没有追加动作/API。2次VLM、1次Jev，总标价估算和预留¥0.01714，已入账；原始失败与人工确认分开保存。

生产修复已完成：API33+采集前清除服务的窗口和节点缓存；失败或旧API逐节点refresh，任一刷新失败/异常即丢弃整帧、清动作绑定及坐标指纹，返回UNAVAILABLE。临时hook与页面长按入口全部删除，普通、decision-only、恢复及截图前复核统一使用该入口。149项单测（含3项新鲜度策略测试）和assembleDebug通过，Sol定向审查无阻塞问题。APK SHA-256为`3094239aec5da8c162ce4eb1a0da2ff6d3c415d3a2ed7cf035b5e92d835a937b`。正式同APK四配对真机验收尚待执行，不能将诊断成功或人工确认作为自动完成证据。
