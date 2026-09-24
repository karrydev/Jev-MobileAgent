# 开发前准备：仓库、模型账号与设备环境

> 2026-09-25 运行边界修订：产品以 [ADR-0003](../adr/0003-standalone-android-runtime.md) 和[当前路线](../development-roadmap.md)为准。原版框架、外部工具与核查时的事实保留；旧桥接阶段不代表独立 App 已交付。

核查日期：2026-09-22。本文为准备清单；公开 Fork 与工程初始化已完成。本次尚未购买额度、安装模型运行依赖或调用付费模型。当前选型为 v3.5、公开 Fork 后裁剪、按需整版升级或择取补丁，完整决定见[项目方向](../project-direction.md)。

实际请求时机以[已确认任务](../../.scratch/mobile-agent-v1/index.md)为准：先推进离线与模拟器，VLM/手机在首次真实联调前请求，Jev 在仅 VLM 闭环后请求，当前已撤销服务器准备要求；21 使用已提供 Jev 凭据，26/27 实现手机内运行。下列账号信息是核查时的参考，使用前由实施任务重新验证，不要求用户现在办理。

## 1. 仓库起步方式

已确定从开始即公开，先 Fork 完整 `X-PLUG/MobileAgent`，再围绕 Android App 裁剪。GitHub Fork 的对象是仓库；只复制一个目录属于代码提取，不保留完整的 fork/upstream 关系。Fork 后可以删除不需要的工作树目录，同时保留来源与 Git 历史。[GitHub Fork 说明](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo)

开发基于 `Mobile-Agent-v3.5`：`mobile_use` 提供真机截图/动作示例，完整四角色位于 `android_world_v3.5`。先建立选定入口的基线，提取需要的角色与状态代码，再移除无依赖的旧版、PC、浏览器和其它无关目录；AndroidWorld 中需要的代码尚未提取前不能整目录删除。目录整理与功能改造分别提交，整理后重跑基线。

本地 `Jev-MobileAgent` 已完整克隆公开 fork，原有 14 份研究与方向文档已保留并纳入版本管理。`origin` 指向 `karrydev/Jev-MobileAgent`，`upstream` 指向 `X-PLUG/MobileAgent`；实际采用的上游起点为 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`。此处原指研究时状态；当前基线和设备阶段结果见任务 01–20，独立 App 待 26/27 验收，实施顺序见[开发路线](../development-roadmap.md)。

当前职责划分（Python 目录仅作开发参考，实际状态见工程入口）：

```text
Jev-MobileAgent/
  agent_core/      # 已提取的 Python 角色与行为参考
  android-app/     # 现有 Android 工程；设备能力及待迁入的本地编排
  services/        # 开发参考、旧设备桥和模型访问探针
  eval/            # 必要评测适配、用例、统计和外部环境版本记录
  docs/            # 已有研究、设计、上游来源与移植记录
