# 15 — 真实 VLM 能完成所需角色协议请求

**What to build:** 维护者用固定的小型探针获得真实单模型与四角色协议的响应报告，明确可用与不兼容能力，再把一个有效动作交给模拟设备执行。

Blocked by: 09, 13

Status: ready-for-agent

Execution: done
Owner: luna-live-vlm
Branch: codex/v1-live-vlm
Evidence: 真实模型 API
Gate: 真实 VLM 能完成所需角色协议请求

## 前置与规格

[09 — 回放模型响应驱动任务并处理服务错误](09-model-replay-task.md)、[13 — 提供真实 VLM 最小访问条件](13-human-vlm-access.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 实际验证所选模型的中文、多图、动作/规划/反思/记忆所需格式与 usage；不把 SDK 兼容等同于角色兼容。
- [x] 记录模型、配置、提示词、响应、错误与真实消耗，遵守用户允许的试跑预算。
- [x] 最小适配或上游阻塞修复单独记录，静态 file URI/动作覆盖/坐标疑点先复现，不凭猜测改写基线。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-23：09/13 前置已满足，领取真实协议探针。允许范围 services/live_vlm/、直接测试及说明，必要时小范围修复现有探针；保留原版角色源码与基线，不提前提取/裁剪。真实请求由主协调者串行发起、统计预算；子 Agent 仅离线开发测试，不读取凭据、不调用付费模型。现有最小探针图像仍为夹具占位，已确认不能直接用作真实视觉验收，需有效合成图与明确输出上限。

2026-09-23：真实探针 suite02 五次请求均 HTTP 200，中文、多图及四角色标题解析可用；原版手机动作生成有效点击并经现有 SimulatedDevice 执行、独立重新观察确认。Executor 返回 [440,1143]，超出上游执行器约定的 0–1000，按失败保留，不能标记四角色完整兼容。原版角色提示词未明确该坐标范围，拟以独立开关添加最小坐标说明并另记适配结果，不覆盖原始失败。suite01 的图像/验收器问题也保留为无效验收尝试；全部调用计费见 ../live-model-budget.json。

2026-09-23 验收：原探针提交 `bcd3f27`，显式坐标适配提交 `031276a`，原版三份提示词/解析源码未修改。suite03 启用 coordinate_adaptation 后五请求全部兼容，phone/Executor 动作经 SimulatedDevice 回执 EXECUTED 与后观察 done 独立确认；原始模式 Executor 失败保留，不能宣称未经适配四角色通过。Luna 实现与 Terra 定向复审 PASS；`python3 -B -m unittest tests.test_live_vlm tests.test_runtime_controls.LiveModelFixtureTests` 16/16，通过 py_compile/diff check。最终合入文件与真实运行 probe_sha256 一致。全部 16 次成功 HTTP 请求及初始 TLS 失败记录在费用账本，总公开价格估算 ¥0.019353；实际账单/免费额度未读取。证据见 [原版 suite02](../evidence/live-vlm-original-suite02.json)、[适配 suite03](../evidence/live-vlm-adapted-suite03.json) 与 [费用账本](../live-model-budget.json)。本项关闭协议与模拟动作门槛，尚无真实模型驱动的物理手机或 AndroidWorld 基线。
