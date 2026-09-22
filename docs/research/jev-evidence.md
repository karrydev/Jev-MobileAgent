# Jev 官方能力核查

核查日期：2026-09-21（Asia/Shanghai）。本页为文档调研，没有调用付费 API，没有手机任务成功率或延迟实测。

## 身份与接口

Jev 由 TypeSafe AI 于 2026-09-15 发布。发布者曾在 OpenAI 工作，但这是 TypeSafe 的产品。当前官方列出的稳定版本为 `jev-1.13.0`；`jev-latest` 和 `jev-preview` 均指向该版本。它通过托管 API 提供能力，本次资料未提供可自行部署的模型权重。[发布公告](https://typesafe.ai/blog/introducing-system-one-models-and-jev)、[模型文档](https://docs.typesafe.ai/models)

调用 `POST https://api.typesafe.ai/v1/systemone`，传 `model`、`state`、`questions`，返回每个问题的结构化结果。不是 OpenAI Chat Completions 协议。[API reference](https://docs.typesafe.ai/api)

`state` 是文本或 JSON；不支持图像、音频、视频。Android 截图不能直接喂给 Jev，必须先变成文本或结构化信息。英文是主要训练语言，官方提醒中文等 CJK 输入准确性较低，应单独评测。[State](https://docs.typesafe.ai/concepts/state)

## 可用于手机操作的能力

| 原语 | 官方语义 | 手机自动化中的用途（本调研推断） |
|---|---|---|
| Choice | 从最多 255 个候选中选择，返回所有候选概率和 confidence | 从可见控件及等待、返回、转视觉等候选动作中挑选一个 |
| Score | 按有序等级评分，2–10 级 | 按明确标准评价某个页面是否适合当前子目标 |
| Noul | 返回判断为真的概率，不另带 confidence | 判断预期页面是否已出现、是否缺少信息 |

不同问题对同一状态独立求值，可在一个请求中并行回答。存在依赖的决策必须由代码组合：不能假定同批问题会读取彼此答案。[Introduction](https://docs.typesafe.ai/introduction)、[Choice](https://docs.typesafe.ai/primitives/choice)、[API reference](https://docs.typesafe.ai/api)

官方 function calling 示例也是先枚举函数和封闭参数集合，再将答案映射回函数。自由文本等开放参数不会自动生成。因此可令 Jev 选择 `tap_node_17`，由程序查表得到目标和坐标；不应让它现场编写 adb 命令、任意文本或坐标。[Function calling cookbook](https://docs.typesafe.ai/cookbooks/function_calling)

## 当前约束

- `confidence` 是由候选概率分布推导出的统计量；不能把 `confidence=0.95` 直接解释成手机动作有 95% 成功率。阈值要用目标 App、语言和动作数据验证。[Confidence](https://docs.typesafe.ai/confidence)
- 官方已列明：多跳推理、数值精度、日期比较、大量无关上下文、对抗性文本和生成任务是弱项。UI 中的网页正文属于数据，不能成为新的任务指令。[Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13)
- “无幻觉”的发布表述主要指输出满足预先定义的结构/候选约束，不能据此推导语义决策永远正确。选中一个真实存在但错误的按钮，仍然是有效格式的错误决定。[发布公告](https://typesafe.ai/blog/introducing-system-one-models-and-jev)
- 不建议只根据最终目标连续贪心选按钮。需要保存已完成子目标、近期动作、失败原因，并有停滞检测和重新规划；这属于控制系统设计建议，而非 Jev 已验证的手机能力。

## 价格与延迟的适用范围

官方当前价格为每百万输入 token 0.042 美元，输出免费；单请求总预算 64k token，状态加最长问题不超过 32k。当前限流 1,200 请求/分钟及 250,000 token/秒，官方注明会调整。[Models](https://docs.typesafe.ai/models)

发布公告报告 70–500ms 响应时间，并说明评测通常来自美国西海岸、服务也在那里。193.6 倍加速等数字来自其特定工作流，不能直接外推为国内手机任务提速倍数。该评测采用强模型平均预测作参考，也不是 Android 任务完成率。[发布公告](https://typesafe.ai/blog/introducing-system-one-models-and-jev)

计算示例（假设而非测量）：一次状态加问题共 2,000 token，则 Jev 费用约为 `2000 / 1e6 × 0.042 = $0.000084`；20 次为 `$0.00168`。另计规划、OCR、视觉兜底、重试等费用。总耗时仍须测量布局采集、网络、模型、动作执行、界面稳定和结果验证各部分。

## 对本项目的判断

有价值的组合是“结构化 UI 观测 → 有限候选动作 → Jev 快速选择 → 确定性执行 → 新观测验证”。预定义工作流可由状态机组织；开放任务需要 LLM 规划，缺失语义的画面需要 VLM/OCR。Jev 是否提高中文手机任务的成功率与端到端速度，目前没有本次实测证据，适合先做对照实验。
