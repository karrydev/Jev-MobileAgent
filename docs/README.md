# Jev-MobileAgent 文档索引

更新日期：2026-09-24。公开 Fork、工程技能配置与 CodeGraph 已初始化，正式规格与本地任务已建立，模拟任务闭环、独立离线判分和离线选择/核验已通过验收；Android 观察已在模拟器与物理手机受控页面验证；确定性节点动作与任务控制已在真机验证；截图与手势也已在受控真机验证。真实模型基础访问已通过，原版手机协议与最小坐标适配后的四角色协议验证已通过；原版双入口基线已采集（真机成功、AndroidWorld 5 步任务未完成）；角色提取与同条件参考对照已完成（严格任务判分仍失败），无关目录裁剪与参考复验已完成，App+VLM 已通过受控真机闭环、暂停取消及权限/截图失败验证，中断核对与手动恢复已通过受控真机验收，接下来等待 Jev 与服务器访问条件。

## 开发入口

1. [开发路线](development-roadmap.md)：交付范围、阶段门槛、工作线、并行波次、共享契约及证据要求。
2. [并行协作约定](parallel-development.md)：本地任务领取、worktree、共享文件归属、合入顺序和交接。
3. [实施任务索引](../.scratch/mobile-agent-v1/index.md)：28 项已确认任务及其依赖；从无阻塞且未领取的 Agent 任务开始；人工准备按触发时机延后。
4. [领域词汇](../CONTEXT.md)：统一任务、观察、动作核验、任务完成、接管与恢复的含义。

## 已验收的本地入口

- [模拟任务闭环](../services/sim_loop/README.md)：本地 HTTP 模拟设备、任务状态和协议样例；未代表 Android 通道已实现。
- [独立离线判分](../eval/README.md)：合成任务、逐步与终局判分、开发/留出划分和实验模板。
- [任务轨迹与消耗报告](../services/task_evidence/README.md)：运行与回放、独立判分、公开脱敏和费用未知标记；当前为离线证据。

- [离线候选选择与四态核验](../agent_core/README.md)：冻结合成样本、回放分类和独立判分；真实 Jev 尚未验证。

- [Android 观察 App](../android-app/README.md)：固定构建入口、连接与权限、真实无障碍树；观察、节点点击/中文输入与暂停取消已在受控真机验证。

- [App + VLM 真机验证](app-vlm-validation.md)：App 配置、受控任务和任务 19 实测证据；不代表 Jev 或脱离 ADB 已验收。
- [Android 中断恢复](android-recovery-validation.md)：核对、显式恢复、已知设备边界与任务 20 证据。
- [本地部署演练](local-deployment.md)：启动、健康检查、重启与回退；远端部署仍待服务器条件。

## 当前决策与研究依据

- [项目方向与决策](project-direction.md)：选型、公开 Fork、Android App 边界、首版单手机与手动恢复。
- [App 与编排边界](adr/0001-device-and-orchestration-boundary.md)、[单手机与手动恢复](adr/0002-single-device-and-explicit-resume.md)：已确认的关键取舍。
- [开发前准备](research/2026-09-22-development-readiness.md)：API 账号、设备与运行环境；账号可用性仍需实测。
- [阶段规划、评测复用与流程图](research/2026-09-22-stages-evaluation-and-flows.md)：阶段 0–5 的研究依据、评测素材与改造前后流程。

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
