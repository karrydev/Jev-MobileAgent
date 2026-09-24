# Jev 访问前置探针

这是 ticket 21 的最小访问材料：标准库 HTTP 客户端、一个很小的中文 `choice` 请求和一个显式开启的无效请求探针。它只验证接口形状、认证响应、`usage` 和错误分类，不接入生产策略、手机或 ADB。

默认 dry-run，不联网，也不读取 `vlm.env` 或本机其他 secret：

```bash
python3 -m services.jev_probe --output /tmp/jev-probe-report.json
```

配置样例在 `config.example.json`。真实试跑前请在受控运行环境设置 `JEV_API_KEY`，并最终核对 endpoint 和 `jev-1.13.0`：

```bash
JEV_API_KEY='由用户在受控环境提供' \
python3 -m services.jev_probe \
  --config services/jev_probe/config.example.json \
  --live \
  --output /tmp/jev-probe-live.json
```

只有显式加入 `--error-probe` 才会在主请求完成且 `usage` 完整后再发一次故意无效请求；不自动重试。任何 HTTP、协议或缺失 `usage` 都会停止并写入报告。报告只包含请求摘要、HTTP 状态、usage、耗时和脱敏错误，不包含 Authorization、API key 或原始响应；USD 费用保持 `unknown`，不据文档价格假算。

2026-09-25 已通过真实 Jev 访问探针（中文选择 HTTP200、无效请求 HTTP400，见 `.scratch/mobile-agent-v1/evidence/jev-access-live01.json`）；访问结果与 mock HTTP 测试不证明实际任务质量、延迟或收益。
