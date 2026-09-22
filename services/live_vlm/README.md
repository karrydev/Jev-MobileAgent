# Live VLM compatibility probe

这个探针只验证一个配置好的 GUI-Plus 模型是否能处理项目实际要用的协议。它在运行时动态加载仓库内 MobileAgent v3.5 的原始手机提示词/`parse_action`，以及 AndroidWorld 四角色的原始 `get_prompt`/`parse_response`：`Manager`、`Executor`、`ActionReflector` 和 `Notetaker`。探针没有复制或改写这些提示词，也不把 OpenAI 兼容 SDK 当作角色兼容。

默认会准备五个请求：原版手机 `<tool_call>` 请求和四个角色请求。只有显式传入 `--execute` 才会发送网络请求；默认最多发送 5 个请求，硬上限为 6 个，默认 `max_tokens=1024`，硬上限为 1024。探针不自动重试。每次请求记录 HTTP 状态、原始 JSON 响应或错误文本、原始响应内容、解析结果、usage 是否缺失、估算费用和模拟执行证据；报告不会写入认证头或 API Key。

先离线检查配置和请求预览：

```bash
python3 -m services.live_vlm
```

真实试跑需要完整的 chat-completions endpoint、模型名和运行环境中的 Key。北京 GUI-Plus 的例子如下；Key 只通过环境变量提供：

```bash
SSL_CERT_FILE="$(python3 -c 'import certifi; print(certifi.where())')" \
JEV_VLM_API_KEY='(从受控环境读取，不要写入命令历史)' \
python3 -m services.live_vlm \
  --endpoint 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' \
  --model gui-plus-2026-02-26 \
  --credential-env JEV_VLM_API_KEY \
  --budget-cny 0.2 \
  --max-requests 5 \
  --max-tokens 1024 \
  --execute \
  --output .scratch/live-vlm-report.json
```

`--output` 只写本地报告；不要把报告或命令中的凭据提交。图片夹具是程序生成的有效 64×64 红/蓝 PNG，报告包含尺寸和 SHA-256。手机原版解析器保持严格行为：它要求 `<tool_call>\n` 和原始 JSON 结构；解析失败会报告为该角色不兼容，不会用宽松解析补救。

原版 `mobile_tool_call` prompt 明确声明屏幕分辨率为 1000×1000；四角色的原始 prompt 不包含这个坐标范围约束。默认探针保留四角色 prompt 原样。如需在探针中显式测试适配提示，可加入 `--coordinate-adaptation`；它只向四角色请求追加一段说明，要求两轴使用 0–1000 的 normalized 坐标，不提供目标、答案或中心坐标，手机原版请求保持不变。适配模式会在报告 `config.coordinate_adaptation` 中记录为 enabled，并把追加文本保存在对应角色的 prompt 中。

`mobile_tool_call` 或 `executor` 返回点击动作时，探针把 0–1000 坐标映射到已有 `services.sim_loop.device.SimulatedDevice` 的 `start-button`，先获取观察，再交给模拟设备执行，最后重新观察并查询动作历史。只有真实设备回执为 `EXECUTED` 且页面状态变为 `done` 才算独立确认。这只是离线动作映射/后置条件证据，不是 Android 真机证据。真实模型失败、角色解析失败、HTTP 错误或 usage 缺失都会保留在报告中；因此“请求成功”不等于四角色兼容通过。
