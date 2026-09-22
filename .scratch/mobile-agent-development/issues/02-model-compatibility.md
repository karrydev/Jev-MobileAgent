# 模型协议兼容性验证

Status: ready-for-agent
Execution: pending
Blocked by: 04
Owner: unassigned
Branch: unassigned
Workstream: Agent 与模型
Gate: 选定 API 能支撑原版入口与 Jev 实验

## 目标

选定 API 能支撑原版入口与 Jev 实验。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [API 与设备访问条件](04-human-access.md)：需在集成分支上完成并有验收证据。

## 修改范围

模型适配检查工具；配置样例；eval 中模型兼容性证据。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 为候选 GUI-Plus/兼容 VLM 与固定 Jev 版本建立最小调用探针，记录实际模型 ID、端点类型和环境变量名。
2. 分别验证 Manager、Executor、ActionReflector、Notetaker 的输入输出，以及多图、中文、动作格式、usage 和错误；真机单模型入口单独记录。
3. 验证 Jev 的文本/JSON 与候选响应、缺参/拒绝/超时等处理，给出实际可用的配置；候选服务不兼容时记录阻塞和替代方案，不能冒称 OpenAI 兼容即角色兼容。

## 验收

- [ ] 真实请求有脱敏证据；所有选定角色的输出均能由目标解析器消费。
- [ ] 探针发生超时、格式错误和服务失败时明确失败，不产生设备动作；未实测的供应商能力标为未验证。
- [ ] 模型和提示词版本可复现，原生协议与适配补丁分别记录；费用来源可追溯。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [开发准备](../../../docs/research/2026-09-22-development-readiness.md)
- [Jev 能力证据](../../../docs/research/jev-evidence.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
