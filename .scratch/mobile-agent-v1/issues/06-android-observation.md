# 06 — 在模拟器中连接 App 并查看当前观察

**What to build:** 开发者启动 Android App，完成测试环境配对后，在服务端看到真实 Accessibility 观察与能力状态，并在 App 看到连接和权限反馈。

Blocked by: 01

Status: ready-for-agent

Execution: in-progress
Owner: luna-android-observation
Branch: codex/v1-android-observation
Evidence: Android 模拟器
Gate: 在模拟器中连接 App 并查看当前观察

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [ ] 在 Android 模拟器受控页面验证文字、状态、窗口、尺寸、旋转、时间与观察版本；不是以手写树代替 App 采集。
- [ ] 权限撤销、空树和连接失败有可见原因，不能显示过期观察仍有效。
- [ ] 提供可构建安装包、最小连接/权限界面与服务端读取入口，复用任务 01 契约。
- [ ] 由 Agent 检查并准备可用本地工具链与模拟器；环境实际不可用则记录阻塞并继续离线任务，本票不凭构建成功关闭。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：android-app/、独立 services/android_bridge/、contracts/android/、tests/test_android_bridge.py；不改 sim_loop、contracts/v1、根构建或其他工作线。共享最小封套语义需复用，Android 扩展独立版本化。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：Android App assembleDebug 成功；Pixel_5_API_32 实际采集 2 windows / 12 nodes、1080x2340、rotation 0。撤销权限返回 PERMISSION_UNAVAILABLE 空树；断连清理旧观察。主 Agent 复跑 bridge 5 项测试通过，Terra 正在审查。原始受控证据留本机 /tmp/jev-android-observation-final.json 与同名前缀截图；待审查通过后记录合入 SHA。
