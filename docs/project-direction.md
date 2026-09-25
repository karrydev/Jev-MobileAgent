# Jev-MobileAgent：当前项目方向与决策

更新日期：2026-09-25。本文记录已经确定的方向，作为后续开发的入口。文档只保留当前方案。已完成公开 Fork、合入研究文档和工程技能配置、初始化 CodeGraph；Android App 的观察、输入、节点动作、截图与手势已在受控真机验证，模型基础访问已通过；真实角色协议、原版双入口基线和角色提取对照已完成；原版与提取版亮度任务严格判分均未成功。无关目录裁剪与复验、App+VLM 受控真机闭环已验收；中断核对与手动恢复已通过受控真机验收，以上基于旧 App + Python 设备桥。用户明确的独立 App 仅 VLM 编排已通过任务 26 拔线验收，现继续接入手机内 Jev 策略与完整恢复；最终集成交付仍待任务 28。

## 1. 已确定的方向

| 项目 | 当前决定 |
|---|---|
| 使用范围 | Android 手机自用，从项目开始即可公开，后续可独立开源发展；不上应用商店 |
| 框架版本 | 采用 MobileAgent v3.5，复用 Android 相关代码并提取需要的多角色能力 |
| 仓库来源 | 先公开 Fork 完整 `X-PLUG/MobileAgent`，保留 Git 历史、来源和许可证，再裁剪无关目录 |
| 裁剪范围 | 围绕 Android App 保留必要编排、模型适配、设备协议及评测资产；无需长期保留 PC、浏览器、旧版本和其它无关项目 |
| 后续演进 | 能合理升级时升级；不适合整体升级时，择取 feature/bugfix 或移植其实现意图；允许独立发展 |
| 设备能力 | App 通过无障碍服务采集布局树、执行节点动作和手势，必要时提供截图；首期不依赖 root、Shizuku 或在手机中运行完整 Android CLI |
| 模型分工 | Jev 做有限候选选择和结果分类；生成式模型继续提供规划、任意文本生成、摘要及视觉兜底 |
| 运行位置 | 任务编排、状态、观察、动作及核验在 Android App 内；App 直连模型供应商。安装并填写 Key 后可用，无需自建服务器、电脑、USB 或 ADB |
| 首版范围 | 单用户、单台手机、同一时间一个任务；多设备与多任务调度留到后续 |
| 异常恢复 | 断线、进程重启或人工介入后先暂停、重新观察并核对执行历史，用户确认后恢复；结果不明的动作不自动重放 |
| 推进方式 | 只规划阶段和验证方式，不预设降本、速度或成功率提升目标；是否改善由同条件实测决定 |

