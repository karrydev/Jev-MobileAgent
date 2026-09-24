# 工程指引

开展工作前阅读 `docs/README.md`、`docs/project-direction.md` 和 `CONTEXT.md`。领取开发任务前阅读 `docs/development-roadmap.md`、`docs/parallel-development.md` 与 `.scratch/mobile-agent-v1/index.md` 与 `.scratch/mobile-agent-v1/spec.md`；按任务链接读取相关研究和 ADR。仅从 mobile-agent-v1 领取任务，旧 mobile-agent-development 是历史规划参考。

基于 MobileAgent v3.5 开发独立 Android App：手机内编排、直连模型，用户在 App 填写 Key，无需自建服务器、电脑或 ADB。当前仍有 Python 设备桥实现；迁移边界见 `docs/adr/0003-standalone-android-runtime.md`，旧 19/20 证据不替代独立 App 验收。保留来源历史、许可证及上游提交记录。

## 开发执行

按用户指定的 `code-this` 技能推进已批准任务，子 Agent 的实现与审查模型以当前技能文件为准；主 Agent 负责调度、集成验证、任务证据和 Git 操作。实现者不提交或推送。沿用已确认的独立 worktree 协作。

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
