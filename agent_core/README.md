# 离线策略切片

`agent_core` 是任务 11/12 的离线行为包。它不接入 `services/sim_loop`、Android 设备或真实 Jev；运行只需要 Python 标准库和仓库中任务 02 已验收的 `eval.engine`。

## 任务 11：候选选择

`build_candidates(observation, known_parameters)` 只读取当前观察和用户/模板已经提供的参数。每个候选都带有目标节点、完整参数、观察版本和中文/等价动作集合。回放文件只能给出候选 ID，`ReplaySelector` 不会从 ID 生成文本或创建候选。观察过期、观察时钟不可用、没有候选、缺少参数和非法 ID 都会返回 `VISUAL_FALLBACK`，并保留原因；没有可靠 `now` 时不会跳过过期检查。

`DeterministicEffectSimulator` 使用 fixture 中显式的效果规则模拟页面状态变化。它的回执与独立判分分开保存；候选选择报告随后在内存中适配任务 02 的三份 `tasks`/`traces`/`truth` 文档，并调用现有 `eval.engine` 生成独立动作和任务报告。

## 任务 12：四态核验

`RuleVerifier` 先检查回执、前后观察、加载状态、错误和动作后置条件。规则不能判断时才使用冻结的 `ReplayClassifier`。所有 `SUCCESS` 都需要前后观察截图；任一截图缺失时，回放分类器也只能得到 `UNKNOWN`。前截图缺失无法由后续视觉回退补造，控制器会带着该原因暂停。`VerificationController` 将四态映射为 `CONTINUE`、有界 `WAIT`、`VISUAL_FALLBACK` 或 `PAUSE`，等待到达上限会暂停。

动作核验和整体任务完成使用不同字段与不同真值记录。报告中的 `independent_task_completion` 仅在分类完成后由外部真值复制，绝不进入规则或分类器输入。

核验报告还提供 `confusion`，按最终分类状态与独立动作真值状态统计四态矩阵；`counts.control` 单独统计等待、回退和暂停分支。

## CLI

从仓库根目录运行：

```bash
python3 -m agent_core selection --split all
python3 -m agent_core selection --split dev --output /tmp/selection-dev.json
python3 -m agent_core selection --split holdout
python3 -m agent_core verification --split all
python3 -m agent_core verification --split dev --output /tmp/verification-dev.json
python3 -m agent_core verification --split holdout
```

每条命令默认使用 `agent_core/fixtures/` 下的三个输入文件。`--cases` 是决策或证据输入，`--replay` 是离线选择/分类回放，`--truth` 是独立参考输入；三者不能合并成一个供策略读取的 payload。cases 中的冻结 manifest 会校验策略 artifact 和 replay 的身份与 SHA-256；全量加载阶段会拒绝跨 dev/holdout 的 task 或 trajectory。`--now` 只用于选择管线的确定性过期检查。相同输入、冻结 artifact 和 Python 标准库环境会得到相同 JSON 字节内容。

## 证据边界

fixture 是合成离线样例，覆盖正例和无效 ID、空候选、过期观察、缺少参数、失败、等待、截图缺失、结果未知与任务未完成反例。`scope: offline-fixture-only` 明确它们只用于离线契约和回放行为；它们不代表真实模型、Jev 服务、Android ROM 或物理手机质量，也不会启用任何生产运行策略。