公开 fork 为 [karrydev/Jev-MobileAgent](https://github.com/karrydev/Jev-MobileAgent)，上游为 `X-PLUG/MobileAgent`；当前开发路线见[开发路线](development-roadmap.md)。

## 2. v3.5 具体复用什么

已审阅并实际采用的上游起点为 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`。后续模型与运行基线还需记录各自提交、配置和证据，不能把代码起点当作已通过的运行基线。

v3.5 的两个 Android 入口需要区分：

| 入口 | 现有能力 | 本项目用途 |
|---|---|---|
| `Mobile-Agent-v3.5/mobile_use` | 宿主 Python 通过 ADB 截图、调用 GUI-Owl、解析并执行动作，携带图文历史；没有独立四角色编排 | 原生真机参考与对照；复用模型请求、动作协议、历史处理和视觉执行逻辑 |
| `Mobile-Agent-v3.5/android_world_v3.5/android_world/agents` | `MobileAgentV3_M3A` 串联 Manager、Executor、ActionReflector、Notetaker 和 InfoPool | 提取需要的多角色能力；将 AndroidWorld 环境、固定屏幕尺寸、任务特例与生产逻辑分离 |

来源：[真机入口](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py)、[四角色调用入口](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3.py)、[角色与状态定义](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/android_world_v3.5/android_world/agents/mobile_agent_v3_agent.py)。

当前方案是基于 v3.5 的 Android 相关代码构建衍生项目，并保留需要的多角色能力。角色现已提取至 `agent_core/vlm/`，参考适配保留在 `services/original_baselines/`；同输入分支和真实参考运行已验证，App 通道集成已在任务 19 的受控真机任务中完成；当前将编排行为迁入 App，恢复和 Jev 接入以独立 APK 重新验收。上游没有可直接嵌入的独立 Android App；“保留 Android 相关代码”包含可移植编排行为和开发评测适配，不等于只留下一个现成 APK 文件夹。

仅接入 Jev 不会自动得到布局树、设备操作、用户接管或中断恢复。现有 Android 能力可复用，编排与恢复需迁入手机；规划、视觉和记忆行为继续使用生成式模型。

## 3. Fork、裁剪与上游同步

按以下顺序推进；第1–4项已完成，保留以下顺序作为来源与复验约定：

1. 公开 Fork 整个仓库并保留完整历史，记录上游地址与基线提交；将当前研究文档合入工作副本。
2. 固定模型配置，跑通选定 v3.5 入口并保存原始轨迹、费用和判分。若需修复原示例阻塞问题，单独记录补丁，区分原始源码与修复后的可运行基线。
3. 确定生产会复用的代码、依赖及评测引用；先提取 AndroidWorld 中需要的角色逻辑，再裁剪其余评测环境和无关目录。
4. 将目录整理与功能改造分开提交，重新运行基线，确认整理没有引入行为变化，然后接入 App 和 Jev。

| 处理 | 内容 |
|---|---|
| 保留 | 被实际使用的 v3.5 角色、状态、模型封装、提示词、动作协议与历史处理；App、必要评测适配与项目文档；Python 服务和设备桥暂留为开发参考 |
| 可移除 | 无实际依赖的旧版本、PC/网页执行器、桌面或网页 benchmark、大型演示素材和无关研究项目 |
| 外部引用 | AndroidWorld、MobileWorld 等完整环境及大型数据；优先记录版本、获取方式和适配入口，按需准备 |
| 始终保留 | Git 来源历史、所用文件的许可证与版权信息、上游基线、移植记录和必要第三方声明 |

实际删除清单在依赖检查后确定，不按目录名直接删除。评测材料不必全部留在主仓库，也不能因为整理目录丢掉可复现的任务和判分方式。[GitHub Fork 说明](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo)

减少后续同步成本主要依靠职责边界：设备访问集中到设备接口，Jev 独立适配，模型输出先转成统一动作，规划/验证调度集中管理。保留来源路径和提交记录，不为永久保持上游目录结构而保留无关代码，也不承诺以后可以无冲突合并。

后续发现有价值的上游变更时，先判断涉及哪些模块；独立补丁可以 cherry-pick，依赖或路径变化较大时按语义移植。记录原提交、采用方式、适配差异和验证结果。整版升级值得采用时再做整版对照；上游方向不再适用时，本项目继续独立演进。

**模型升级与框架升级分别验证。**更换 GUI-Owl 权重、GUI-Plus 服务版本或 Jev 版本，不必同时合并整版框架，但必须检查提示词、响应格式、坐标、历史输入和任务行为。

当前运行边界见 [独立 Android App](adr/0003-standalone-android-runtime.md)，它替代 [旧 App/服务端边界](adr/0001-device-and-orchestration-boundary.md)；[单手机与手动恢复](adr/0002-single-device-and-explicit-resume.md)继续有效。

## 4. App、Jev 与生成式模型的边界

App 提供统一观察：当前窗口、可访问节点的文字/语义/状态/边界、屏幕尺寸和观察版本，按需要附截图。App 依据当前子目标、布局树和已知参数构造完整动作候选，Jev 返回选择，程序将其映射为确定动作。自由输入内容来自用户、模板或生成式模型，不能要求 Jev 从候选 ID 生成任意文本。[Jev 能力边界](https://docs.typesafe.ai/models)

执行后，先等待页面变化并重新获取布局树：确定性条件优先由代码判断，语义判断可以交给 Jev，结果分为 `SUCCESS / FAILURE / PENDING / UNKNOWN`。这里的成功指本次动作的预期后置条件满足；任务完成还需检查整体完成条件。树内信息不足时再调用视觉模型，不能把“页面有变化”或“点击命令返回成功”视为任务成功。

无障碍节点不是完整内部 View 树。自绘、图像内容、语义缺失和部分 WebView 页面可能无法提供充分信息，需通过截图补充；截图也可能受系统或窗口限制。[Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

“按需采集截图”和“按需上传截图”分开处理。若保留原 Reflector 的前后图比较，应在动作前本地缓存前图，仅在需要时上传；执行后不能补拍历史画面。若希望完全按需采集，则另行验证“前树 + 动作 + 后图”的验证分支。规划、视觉动作和视觉验证都可能需要截图，不能只统计 Executor 的图像请求。

App 内执行入口继续落实观察版本检查、过期目标拒绝、动作去重、回执、超时、任务取消、用户暂停/恢复和模型断网处理。既有设备桥保留为开发参考；先验证手机内仅 VLM 行为，再逐步改变决策与验证策略。

## 5. GUI-Owl 1.5 的 API、部署与模型规模

`1.5` 是模型系列版本号，不是 1.5B 参数。现有官方公开集合包含：

| 名称规模 | 已公开变体 |
|---|---|
| 2B | `GUI-Owl-1.5-2B-Instruct` |
| 4B | `GUI-Owl-1.5-4B-Instruct` |
| 8B | `GUI-Owl-1.5-8B-Instruct`、`GUI-Owl-1.5-8B-Think` |
| 32B | `GUI-Owl-1.5-32B-Instruct`、`GUI-Owl-1.5-32B-Think` |

论文还提及 `235B-A22B`，既有核查没有在该公开集合找到相应 checkpoint，不能列为已可下载的选项。[官方模型集合](https://huggingface.co/collections/mPLUG/gui-owl-15)、[论文](https://arxiv.org/abs/2602.16855)

v3.5 真机脚本要求传入 `--api_key`、`--base_url`、`--model`，通过兼容 OpenAI 的 API 调用模型；没有默认选定的模型规模，也不会自行下载权重。该 API 可以来自托管服务，也可以来自自己用 vLLM 部署的推理服务。README 的本地 Transformers 示例使用 8B，不代表真机脚本默认加载 8B。[参数与调用入口](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py)、[部署说明](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct#deploy)

首期优先托管 API，准备百炼与 TypeSafe 账号，无需先购买 GPU。百炼 `gui-plus-2026-02-26` 是候选服务，但其参数量与公开权重的精确对应未披露，不能称其为已确认的 8B/32B 服务；最小坐标适配后的真实角色协议已验证；配置与全部失败见本地任务15–17的证据记录。OpenAI SDK 只表示接口兼容，不要求购买 OpenAI 额度。[GUI-Plus 官方接口](https://help.aliyun.com/zh/model-studio/gui-plus-interface-interaction-model)

真机脚本的可选 App 名称解析还有 `qwen-plus` 默认配置：直接映射失败时才走这条辅助路径。接入时需确认是否保留，或以 App 提供的包名映射替代；它不是 GUI-Owl 主模型的默认值。账号、设备与具体配置见[开发准备](research/2026-09-22-development-readiness.md)。

## 6. 验证计划与尚未证实的事项

按[当前开发路线](development-roadmap.md)推进：已完成的基线与裁剪 → 手机内仅 VLM 闭环 → Jev 动作选择 → 树结果验证 → 按需规划 → 最终独立 APK 和中文真机复核；[原阶段研究](research/2026-09-22-stages-evaluation-and-flows.md)作为来源保留。现有 AndroidWorld、AndroidControl、MobileWorld 和 Mobile-Eval-E 可以分工复用，但没有覆盖本项目全部阶段的现成测试套件。

下列事项仍需在开发中验证：

- v3.5 示例中的图像 `file://` 路径处理、动作声明与分派覆盖、截图缩放和原始设备坐标。这些是早期静态审阅发现；后续原版双入口基线、适配与失败已由任务 15–17 留证。新 Android 移植仍须单独验证，不能把旧入口结果当作手机内协议已全部兼容。[源码证据](research/mobileagent-evidence.md#当前示例的具体工程问题)
- 四角色代码脱离 AndroidWorld 后的行为一致性；固定尺寸和测试任务特例应显式处理，不能直接带入通用手机流程。
- 选定 API 对多图、角色提示词、动作格式、中文、thinking 与 usage 的支持，以及 App 获取真实树、截图和执行动作的覆盖情况。
- Jev 候选覆盖、错误选择、误报成功、等待状态与视觉回退；原 VLM 判断不能直接充当真实标签。
- 实际任务成功率、调用量、延迟和总费用。按实际入口、角色、模型与重试记录成本，不预设降本比例。[成本核算方法](research/2026-09-22-jev-cost-model.md)

独立 App 的仅 VLM 闭环已在任务 26 的受控中文输入和视觉点击中通过拔线真机验收；跨应用兼容性、手机内 Jev 策略、完整恢复及优化收益仍需后续实测，不能从这两个受控任务外推。
