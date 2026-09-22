# 16 — 分别运行原真机与四角色最小基线

**What to build:** 维护者分别运行一个固定小型真机单模型任务集与 AndroidWorld 四角色任务集，得到可复现的独立完成判分及轨迹。

Blocked by: 02, 14, 15

Status: ready-for-agent

Execution: done
Owner: luna-original-baselines
Branch: codex/v1-original-baselines
Evidence: 真实 API、AndroidWorld、物理手机
Gate: 分别运行原真机与四角色最小基线

## 前置与规格

[02 — 给成功、失败和未知任务生成独立判分报告](02-independent-evaluation.md)、[14 — 提供物理手机与首轮任务授权](14-human-phone-access.md)、[15 — 真实 VLM 能完成所需角色协议请求](15-live-vlm-compatibility.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 固定两入口各自代码、模型、提示词、设备/模拟器、任务、重复与预算，不能混成同一组收益数据。
- [x] 原版或最小修复版可运行，修复分开提交；报告全部失败和环境限制。
- [x] 使用具体 AndroidWorld 任务判据，基类默认返回成功不能当判分；真机按已确认 rubrics 验证。
- [x] 实际 AndroidWorld 环境由 Agent 优先准备；无法运行时保持本票未完成，禁止以离线回放代替基线。

## 范围与协调

原版双入口有限小样本，不在本票扩成大规模 benchmark。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-23：15 验证期间完成无模型调用的环境预备：已安装 API 33 Google APIs ARM64 系统镜像，建立独立 Pixel 6 AVD `JevAndroidWorldApi33`（console 5556 / gRPC 8554），实际启动到 sys.boot_completed=1；没有修改现有 AVD 或物理手机。隔离 Python venv 已安装 android_env 1.2.3、dm_env 1.6、protobuf 5.29.5、numpy 1.26.3 等，android_world.env.env_launcher 可导入。依赖快照保留本机 `/tmp/jev-androidworld-environment.txt`。这只是环境预备，任务、应用初始化和真实四角色基线未运行，本票保持 pending。启动验证后已正常关闭本轮专用模拟器，避免后台空转。

2026-09-23：14/15 已关闭，按 [冻结方案](../baseline-plan.md) 领取双入口运行工作。模型调用与物理设备由协调者串行控制；Luna 只开发最小有界运行适配与离线验证，保留上游入口，必要修复单独记录。

2026-09-23 环境实测：AndroidWorld env_launcher 实际连接专用 AVD，取得 2400×1080 截图与 18 个 UI elements；SystemBrightnessMax.initialize_task 后具体 is_successful 返回 0.0（screen_brightness=1），独立判分前置有效。macOS 下 gRPC INFO 日志污染 ADB stdout 曾引起 int 解析失败，进程启动配置 GRPC_VERBOSITY=ERROR、GRPC_ENABLE_FORK_SUPPORT=0 后复验通过；SSL_CERT_FILE 使用 certifi 的 CA bundle，TLS 验证保持开启。无需修改上游判分源码。环境初始化会影响 ADB reverse，物理手机联调必须串行并恢复反向端口。以上均未调用模型，也不作为基线通过。

2026-09-23 首轮实跑：AndroidWorld 在实际 API33 模拟器使用原 MobileAgentV3_M3A/episode_runner、具体 SystemBrightnessMax，初始分0、结束分0，5步/15次API后按约定停止，未完成亮度任务，费用估算 ¥0.1042035；反复无效滑动的失败轨迹完整保留。真机 mobile03 两次API，点击后独立新观察 ready→completed，但运行保护错误拒绝原版合法终止 answer，整轮仍记 stopped；正在做最小保护规则修复，不能将本轮记作整体通过。两次前置观察/焦点检查失败均未发送模型请求。每轮真机结束已锁屏并确认 Dozing。

2026-09-23 基线采集验收完成：运行器提交 `6e9f5d7`，保留全部上游源文件不变；仅包装 API 传输、坐标说明、预算和设备保护。Terra 有限审查通过；焦点读取与合法 answer 终止的实机修复后定向测试 14/14，通过 py_compile/diff check，修复前全量 122/122。mobile04 实际两次 API、一次原版 ADB 点击，独立新观察 ready→completed，合法结束，整体 success；费用 ¥0.015687，测试后确认 Dozing。最终合入 harness 与 mobile04 实跑 SHA-256 一致。AndroidWorld 5步任务失败保持不变，其模型与原角色/episode/任务判分均实际运行，原始轨迹已留本机；之后的修改仅影响 phone 焦点和终止保护，不改变 AW 路径。结果见 [真机成功](../evidence/original-baseline-mobile04.json)、[真机保护规则失败](../evidence/original-baseline-mobile03.json)、[AndroidWorld 任务失败](../evidence/original-baseline-androidworld01.json)。本项 done 表示双入口基线已经采集，不表示两项任务均成功，也不表示 App+VLM 通道已完成。全流程累计公开价格估算 ¥0.1552365，实际账单与免费额度未知。