```

实际使用的文件保留许可证和版权信息。框架升级有价值且成本合理时整体升级，否则 cherry-pick 或按语义移植 feature/bugfix，允许独立演进；不要求长期保持完整上游目录布局。模型升级单独检查协议和行为。

不需要安装上游所有版本的依赖。先为选定入口建立隔离环境和锁定依赖；完整 AndroidWorld/MobileWorld 可以作为外部固定版本环境单独准备。

## 2. MobileAgent 实际使用什么模型

MobileAgent 是框架，GUI-Owl 是其模型系列。选定的 v3.5 配套 GUI-Owl-1.5；`1.5` 是版本号，不是 1.5B 参数。[v3.5 说明](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/README.md)

| 公开名称规模 | 当前公开变体 |
|---|---|
| 2B | Instruct |
| 4B | Instruct |
| 8B | Instruct、Think |
| 32B | Instruct、Think |

共六个 checkpoint。论文提及 `235B-A22B`，既有核查未在官方集合找到相应可下载 checkpoint。[模型集合](https://huggingface.co/collections/mPLUG/gui-owl-15)、[论文](https://arxiv.org/abs/2602.16855)

v3.5 真机入口 `mobile_use` 要求提供 `--api_key`、`--base_url`、`--model`，通过 `GUIOwlWrapper` 调 API；没有默认选定的模型规模，不会自动下载模型。可以连接托管 API，也可以先用 vLLM 自部署再调用其 API。README 的本地 Transformers quickstart 使用 8B，是另一种使用示例。[真机参数](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/run_gui_owl_1_5_for_mobile.py)、[模型调用封装](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/mobile_use/utils.py#L480)、[自部署说明](https://huggingface.co/mPLUG/GUI-Owl-1.5-8B-Instruct#deploy)

四角色实现在 AndroidWorld 入口中，通过不同角色提示词使用生成式模型，首期可以共用一个兼容的 VLM 服务，不必为规划与视觉分别购买两套模型。真机脚本另有可选 App 名称解析，直接映射失败时使用辅助模型，默认 `qwen-plus`；可配置或改为 App 提供的包名映射，不应误认为它是 GUI-Owl 主模型默认值。

使用 OpenAI Python SDK 只是协议兼容，不要求购买 OpenAI 额度。Jev 是单独的 TypeSafe 服务，使用自己的客户端与 endpoint。

## 3. 账号与购买入口

| 服务 | 开发准备 | 核实的当前计费与限制 |
|---|---|---|
| 阿里云百炼 GUI-Plus | 开通北京地域业务空间，取得该地域 API Key；在模型页确认 `gui-plus-2026-02-26` 可用；阿里云账户支持按量支付 | 官方价输入 ¥1.5 / 百万 token、输出 ¥4.5 / 百万 token；具体账户可用额度、免费额度有效期以控制台为准 |
| TypeSafe Jev | 注册官方控制台，创建 key，查看账户的计费/支付与可用额度状态；开发固定 `jev-1.13.0` | 输入 $0.042 / 百万 token，输出免费；公开资料未确认具体充值套餐、付款方式及该账户是否可立即开通 |
| ModelScope API-Inference（可选） | 官方模型页查看 API 可用性并创建访问 token | 适合试用 GUI-Owl 开源模型；本次未实际调用，免费额度与具体模型权限以登录后页面为准 |

入口与证据：

- [百炼北京 GUI-Plus 模型页](https://bailian.console.aliyun.com/cn-beijing?tab=model#/model-market/detail/gui-plus-2026-02-26)、[API Key 管理](https://bailian.console.aliyun.com/settings/api-key)、[阿里云账户充值](https://billing-cost.console.aliyun.com/fortune/fund-management/recharge)、[GUI-Plus 官方价格](https://help.aliyun.com/zh/model-studio/gui-plus)。
- [TypeSafe 控制台](https://console.typesafe.ai/)、[创建 API Key](https://console.typesafe.ai/keys)、[快速开始](https://docs.typesafe.ai/introduction/quickstart)、[模型与价格](https://docs.typesafe.ai/models)。
- [ModelScope GUI-Owl-1.5-8B-Think](https://modelscope.cn/models/iic/GUI-Owl-1.5-8B-Think)，由 [MobileAgent 官方 README](https://github.com/X-PLUG/MobileAgent) 链接。

先完成无凭据探针与离线链路，再按真实能力关口分别请求 VLM 和 TypeSafe 账号；以托管推理开始接入，不预先购买 GPU。需要精确复现指定 GUI-Owl-1.5 权重时，再确认其可用托管服务或自部署路径；GUI-Plus 商业 ID 的参数量及公开 checkpoint 对应未披露，不能直接声明等同于开源 8B、32B 或论文的 235B-A22B。

百炼当前文档推荐配置：

```text
model: gui-plus-2026-02-26
SDK base_url: https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
API Key: 北京地域的 DASHSCOPE_API_KEY
```

`WorkspaceId` 来自业务空间。SDK 的 base_url 不追加 `/chat/completions`。[官方模型 API](https://help.aliyun.com/zh/model-studio/gui-plus-interface-interaction-model)

Jev 使用 `POST https://api.typesafe.ai/v1/systemone`。用户在 App 输入并加密保存模型 Key，App 直接调用模型端点。开发探针统一从用户指定的 `jev-test.env` 读取 `JEV_API_KEY` / `JEV_VLM_API_KEY`；env 不随 APK 交付。仓库只放不含 key 的配置样例。

