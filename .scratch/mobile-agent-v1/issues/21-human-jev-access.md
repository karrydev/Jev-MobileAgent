# 21 — 验证已提供的 Jev 实测访问条件

**What to build:** 使用用户已提供的 Jev 凭据运行已准备的最小访问探针，确认真实接口可访问并记录费用/错误，为 App 内接入提供事实依据。

Blocked by: none

Status: ready-for-agent

Execution: done
Owner: main-live-probe
Branch: main
Evidence: 真实 Jev 访问探针
Gate: 已提供 Jev 凭据可用于实测

## 前置与规格

无技术前置；凭据已提供，可与任务 26 并行。

[首版规格](../spec.md) · [任务索引](../index.md) · [并行协作](../../../docs/parallel-development.md)

## Acceptance criteria

- [x] Agent 已准备配置样例、最小中文/错误探针与报告入口。
- [x] 用户已提供包含 `JEV_API_KEY` 和 `JEV_VLM_API_KEY` 的本地测试 env，变量存在；尚未将此事实当作 API 可用。
- [x] 用 `services/jev_probe/` 做有限实际访问/中文探针，记录认证、响应和 usage；失败先分类，仅在确认账号/额度缺口时请求用户。
- [x] 本项仅关闭访问门槛，接口/质量与收益由后续真实任务验证。

## 用户参与触发条件

测试统一读取用户指定的 `/Users/liangkairui/Repo/jev-test.env`，不提交文件或打印 Key；沿用首轮总预算 ¥10，包含既有调用与未知用量预留。正常用户在 App 内输入 Key（任务 26），不需要 env 文件。仅在实际探针证明缺少账号授权或额度时，说明原因与获取方式再请求用户。

## 交付证据

记录最终合入 SHA、验证命令、契约与环境版本、实际结果和未验证项。模拟证据只关闭模拟任务；真实能力缺失时不能记为通过。共享接口变更由集成负责人串行合入。

## Comments

2026-09-22：用户已确认测试边界、任务拆分与延后人工准备；本票已发布，尚未实施。

2026-09-24：Agent访问材料已准备 services/jev_probe/（默认dry-run、显式live、中文choice与可选错误探针、JSON报告）。7项离线HTTP测试通过，Sol定向复核关闭错误配置URL凭据回显与401/429误判；尚未调用真实Jev，不将本票记为done。待20本地恢复/部署准备后尽量合并请求所需外部条件。

2026-09-25：用户已提供统一测试凭据文件，改为 ready-for-agent；本轮仅修订规格，未运行真实 Jev 探针，Execution 保持 pending。

2026-09-25：清理其余文档旧边界后领取；按 code-this 推进。主 Agent 独占真实模型请求、凭据、设备、集成证据与 Git；实现者不提交或推送。

2026-09-25：真实访问通过，见 [jev-access-live01.json](../evidence/jev-access-live01.json)。固定 jev-1.13.0，中文 choice 返回 click_continue/HTTP200，usage 输入378/输出35；显式错误探针HTTP400，无重试。沿用已审查的探针实现，未改源码。按官方输入标价估算 USD0.000015876，实际账单未知，人民币预算保守预留0.01元；不将其记作 App 已集成或质量验收。
