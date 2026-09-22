# 08 — 手势与截图支持完成受控页面任务

**What to build:** 当受控页面需要滑动、长按、系统导航或坐标动作时，测试策略可经 App 完成操作并获取关联截图，旋转后仍能核对结果。

Blocked by: 07

Status: ready-for-agent

Execution: done
Owner: luna-android-visual-task
Branch: codex/v1-android-visual-task
Evidence: 物理手机受控页面
Gate: 手势与截图支持完成受控页面任务

## 前置与规格

[07 — 从 App 发起中文输入任务并控制执行](07-android-node-task.md)

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] 用固定的小型页面集验证所需手势/系统动作；坐标经过旋转、缩放、系统栏/窗口偏移后命中正确区域。
- [x] 按需上传的前后图关联观察，动作前图真实预留；分别记录截图采集与上传，无法截图返回原因。
- [x] 截图缺失、输入法、多窗口和语义缺失分支可演示；不把受理回执当成页面效果。
- [x] 产出能安装的 App 与手机授权步骤，真实 ROM 验收由后续真机任务完成。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-22：07 已验收，领取手势/截图完整切片，按用户要求在已授权物理手机验证。修改范围 Android App、Android bridge、Android 契约与直接测试；沿用既有控制和节点绑定保护，不改原版角色或模拟运行器。由于运行环境已达子 Agent 总创建上限，复用现有 Luna-max 与 Terra-max，主 Agent 统一 Git 和验收。

2026-09-23：实现 `4cf526c` 经 `fbf22ab` 合入 main。Luna-max 实现，Terra-max 审查并关闭旋转重复转换、窗口/遮挡约束、截图计数与缺失原因不可见的问题；手势路径遮挡修复定向复审 PASS。主协调者复跑 Android bridge 18/18，安装产物来自通过的 Gradle debug 构建，`git diff --check` 通过。APK SHA-256 `8064338b13c745d912fa6bc9063c2acba5de2e29c4a2e798d23aad61d580285f` 与 OnePlus 8T / Android 14 已安装包逐字一致。Android 契约版本 1.0。

真机结果：

| 用例 | 真实结果与证据 |
| --- | --- |
| 节点长按 / 系统返回 | visual-long-04 obs1576→1577、visual-back-01 obs1534→1535，独立页面状态符合预期，均 SUCCEEDED |
| 自绘无语义目标坐标点击 / 滑动 | visual-coordinate-01 obs1605→1606、visual-swipe-05 obs1660→1661，前后图真实采集，页面分别变为 coordinate_tap_completed / swipe_completed |
| 双向横屏与窗口偏移 | visual-rotate1-02 obs1731→1732、visual-rotate3-01 obs1742→1743 均 SUCCEEDED；2400×1080，rotation 1/3；前者真实窗口 x=103，后者右侧 inset=103，未重复旋转/平移坐标 |
| 模型坐标缩放 | scaled-coordinate-01 obs1789→1790；本地策略夹具把模型帧设为 540×1200、坐标(270,600)，App 映射到屏幕(540,1200)，真实命中并 SUCCEEDED |
| 截图上传丢失 | missing-after-02 obs1763→1764：AFTER 图上传被本地夹具注入 503，动作 EXECUTED，但任务 PAUSED、verification=null、action_result_unknown=true；capture_count 11→12，upload_count 11→11，after_visual=UNAVAILABLE 并保留原因。测试后取消释放占用 |
| 输入法与多层窗口遮挡 | ime-stale-landscape-01：真实 BEFORE obs1805 采集后打开手机输入法，较高窗口覆盖路径；回执 REJECTED / stale_observation，未执行手势。竖屏输入法不覆盖目标时坐标仍命中；不把任意触摸都宣称为自动暂停 |

前后图由 App 的 Accessibility takeScreenshot 获取并按 observation id/version 上传，ADB screencap 只用于外部查看。独立页面状态核验曾真实拒绝两类 EXECUTED 但无正确效果的用例（长按未绑定处理、横屏夹具落点越界），修复后重新通过；没有将执行回执当作成功。

原始图片、JSON 与临时故障/缩放策略夹具保留本机 `/tmp/jev08-*`、`/tmp/jev-result-*`、`/tmp/jev08-evidence-bridge.py`，不入公开仓库。系统旋转设置已恢复原值 free。真实多窗口证据为输入法高层窗口覆盖；任意应用分屏、FLAG_SECURE 截图拒绝、多 ROM、真实 VLM 与脱离 ADB 尚未验收，继续留给对应真机/最终门禁，不扩大本票结论。
