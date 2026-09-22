# 候选构造与 Jev 离线选择

Status: ready-for-agent
Execution: pending
Blocked by: 01, 02, 03
Owner: unassigned
Branch: unassigned
Workstream: Agent 与模型
Gate: 完整候选与选择可被离线评价

## 目标

完整候选与选择可被离线评价。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [共享契约与工程布局](01-shared-contracts.md)：需在集成分支上完成并有验收证据。
- [模型协议兼容性验证](02-model-compatibility.md)：需在集成分支上完成并有验收证据。
- [任务样本与独立判分设计](03-evaluation-design.md)：需在集成分支上完成并有验收证据。

## 修改范围

agent-core/ 的候选构造、Jev 适配与视觉回退信号；eval 离线样例。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 基于当前树、子目标和已知参数生成完整候选，绑定观察版本；提供无合适候选/转视觉路径。
2. 文本来自用户、模板或生成式模型，坐标来自明确观察或视觉适配；不要求 Jev 生成开放参数。
3. 按固定 Jev 版本与实际限制处理候选过多、失败、置信度和中文输入；阈值使用开发集确定，保留留出集。

## 验收

- [ ] 分别统计候选覆盖、选择错误和参数错误；参考答案不能用于反向构造候选。
- [ ] 缺参、非法返回、旧观察及候选为空均不能直接执行；输出可映射到一条确定动作。
- [ ] 离线结论只陈述样例覆盖与选择结果，不宣称已经提高真机任务成功率。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [Jev 能力证据](../../../docs/research/jev-evidence.md)
- [角色复用与改造接口](../../../docs/research/mobileagent-jev-adaptation-evidence.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
