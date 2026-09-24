# 在真机复现 App 的 VLM 闭环

> 2026-09-25：以下说明当前桥接式实现或其历史验收。按 [ADR-0003](adr/0003-standalone-android-runtime.md)，最终产品改为手机内编排；这些操作不作为独立 App 用户的使用前提，迁移由任务 26/27 验收。

任务19的实现为 `2ed36fb`，验收记录为 `5510313`。已验证 OnePlus 8T / Android 14；这是开发机运行编排、手机 App 观察与执行的阶段，手机内编排及独立运行由修订后的任务 26/27 验收。

## 操作步骤

1. 按 [Android 安装与连接说明](../android-app/README.md) 构建、安装 App，启动 Python Android bridge，并通过独立端口的 `adb reverse` 连接手机。设备桥 Token 与模型 Key 是两项不同配置。
2. 在手机设置中启用 **Jev observation service**。在 App 填写桥地址、Token、设备 ID 和一个新的任务 ID；每轮使用不同任务 ID。
3. 在 App 填写模型配置：provider 为 `GUI-Plus`，endpoint 为 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，model 为 `gui-plus-2026-02-26`，API Key 使用自己的百炼 Key。Key 保存在 App 私有空间，随任务提交给自己的编排服务，不写到任务报告。这是旧桥接实现的行为；任务 26 将改为 App 加密保存凭据并直连供应商。
4. 点击 **Connect and capture observation** 保存配置。选择 **Fill deterministic click goal**，再打开 **Open controlled observation page**，确认初始状态是 `ready`。
5. 在受控页点击 **Start real VLM task**。App 会提交新观察与截图、接收模型动作，通过无障碍服务执行，再返回动作后的观察和截图。
6. 对比页面的 `Controlled action state: completed` 与任务状态。回执受理、观察到的效果、模型完成判断、独立任务判分是不同字段；不能仅凭回执 `accepted=true` 判成功。
7. 测试结束锁屏。开发测试可执行 `adb -s <device-serial> shell input keyevent 223`；不要让手机因等待代码或审查持续亮屏。

该入口默认最多5步、25次模型请求、每次最多1024输出token、每任务估价上限¥1。当前已知计价模型为上述 GUI-Plus；未知价格不会按零费用继续。这里的¥1是单任务软件限制，整个首轮仍遵守用户批准的¥10总上限。

## 本轮已验证的结果

- 同条件 ADB 参考 `ref01` 与 App `app03` 都完成受控任务，分别7和5次请求；只证明两种设备通道能完成该单例，不据此推断普遍性能或降本。
- 最终 APK 的中文输入和自绘目标滑动通过独立页面状态核对。
- 真机暂停/取消后释放显式回放的迟到模型响应，没有继续派发动作。
- 截图上传注入503、通过系统UI撤销无障碍权限时，App明确失败且没有调用模型；之后恢复权限。
- 权限重新启用后需回到 App 再点 **Connect and capture observation** 恢复采集。重新授权本身不启动任务。

公开脱敏报告在 [任务19](../.scratch/mobile-agent-v1/issues/19-vlm-app-loop.md) 中索引，所有失败请求包含在 [费用台账](../.scratch/mobile-agent-v1/live-model-budget.json)。原始图像与模型响应留在本机 `/tmp/jev-task19-*`、`/tmp/jev19-*`，不提交仓库。任务20负责中断后的持久化核对和手动恢复。
