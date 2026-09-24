# MobileAgent v3.5 一手证据

> 2026-09-25：下文未运行的描述限定于 9 月 21 日研究时点。后续模型与真机基线见本地任务 15–20，Jev API 探针见任务 21；当前产品采用 [独立 Android App](../adr/0003-standalone-android-runtime.md)，旧研究不要求用户部署设备桥。

资料核查时间：2026-09-21 01:29（Asia/Shanghai）；方案更新：2026-09-22。已在线读取 GitHub、arXiv、Hugging Face，并将仓库浅克隆到 `/tmp/mobileagent-research-20260921` 阅读源码。核查基线为 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`，提交时间 `2026-07-07T17:22:18+08:00`。下列代码链接固定在该提交，没有连接手机、调用模型或复现 benchmark。

## 项目是什么

MobileAgent 是阿里通义实验室的 GUI Agent 项目家族，包含框架、GUI 专用模型与设备/评测入口。本项目选用 **Mobile-Agent-v3.5 / GUI-Owl-1.5**，公开 Fork 后保留需要的 Android 代码，按需择取上游更新。[官方总览](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/README.md)

GUI-Owl-1.5 派生自 Qwen3-VL，公开集合包含 2B-Instruct、4B-Instruct、8B-Instruct、8B-Think、32B-Instruct、32B-Think 六个 checkpoint。`1.5` 为版本号。论文另提及 `235B-A22B`，不能据此认定对应权重已经发布。[v3.5 README](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/README.md)、[官方模型集合](https://huggingface.co/collections/mPLUG/gui-owl-15)、[论文](https://arxiv.org/abs/2602.16855)。

## 架构与真实代码边界

**论文框架**：Manager 拆分/更新子目标，Worker 产生下一动作，Reflector 对比动作前后状态，Notetaker 保存信息，形成闭环。GUI-Owl-1.5 的基本模型输入为截图和指令，输出动作说明及结构化 tool call；框架状态描述允许 UI tree / 设备元数据，但不代表每个实现都实际使用这些输入。[v3.5 论文 §2.1、§2.3.3](https://arxiv.org/html/2602.16855v1)

**真机示例代码**：`run_gui_owl_1_5_for_mobile.py` 的循环依次截图、构建消息、调用 VLM、解析动作、执行 ADB、记录轨迹，再等待 2 秒。默认最多 50 步。支持 click、long_press、type、swipe、Back/Home、open、wait、answer、terminate、用户接管。坐标按 0–1000 归一化数值换算。该脚本是单模型端到端示例，不能把它描述成已包含论文四角色的完整编排。[执行循环源码](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py)

**感知和执行**：本次审阅的真机示例没有采集/输入无障碍树的流程。`AdbTools` 以 `exec-out screencap -p` 截图，`shell input tap/swipe/keyevent` 操作，ADB Keyboard 广播输入文本，`monkey -p` 启动应用。`build_messages` 传当前截图、最近四轮截图/动作和更早动作文本摘要；`GUIOwlWrapper` 使用 OpenAI Python SDK 的 `chat.completions.create` 调兼容服务。执行器不自带目标选择智能。[工具、提示词与消息构造源码](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/utils.py)

## 模型替换与 Jev + Android App 的边界

**已证实**：v3.5 真机示例要求设置 `api_key/base_url/model`，模型需理解截图并输出指定的 `<tool_call>` 包裹 JSON。脚本不预选模型规模或自动加载本地权重；可连接托管服务，也可连接自部署的兼容 API。兼容 OpenAI API 只是传输接口兼容，不等于任意模型可用。[入口与解析器](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py#L34)

**分析判断**：Jev 是已核实的文本/JSON 闭集决策模型，不能直接替换 GUI-Owl。需要新增 `App UI tree → 完整动作候选 → Jev 选择 → App 执行 → 新树核验`，同时复用 v3.5 的角色、图文历史和视觉动作逻辑。自由文本、规划、摘要和树外视觉仍由生成式模型或明确模板提供；App 本地设备接口、用户接管和任务会话需自行实现；产品编排位置以 ADR-0003 的独立 APK 为准。本段是工程方案，不是 MobileAgent 已发布的 App 能力。

## Benchmark：有能力证据，但不是产品可靠性保证

| 官方报告对象 | AndroidWorld | 补充 |
|---|---:|---|
| GUI-Owl-1.5-2B-Instruct | 67.9% | 当前 README |
| GUI-Owl-1.5-8B-Instruct | 69.0% | 当前 README |
| GUI-Owl-1.5-8B-Thinking | 71.6% | 当前 README |

来源：[v3.5 官方结果表](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/README.md#performance)。这些是作者报告，本次未复现；模型规模、单模型/多 Agent、步数与环境协议不同，不能直接横比。截图单步 grounding 分数不能当整段任务成功率。该 README 的 32B 两项 AndroidWorld 数字与 arXiv v1 表格不一致，本报告不选其中一个作为本项目基线。

## 开源和落地门槛

- 根仓库代码为 MIT；抽查 `GUI-Owl-1.5-8B-Instruct` 模型卡也标记 MIT。子目录仍有各自上游许可证，不能据根许可证推断所有第三方文件的授权。[仓库 LICENSE](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/LICENSE)、[模型卡](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct/raw/main/README.md)
- 当前真机示例要求 Android、开发者选项/ADB 调试、连接电脑、ADB Keyboard 和 VLM 服务；没有要求手机跑大模型，也没有声称普通 App 权限即可运行。iOS 不支持该真机路径。[部署说明](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/README.md#deploy-mobile-agent-v35-on-your-mobile-device)
- 开源推理/设备示例、权重和若干评测目录可见；README 的“评测代码待开源”TODO 尚未勾选，但已有 `android_world_v3.5` 代码，所以应按实际文件核对，不能只读 TODO。没有据此证明完整训练数据、训练基础设施、云手机服务也全部开放。[v3.5 目录](https://github.com/X-PLUG/MobileAgent/tree/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5)
- 本地权重部署需另配推理服务。8B 模型卡推荐 vLLM、上下文 32768、最多 5 张图；没有给出可信的最小硬件门槛（卡中测试硬件写作 “A100 with 96 GB”，本轮不将其作为最低要求）。云端 API 是另一部署方式。[8B 模型卡](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct/raw/main/README.md)

## 当前示例的具体工程问题

源码静态审阅发现：`build_messages` 给图片路径加 `file://`，而 `convert_messages_format_to_openaiurl` 将它原样传入 `image_to_base64 → PIL.Image.open`，未剥离 URI 前缀。正常本地文件路径在这条链路上会被当成错误文件名，应先修复并做真机冒烟验证，不能承诺 README 命令直接可跑。另有提示词声明的 `key`、`Menu/Enter` 未在主循环完整分派，以及缩放尺寸与设备原始坐标应统一的问题。[路径证据 L401–437、L504–512](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/utils.py#L401)、[动作分派](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py#L205)。这些是代码证据及其直接推断，本轮没有执行 demo 来确认故障栈。

本项目选择 v3.5 作为底座，公开 Fork 后提取 Android 相关角色与执行逻辑，再逐步接入 App 本地编排与设备能力、Jev 动作选择和树验证。原生单模型入口、四角色入口和生产改造需分别建立基线；源码可复用不等于独立手机能力已经实现。详见[当前项目方向](../project-direction.md)与[阶段验证方案](2026-09-22-stages-evaluation-and-flows.md)。
