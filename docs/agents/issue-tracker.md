# Issue tracker: Local Markdown

本项目的任务与规格保存在 `.scratch/`，随项目进行版本管理。

当前实施入口为 `.scratch/mobile-agent-v1/index.md`，规格为同目录 `spec.md`。旧 `.scratch/mobile-agent-development/` 保留为历史规划参考，不再领取，也不将旧票批量改为完成。

## 文件约定

- 每个功能一个目录：`.scratch/<feature-slug>/`。
- 规格：`.scratch/<feature-slug>/spec.md`。
- 每张实施任务单独一个文件：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 编号。
- 任务文件顶部用 `Status:` 记录 triage 状态，取值见 `triage-labels.md`。
- 评论和讨论追加在文件底部的 `## Comments` 下。

技能要求“发布到任务追踪器”时，创建上述本地文件；要求“获取相关任务”时，读取指定路径。仅有编号时，在对应功能目录中解析；若存在歧义，先确定功能目录。

## Wayfinding operations

供 `/wayfinder` 使用：

- 地图：`.scratch/<effort>/map.md`，包含 Notes、Decisions-so-far、Fog。
- 子任务：`.scratch/<effort>/issues/NN-<slug>.md`，正文记录问题，`Type:` 为 research / prototype / grilling / task。
- wayfinder 子任务采用独立执行状态：`Status: open`、`claimed`、`resolved`；与 triage 的五种状态分开使用。
- 依赖：顶部 `Blocked by: NN, NN`；依赖任务全部 resolved 后才解除阻塞。
- 领取：按编号选择 open、无阻塞且未领取的任务，开始工作前保存 `Status: claimed`。
- 完成：在 `## Answer` 下追加结果，设为 `Status: resolved`，再向地图的 Decisions-so-far 追加摘要及链接。
