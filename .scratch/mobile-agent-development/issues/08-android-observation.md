# Android 观察与能力上报

Status: ready-for-agent
Execution: pending
Blocked by: 01, 04
Owner: unassigned
Branch: unassigned
Workstream: Android
Gate: 真实树、窗口和能力可被统一表示

## 目标

真实树、窗口和能力可被统一表示。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [共享契约与工程布局](01-shared-contracts.md)：需在集成分支上完成并有验收证据。
- [API 与设备访问条件](04-human-access.md)：需在集成分支上完成并有验收证据。

## 修改范围

android-app/ 的服务配置、观察采集、能力上报及本地样例。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 建立 App 和 AccessibilityService，采集当前窗口、节点语义/状态/动作、屏幕尺寸与旋转，生成观察版本和临时节点引用。
2. 处理权限未启用/撤销、空根、多窗口、重复节点、遍历边界、界面事件和观察失效；自绘或缺失语义明确表示不足。
3. 输出统一契约样例并对照可控页面；保留真实观察证据的本地路径，提交时使用脱敏样例。

## 验收

- [ ] 在目标手机或明确标注的模拟器实际读取树；只有 Mock 结果不能完成。
- [ ] 界面变化后旧节点不被当作永久目标；IME、弹窗、滚动和旋转具有可解释观察。
- [ ] 树为空和权限不可用均返回明确状态，不伪造可用界面。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [Android 观察与执行证据](../../../docs/research/android-accessibility-evidence.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
