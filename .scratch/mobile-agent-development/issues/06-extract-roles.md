# 角色提取与行为对照

Status: ready-for-agent
Execution: pending
Blocked by: 01, 05
Owner: unassigned
Branch: unassigned
Workstream: Agent 与模型
Gate: 提取后 VLM 编排具有可用对照

## 目标

提取后 VLM 编排具有可用对照。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [共享契约与工程布局](01-shared-contracts.md)：需在集成分支上完成并有验收证据。
- [原版双入口基线](05-upstream-baselines.md)：需在集成分支上完成并有验收证据。

## 修改范围

agent-core/；参考设备适配；来源清单。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 从 v3.5 AndroidWorld 提取 Manager、Executor、ActionReflector、Notetaker 和 InfoPool，保留来源路径、提交和版权。
2. 将设备访问、模型调用、轨迹输出与角色逻辑分离；处理固定屏幕尺寸、任务特例和环境初始化；差异逐项记录。
3. 保持对照策略和角色调用条件，先使用参考设备后端复跑基线；不同时加入 Jev 或按需规划。

## 验收

- [ ] 生产核心可在不加载完整 AndroidWorld 的条件下运行契约/回放；真实对照仍按固定环境执行。
- [ ] 提取前后相同输入的解析、动作及状态更新有对照，差异被解释；完整任务判分及费用变化有记录。
- [ ] 已依赖与未使用文件清单足以支持后续裁剪。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [角色复用与改造接口](../../../docs/research/mobileagent-jev-adaptation-evidence.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
