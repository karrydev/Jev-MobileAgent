# Triage Labels

技能中的五种角色映射为任务文件的 `Status:` 值：

| 技能角色 | 本地状态 | 含义 |
| --- | --- | --- |
| needs-triage | needs-triage | 等待维护者评估 |
| needs-info | needs-info | 等待补充信息 |
| ready-for-agent | ready-for-agent | 规格充分，可由 Agent 执行 |
| ready-for-human | ready-for-human | 需要人工实施 |
| wontfix | wontfix | 不予实施 |

技能要求应用某个 triage 标签时，更新本地任务文件的 `Status:`。wayfinder 的执行状态约定见 `issue-tracker.md`。
