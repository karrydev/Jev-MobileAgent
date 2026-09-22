# 四态数据与核验模块

Status: ready-for-agent
Execution: pending
Blocked by: 02, 11, 12
Owner: unassigned
Branch: unassigned
Workstream: 评测与 Agent
Gate: 核验结论有独立标签与不足分支

## 目标

核验结论有独立标签与不足分支。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [模型协议兼容性验证](02-model-compatibility.md)：需在集成分支上完成并有验收证据。
- [轨迹、回放与费用统计](11-trace-replay.md)：需在集成分支上完成并有验收证据。
- [仅 VLM 的 App 闭环](12-vlm-device-loop.md)：需在集成分支上完成并有验收证据。

## 修改范围

eval/ 四态数据；agent-core/ 的规则与 Jev 核验模块。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 采集并标注成功、无效/错误操作、加载中和观察不足；输入包含子目标、动作、预期后置条件、前后证据及时间。
2. 实现规则优先、必要时 Jev、信息不足转视觉的核验接口；明确 PENDING 等待界限与 UNKNOWN 处理。
3. 覆盖相似文字、页面变了但目标未达成、截图失败、前图缺失和树外变化；真值由受控页面或人工提供。

## 验收

- [ ] 输出四态混淆与误报成功；阈值只用开发数据选择，留出数据独立报告。
- [ ] 终局判分不能直接当每一步的标签，VLM Reflector 不能直接充当真值。
- [ ] 信息不足不强行转为 SUCCESS；等待有限且可中止。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [相关章节](../../../docs/research/2026-09-22-stages-evaluation-and-flows.md#3-需要自建的最小评测材料)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
