# Jev-MobileAgent 文档索引

更新日期：2026-09-22。当前处于调研与规划阶段，尚未开始 Fork、代码裁剪或 App 开发。

## 当前开发依据

1. [项目方向与决策](project-direction.md)：v3.5、公开 Fork 后裁剪、Android App 边界、上游同步与独立演进、模型部署与待验证事项。后续讨论调整以此文为入口。
2. [开发前准备](research/2026-09-22-development-readiness.md)：仓库、API 账号、GUI-Owl 1.5 模型规模、设备与运行环境。
3. [阶段规划、评测复用与流程图](research/2026-09-22-stages-evaluation-and-flows.md)：各阶段做什么、如何验证、可以复用哪些数据，以及 v3.5 改造前后的流程。

以上文档不设收益目标，也不表示功能已经实现或评测已通过。所有文档按当前方案维护，不保留已放弃的选型描述。

## 研究与来源证据

- [MobileAgent 源码、模型与工程边界](research/mobileagent-evidence.md)
- [Jev 官方能力与限制](research/jev-evidence.md)
- [MAI-UI 与 Qwen-UI-Agent 仓库边界](research/tongyi-ui-evidence.md)
- [Android 无障碍与设备端项目](research/android-accessibility-evidence.md)
- [App 内 shell、Shizuku 与 UiAutomation](research/android-on-device-shell-evidence.md)
- [Android CLI 本机与官方能力](research/android-cli-evidence.md)

## 专项分析

- [方案可行性与三个仓库的定位](research/2026-09-21-mobile-agent-feasibility.md)
- [手机自行获取布局与设备方案](research/2026-09-21-on-device-layout-and-mobileagent.md)
- [v3.5 复用边界与 Jev 接入](research/mobileagent-jev-adaptation-evidence.md)
- [成本核算与优化空间](research/2026-09-22-jev-cost-model.md)：按实际调用统计，不预设收益比例。
