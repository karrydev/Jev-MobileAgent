# 树核验接入与对照

Status: ready-for-agent
Execution: pending
Blocked by: 14, 15
Owner: unassigned
Branch: unassigned
Workstream: Agent 与集成
Gate: 以树替代部分视觉核验不会混淆成功语义

## 目标

以树替代部分视觉核验不会混淆成功语义。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [Jev 影子与受控执行](14-hybrid-execution.md)：需在集成分支上完成并有验收证据。
- [四态数据与核验模块](15-verification-module.md)：需在集成分支上完成并有验收证据。

## 修改范围

agent-core/ 核验路由与循环；eval 集成对照。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 接入四态核验，明确结果时跳过不必要的视觉核验，观察不足时使用已缓存前图和后图。
2. 分别处理 FAILURE 重规划、PENDING 有限等待、UNKNOWN 接管与 SUCCESS 后任务完成检查。
3. 保持阶段 2 的选择与规划配置，单独比较核验策略变化。

## 验收

- [ ] 动作成功不会直接把整个任务置为完成；命令受理也不会直接置为动作成功。
- [ ] 四态、截图不可用和前图缺失分支有真实或明确受控证据；未知不能无限循环。
- [ ] 报告误报成功、等待、视觉回退及全部费用，开关可恢复上一阶段行为。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [相关章节](../../../docs/research/2026-09-22-stages-evaluation-and-flows.md#4-截图与验证衔接)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
