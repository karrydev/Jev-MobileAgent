# 06 — 在模拟器中连接 App 并查看当前观察

**What to build:** 开发者启动 Android App，完成测试环境配对后，在服务端看到真实 Accessibility 观察与能力状态，并在 App 看到连接和权限反馈。

Blocked by: 01

Status: ready-for-agent

Execution: done
Owner: luna-android-observation
Branch: codex/v1-android-observation
Evidence: Android 模拟器与物理手机
Gate: 在模拟器中连接 App 并查看当前观察

## 前置与规格

[01 — 一个模拟任务从提交到独立判定完成](01-simulated-task-loop.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 在 Android 模拟器受控页面验证文字、状态、窗口、尺寸、旋转、时间与观察版本；不是以手写树代替 App 采集。
- [x] 权限撤销、空树和连接失败有可见原因，不能显示过期观察仍有效。
- [x] 提供可构建安装包、最小连接/权限界面与服务端读取入口，复用任务 01 契约。
- [x] 由 Agent 检查并准备可用本地工具链与模拟器；环境实际不可用则记录阻塞并继续离线任务，本票不凭构建成功关闭。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：01/02 已验收合入；协调者领取本批。范围：android-app/、独立 services/android_bridge/、contracts/android/、tests/test_android_bridge.py；不改 sim_loop、contracts/v1、根构建或其他工作线。共享最小封套语义需复用，Android 扩展独立版本化。 按 code-this 由新 Luna-max 实现，主 Agent 负责 Git，Terra-max 审查。

2026-09-22：Android App assembleDebug 成功；Pixel_5_API_32 实际采集 2 windows / 12 nodes、1080x2340、rotation 0。撤销权限返回 PERMISSION_UNAVAILABLE 空树；断连清理旧观察。主 Agent 复跑 bridge 5 项测试通过，Terra 正在审查。原始受控证据留本机 /tmp/jev-android-observation-final.json 与同名前缀截图；待审查通过后记录合入 SHA。

2026-09-22：用户要求后续切换物理手机并授权由 Agent 操作。OnePlus 8T / Android 14 受控页真实采集、权限撤销与断连清树已验证；已补固定 Gradle wrapper/bootstrap。审查发现服务重启但权限仍开启时旧树未清，以及异步旧响应可覆盖清屏，正在集中补齐 UI 代际保护，尚未验收合入。uiautomator dump 会干扰无障碍服务，因此正向证据改用 App HTTP 上传与设备截图；它仅用作明确标记的生命周期故障注入。

2026-09-22：实现 `27dc095` 已合入 main；Terra 对权限、构建、生命周期与旧回调代际保护定向复审 PASS。Python bridge 6 项通过（主树与新的 runtime 集成后亦通过）；Gradle 9.4.1 / JDK 17 / compileSdk 35 assembleDebug 成功。最终 APK SHA-256 `d834cc2ee6183a25930e5208b8fdc0fe62f4d9d789eeff798aceb9bf041aa894` 与 OnePlus 8T 已安装文件一致；最终真实受控页 version 89 / 2 windows / 15 nodes / 1080x2400 / rotation 0，文字与截图一致。真实权限撤销→空树、断连→清旧状态、服务销毁重建且权限仍启用→要求重新连接均通过。空树与服务端过期的负向协议由 bridge 测试覆盖，未声称所有 ROM/应用覆盖。原始证据本地 `/tmp/jev-phone-observation-final.json`、`/tmp/jev-phone-revoked.json`、`/tmp/jev-phone-disconnected.png`、`/tmp/jev-phone-lifecycle-cleared.png`；不提交私人屏幕材料。调试使用 USB reverse，尚未证明脱离 ADB，也未实现节点动作/截图产品接口。
