# 独立合成评测

这里是任务 02 的独立、离线评测切片。它用一个小型合成页面集验证判分器本身的边界，不依赖模型、Android 设备、ADB 或第三方包。评测器从外部页面真值读取动作后置条件和任务完成结果；Agent 的回执或自报成功不会成为正确标签。

## 运行

在仓库根目录执行：

```bash
python3 -m unittest discover -s eval/tests -v
python3 -m eval.cli --split all --output /tmp/jev-independent-eval-report.json
python3 -m eval.cli --split dev
python3 -m eval.cli --split holdout
```

不传 `--tasks`、`--traces` 或 `--truth` 时，CLI 使用 `eval/fixtures/` 下的合成输入。指定 `--output` 会写入稳定格式的 JSON；省略它则写到标准输出。相同输入、版本和 Python 标准库环境会得到相同的报告字节内容。

报告将两个结论分开保存：每个步骤的 `action_verification` 表示动作后置条件（`SUCCESS`、`FAILURE`、`PENDING` 或 `UNKNOWN`），`task_completion` 表示整体目标是否完成。一个动作可以是 `SUCCESS`，而整体任务仍因没有完成后续目标而是 `FAILURE`。`PENDING` 用于页面加载等有界等待；`UNKNOWN` 用于缺少足够外部证据。`failure_class` 只在有失败或环境证据时标为 `agent` 或 `environment`。

## 输入格式

评测器故意使用三个独立文件，格式版本分别是：

- `jev-independent-evaluation/tasks-v1`：任务 ID、轨迹 ID、`dev`/`holdout` 划分、初始状态、用户输入、整体目标，以及供 Agent 使用的无真值 `decision_payload`。
- `jev-independent-evaluation/traces-v1`：Agent 看到的观察、动作、回执和观察后状态。轨迹不携带判分标签。
- `jev-independent-evaluation/truth-v1`：独立页面真值来源、环境状态、每个动作的后置条件证据和任务终局证据。

`task_id` 与 `trajectory_id` 必须一一对应，并且不能跨 `dev` 与 `holdout` 复用。当前 `decision_payload` 使用显式的 `jev-independent-evaluation/decision-payload-v1` schema，只允许以下结构：

```json
{
  "instruction": "non-empty string",
  "initial_observation": {
    "page": "non-empty string",
    "query": "optional non-empty string",
    "visible_controls": ["string"]
  },
  "available_actions": ["string"]
}
```

每一层的未知字段都会被拒绝，因此 `observation_after`、future/reference/backend 别名或嵌套字段不能通过输入校验。字符串内容本身保持不透明；评测器只保证允许的字段集合和类型，不声称能对任意自由文本做语义上的“无答案”证明。Agent 轨迹仍拒绝已知的评测器专用键，并且每条轨迹的 `step_id` 集合必须与对应 truth 的动作标签集合完全一致；缺失或多出的动作会产生输入错误，不会静默漏评。加载器也会检查真值初始状态与任务清单一致。

合成样例明确包含：

- 两步成功任务；
- 第一个动作成功、但整体目标未完成；
- 回执被接受但页面没有效果；
- 页面仍在加载；
- 操作后外部证据缺失；
- 页面环境启动失败。

这些是判分器边界样例，不是模型或手机性能结果。报告中的 `truth_source` 和 `evidence` 只用于独立评测输出，后续任务 10 可以将运行时轨迹适配到这个切片的输入格式，但本目录不定义或修改共享运行协议。

## 实验模板

`experiments/v1-template.json` 是版本化的首轮真实评测条件模板。它列出了任务/轨迹冻结、重复次数、步骤/时间预算和回归规则需要在真实评测前确定的项目，并给出一组建议数值。文件标记为 `proposal_not_user_approved`，所有数值都标有 `numeric_values_are_proposals`；它们不是用户已经批准的放行阈值，也不能直接当成产品承诺。

建议在真实能力联调前由评测负责人明确冻结：代码、模型、提示词、设备、App、环境、任务与留出划分、重复次数、预算、独立判据、回归允许值和放行责任人。这里的合成报告只关闭离线判分行为，不能关闭真实 API、模拟器、物理手机或最终端到端验收。
