# Domain Docs

本项目采用 single-context 领域文档布局。

## 探索前阅读

- 根目录 `CONTEXT.md`：领域术语与模型。
- `docs/adr/`：与当前工作相关的架构决策。
- `docs/project-direction.md`：当前项目方向与边界。

`CONTEXT.md` 或 ADR 尚不存在时直接继续，不预先创建空文件；由 domain-modeling 在术语或决策得到明确结论时按需建立。

## 文件布局

- `CONTEXT.md`
- `docs/adr/NNNN-<decision>.md`

## 使用领域词汇

任务标题、设计、假设和测试名称使用 `CONTEXT.md` 已定义的术语。遇到缺失概念时先检查现有词汇，确有缺口再交由 domain-modeling 补充。

## 显式指出决策冲突

建议与现有 ADR 冲突时，指出 ADR 编号、冲突内容和重新讨论的理由，由后续决策明确是否替代。
