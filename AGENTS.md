# 工程指引

开展工作前阅读 `docs/README.md` 和 `docs/project-direction.md`；阶段实施与验证阅读 `docs/research/2026-09-22-stages-evaluation-and-flows.md`。

基于 MobileAgent v3.5 开发 Android App 与服务端。先建立原版基线，再提取角色代码和裁剪；保留来源历史、许可证及上游提交记录。工程初始化不代表模型、设备桥或真机评测已通过。

## Agent skills

### Issue tracker

任务和规格使用本地 `.scratch/<feature>/` Markdown 文件追踪。操作前阅读 `docs/agents/issue-tracker.md`。

### Triage labels

任务使用五种默认 triage 状态。分拣或变更状态前阅读 `docs/agents/triage-labels.md`。

### Domain docs

采用 single-context：根目录 `CONTEXT.md` 与 `docs/adr/`。探索领域或设计模块前阅读 `docs/agents/domain.md`。

## CodeGraph

结构查询优先使用 CodeGraph：上下文用 `codegraph_context`，调用路径用 `codegraph_trace`，批量源码用 `codegraph_explore`，符号定位用 `codegraph_search`，调用关系用 `codegraph_callers` / `codegraph_callees`，变更影响用 `codegraph_impact`，单个符号用 `codegraph_node`，文件结构用 `codegraph_files`，索引状态用 `codegraph_status`。

架构问题直接用 context + explore；流程问题用 trace + explore。信任索引结果，不重复 grep 验证或委派文件探索。字面文本查询使用 rg；写入后留出索引同步时间再查询。

新工作副本缺少索引时，询问是否执行 `codegraph init -i`。索引数据库保留本地，不提交。
