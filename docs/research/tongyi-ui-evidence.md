# 通义 GUI Agent：仓库与可复用能力核验

核验日期：2026-09-21。仅查阅官方仓库、模型卡、论文；未下载模型、运行推理或连接手机。结论中的“未见”仅覆盖本次核验的公开仓库与官方 Hugging Face 组织。

## 1. 这两个链接不是两个独立的可运行框架

| 用户提供的仓库 | 当前用途与状态 | 本次固定版本 |
|---|---|---|
| `Tongyi-MAI/Qwen-UI-Agent` | **项目网站源码**；README 明确不含模型、训练代码和 agent 实现，指向 `MAI-UI` 仓库。 | [`f63856c`](https://github.com/Tongyi-MAI/Qwen-UI-Agent/commit/f63856c083ad66e030dc7a187a9a6d95d9b0f76c)，2026-09-15 09:56:30 UTC |
| `Tongyi-MAI/MAI-UI` | 真正项目入口。`MAI-UI/` 保留原版代码；`Qwen-UI-Agent/` 是后继研究，目前只有 README、技术报告 PDF 与宣传素材。不能把论文系统等同于已公开运行代码。 | [`3deabf1`](https://github.com/Tongyi-MAI/MAI-UI/commit/3deabf1d1fcf31964511929938b7c9485589b29c)，2026-08-19 04:25:41 UTC |

依据：[网站仓库声明](https://github.com/Tongyi-MAI/Qwen-UI-Agent/blob/f63856c083ad66e030dc7a187a9a6d95d9b0f76c/README.md)、[项目入口](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/README.md)、[完整文件树 API](https://api.github.com/repos/Tongyi-MAI/MAI-UI/git/trees/3deabf1d1fcf31964511929938b7c9485589b29c?recursive=1)。

## 2. MAI-UI 1.0 实际开放与运行边界

- 可核验的官方开放权重是 **MAI-UI-2B、MAI-UI-8B**，模型卡标记 `qwen3_vl`、BF16、Apache-2.0。32B 与 235B-A22B 出现在研究与指标中，但本次官方组织清单未见这两者权重。[2B 模型卡](https://huggingface.co/Tongyi-MAI/MAI-UI-2B)、[8B 模型卡](https://huggingface.co/Tongyi-MAI/MAI-UI-8B)、[官方模型清单 API](https://huggingface.co/api/models?author=Tongyi-MAI&limit=100)。
- 仓库提供 grounding、navigation 推理客户端、提示词、轨迹记忆、示例 notebook、grounding 评测程序及标注数据；本次完整树未见训练流水线、Android/ADB 执行器或完整端云路由运行时。论文描述的自进化数据、在线 RL 与端云协同不能全部算作已经开源。[固定版本目录](https://github.com/Tongyi-MAI/MAI-UI/tree/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI)。
- 官方 quick start 采用 vLLM 服务，仓库明确固定 `vllm==0.11.0`，要求 `transformers>=4.57.0`，示例 `tensor-parallel-size=1`。8B 模型卡写的是 vLLM `>=0.11.0`，与仓库要求不完全一致；复现应先按仓库固定版本。没有找到最低显存/手机芯片验收表，不能把“2B 可用于端侧”的论文定位当作任意 Android 手机即装即跑。[安装要求与许可证](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/README.md#-installation--quick-start)、[LICENSE](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/LICENSE)。

## 3. 输入、动作与模型替换：需要适配，不能只改 model 字符串

**运行时以截图为模型观察。** `predict()` 读取 `obs["screenshot"]`；`_build_messages()` 发送任务、历史动作和近几张截图。虽然 `TrajStep` 保存 `accessibility_tree`，该树未被这个消息构造函数发送给模型。因此“代码里有 accessibility_tree 字段”不代表“agent 已经利用 Android UI 树”。[消息构造实现](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/mai_naivigation_agent.py#L395)、[观察与轨迹保存](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/mai_naivigation_agent.py#L491)。

**动作是模型输出的文本协议。** 提示词定义 click、long_press、type、swipe、open、drag、system_button、wait、terminate、answer，扩展提示词加入 ask_user、double_click 和 MCP 描述。输出用 XML 标签包住 JSON；解析器按 0–999 坐标归一化。它不直接输出 Android resource-id 或 UI 节点引用。[动作提示词](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/prompt.py#L18)、[解析器](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/mai_naivigation_agent.py#L61)。

**OpenAI SDK 不等于 OpenAI 模型即插即用。** 客户端允许指定 `base_url` 和 `model_name`，但 API key 固定为 `empty`，请求带 vLLM 的 `top_k`/`repetition_penalty`，响应解析依赖上述文本协议。换一般 VLM 至少要改认证、参数和输出适配，并重测截图定位。[客户端初始化](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/mai_naivigation_agent.py#L236)、[请求参数](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/src/mai_naivigation_agent.py#L535)。

**示例没有完成“真实手机闭环”。** `run_agent.ipynb` 依次读取五张预存截图，调用 `predict()` 并画出预测点击点；手机截图、点击执行、等待稳定、再次观测和完成验证需要由外部 runtime 补齐。[完整示例](https://github.com/Tongyi-MAI/MAI-UI/blob/3deabf1d1fcf31964511929938b7c9485589b29c/MAI-UI/cookbook/run_agent.ipynb)。

## 4. Qwen-UI-Agent 后继工作的证据边界

论文评估 27B、35B-A3B、4B；主结果来自 27B。它扩展到手机、桌面、浏览器与检索，统一 GUI、CLI、API，并可批量输出动作；Android 侧通过 ADB shell 执行，返回输出、错误与退出状态。当前项目子目录尚无可据此检查的执行器源码。[后继目录](https://github.com/Tongyi-MAI/MAI-UI/tree/3deabf1d1fcf31964511929938b7c9485589b29c/Qwen-UI-Agent)、[论文 §2.1、§2.6、§3.1](https://arxiv.org/html/2607.28227v1)。

指标应保留协议边界：Qwen-UI-Agent 的 MobileWorld **82.1% 是 117 个 GUI-only 任务子集**；MobileWorld-Real **92.2%** 来自作者自建真机评测，409 任务/104 app，使用 AutoJudge，并排除 `env_error`。这些数字不能直接作为本项目任意真实任务成功率，也不能与旧 MAI-UI 的 MobileWorld 41.7% 做无条件提升计算。论文承认评判器有误差，交互延迟仍是实际使用障碍。[论文 §3.2、§7](https://arxiv.org/html/2607.28227v1#S3.SS2)。

旧 MAI-UI AndroidWorld 2B/8B/32B/235B 的报告成绩分别是 49.1%/70.7%/73.3%/76.7%；76.7% 不是开放 2B/8B 的成绩。ScreenSpot-Pro 则是单步定位评测，不能等同于多步完成率。[MAI-UI 论文 §3](https://arxiv.org/html/2512.22047v1#S3)。

## 5. 对 Jev + Android App 方案的推论

本节是架构推论；Jev 能力应以主报告独立核验的官方文档为准。

Jev 以文本/JSON 为输入、提供有限候选选择和评分，不能直接替换 MAI-UI 的截图 VLM。本项目由 App 无障碍服务提供结构化 UI 树，适配层把当前可操作节点及导航操作编成完整候选，Jev 选择，App 执行，再采集状态验证。MAI-UI 的观察—决策—动作—轨迹结构值得参考；其视觉输入与自由文本坐标协议不应照搬。

这条路线需要另外验证候选覆盖率、UI 树缺失时的视觉后备、节点引用过期、文本输入内容从何生成、动作后成功判断与循环恢复。当前仓库和论文均不能证明 Jev 在这些环节已经有效；应做同一设备、同一任务集的实际闭环试验。
