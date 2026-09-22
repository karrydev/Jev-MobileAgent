# Android 执行、截图与任务控制

Status: ready-for-agent
Execution: pending
Blocked by: 08
Owner: unassigned
Branch: unassigned
Workstream: Android
Gate: 设备端动作与暂停语义可验证

## 目标

设备端动作与暂停语义可验证。本任务尚未开始，文中能力均为待交付。

## 前置依赖

- [Android 观察与能力上报](08-android-observation.md)：需在集成分支上完成并有验收证据。

## 修改范围

android-app/ 的节点动作、手势、截图、回执、用户控制。超出范围先与对应负责人协调；根配置和契约不由消费者自行修改。

## 实施与产物

1. 实现统一动作支持表，节点动作优先，手势作为后备；输入内容、观察版本和目标合法性在执行前检查。
2. 实现截图与前图缓存、采集/上传计数、回调/失败语义和坐标换算；受限窗口明确不可用。
3. 实现用户暂停、停止、确认恢复的可见入口；权限变化和手动介入中断执行；与服务端命令去重契约一致。

## 验收

- [ ] 点击、输入、滚动、长按、导航等已声明动作逐项通过，未支持项显式拒绝；中文输入不能靠英文样例替代。
- [ ] 手势提交/回调和页面效果分开记录；旧观察、缺参、越界和重复命令不导致错误执行。
- [ ] 暂停后新动作不再执行；截图不可用时保留明确失败，前图不存在不能事后伪造。

## 证据与交接

按[协作约定](../../../docs/parallel-development.md)记录最终 SHA、契约/模型/设备版本、验证命令、结果和未验证项。原始私人内容与凭据保留在指定运行环境；仓库只留脱敏摘要和可复现说明。实施任务完成但尚未合入或缺少必要实测时，Execution 保持 in-progress。

## 阅读材料

- [开发路线](../../../docs/development-roadmap.md)
- [项目领域词汇](../../../CONTEXT.md)
- [Android 观察与执行证据](../../../docs/research/android-accessibility-evidence.md)
- [首版与恢复决策](../../../docs/adr/0002-single-device-and-explicit-resume.md)

## Comments

2026-09-22：依据既有研究与已确认的首版边界建立任务；尚未执行。