### 首项开发检查：模型兼容性

取得可用 key 后，先验证选定 v3.5 入口及模型协议；采用四角色时，分别发送规划、动作、反思、可选记忆的最小请求，确认多图输入、返回格式、坐标、中文、thinking 字段和 usage 是否可用。再运行原流程形成该模型下的基线；真机单模型入口与四角色入口分别标记，不能共用一组调用次数假设。

官方明确 GUI-Plus 不同版本需要不同系统提示词；OpenAI 兼容只证明调用协议可以接入，不保证 v3.5 各入口的 prompt 和解析器直接兼容。[官方使用指南](https://help.aliyun.com/zh/model-studio/gui-automation)

不要把旧 `/api/v2/apps/gui-owl/gui_agent_server` 当作模型推理接口：它提供封装的 UI Agent 服务，会影响我们对角色和 Jev 路由的控制。[旧 UI-Agent 接口](https://help.aliyun.com/zh/model-studio/ui-agent-api)

## 4. 设备、开发和评测环境

| 准备项 | 何时需要 | 具体用途 |
|---|---|---|
| Android 11+ 手机与 USB 数据线 | 安装包就绪、首次真实基线前 | 安装 App、跑原 ADB 基线、采集真实树；Accessibility 截图 API 从 API 30 提供，截图仍受窗口和系统限制 |
| 手机开发者选项、USB 调试；后续手动启用本 App 的无障碍服务 | 原版基线及 App 联调 | 初期 ADB 用于安装/调试和对照；正式设备观察与执行逐步迁移到 App |
| Android Studio、SDK/Platform Tools、配套 JDK | App 开发 | 编译现有 Android App、设备日志、模拟器；纯无障碍方案无需预先配置 root 或 Shizuku |
| Python 隔离环境与 Git | 开始阶段 | 运行 MobileAgent、模型接口和设备桥；先在现有开发机运行服务即可 |
| 目标 App 与测试账号、具体任务和完成条件 | 首次真机采集前 | 区分模型问题、设备问题和登录/初始状态问题；提供中文任务样本 |
| AndroidWorld 模拟器 | 基线评测阶段 | 独立初始化和成功判定，与真实手机用例分开 |
| Linux/KVM/Docker 环境 | 后续使用完整 MobileWorld 时 | 当前 MobileWorld 的完整环境要求；不用一开始就部署所有评测基准 |
| 手机互联网与供应商 HTTPS API | 独立 App 实测 | App 内编排直连模型，无需用户自建服务器 |

v3.5 真机示例的文本输入使用 ADB Keyboard，跑原版基线时按 README 安装配置；App 改造后优先使用节点 `ACTION_SET_TEXT`，不把 ADB 输入法作为最终方案前提。

来源：[Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[Android Studio](https://developer.android.com/studio)、[原版设备准备](https://github.com/X-PLUG/MobileAgent/blob/11cea575561fb7800b5fb6b6cafa56f7a91de11f/Mobile-Agent-v3.5/README.md)、[MobileWorld 环境要求](https://github.com/Tongyi-MAI/MobileWorld)。

准备顺序：固定 v3.5 来源 → 模拟闭环、离线评测与 App 模拟器 → 按需请求 VLM 和手机 → 验证协议并建立原版基线 → 提取、裁剪与复验 → 真实 App+VLM → 按需请求 Jev → 策略对照 → 最终独立 APK 验收。注册、支付、设备授权由用户完成；仓库整理、代码适配、环境配置与验证在正式开始开发后执行。
