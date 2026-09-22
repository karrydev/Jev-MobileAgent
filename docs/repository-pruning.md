# 仓库裁剪清单

更新：2026-09-23。本文记录任务 18 的目录裁剪、依赖核查、来源保留和复现入口。核查基线为工作分支起点 `170f30c`；删除不改写 Git 历史，也不改变配置、凭据或本地未跟踪文件。

## 核查方法

候选范围先由 `git ls-files` 固定，再用 `git grep -I` 在候选目录之外检查路径、导入和文字引用。生产与回归入口重点检查了 `agent_core/`、`android-app/`、`contracts/`、`eval/`、`services/` 和 `tests/`。没有初始化 CodeGraph：本票的依赖边界是已知目录和字面路径，使用 tracked-files metadata 与文本核查即可复现。

结果如下：保留代码只引用当前的 `Mobile-Agent-v3.5`、`agent_core`、`services` 和测试/契约目录；没有对下列旧目录的本地导入或运行时路径依赖。根 README 与旧根 `index.html` 是旧系列目录的本地展示引用，已分别重写和删除。研究文档中的同名链接都是固定到上游提交的外部资料链接，保留作 provenance，不表示本地运行时依赖。

## 已移除

删除数量和大小按删除前工作树中的 tracked files 统计。

| 路径 | tracked files | bytes | 判定 |
| --- | ---: | ---: | --- |
| `PC-Agent/` | 31 | 12,029,908 | PC 入口，无保留代码引用 |
| `Mobile-Agent-v1/` | 38 | 10,047,307 | 旧手机版本，无保留代码引用 |
| `Mobile-Agent-v2/` | 14 | 4,411,352 | 旧手机版本，无保留代码引用 |
| `Mobile-Agent-v3/` | 577 | 27,444,014 | 非当前 v3.5 版本，无保留代码引用 |
| `Mobile-Agent-E/` | 59 | 312,215,088 | 自进化手机项目与演示数据，无保留代码引用 |
| `GUI-Critic-R1/` | 9 | 7,986,002 | 独立评估项目，无保留代码引用 |
| `UI-S1/` | 432 | 10,676,008 | 训练/评测项目，无保留代码引用 |
| `index.html` | 1 | 31,880 | 旧总览演示页，引用已移除系列 |
| `assets/aliyun.png` | 1 | 39,592 | 仅旧根 README/演示页使用 |
| `assets/framework.png` | 1 | 7,544,342 | 仅旧根 README/演示页使用 |
| `assets/logo.png` | 1 | 942,133 | 仅旧根 README/演示页使用 |
| `assets/result.png` | 1 | 15,840,321 | 仅旧根 README/演示页使用 |
| `assets/tongyi.png` | 1 | 22,478 | 仅旧根 README/演示页使用 |
| **合计** | **1,166** | **409,230,425** | **384,809,679 bytes 来自七个旧目录** |

`assets/gui_owl_15_logo.png` 保留（4,384,616 bytes）：`Mobile-Agent-v3.5/README.md` 与 `README_zh.md` 仍通过 `../assets/gui_owl_15_logo.png` 引用它。它不是无引用的根演示资产，不能随根演示图片一起删除。

## 保留与来源

- `Mobile-Agent-v3.5/` 全树保留（删除前 603 个 tracked files、12,114,623 bytes），包括 `mobile_use` 原版手机入口、`android_world_v3.5` 参考环境、`grounding_and_kb`、各自 README 和许可证。没有做环境下载、模型权重或更细粒度的 v3.5 裁剪。
- `agent_core/` 保留提取后的 Manager、Executor、ActionReflector、Notetaker/InfoPool 状态与离线选择/核验实现；来源和文件哈希见 `agent_core/vlm/README.md`。
- `android-app/`、`contracts/`、`eval/`、`services/`、`tests/`、`docs/` 和 `.scratch/mobile-agent-v1/` 保留为当前 App、服务、契约、评测、回归和证据入口。
- 根 `LICENSE` 未修改；保留的 v3.5 AndroidWorld 许可证位于 `Mobile-Agent-v3.5/android_world_v3.5/LICENSE`。删除的旧项目没有被当前运行入口使用，不能从删除动作推断其外部资料仍被本仓库打包。
- 上游来源为 `X-PLUG/MobileAgent`，项目记录的 v3.5 起点是 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`。本次没有重写历史，也没有把完整环境外置成新的下载框架。

## 外部固定引用

旧项目在 `docs/research/2026-09-22-stages-evaluation-and-flows.md` 中仍有 Mobile-Eval-E、GUI-Critic-R1 和 UI-S1 的上游 GitHub 链接；这些链接用于说明可选评测资料和研究来源，不是本地导入、构建依赖或当前验收资产。若后续要重新使用这些资料，应按链接对应版本另行获取并记录许可证与适配结果。

当前真实基线还依赖用户提供的兼容 VLM 服务（记录中的模型为 `gui-plus-2026-02-26`）和 AndroidWorld/实体手机环境；模型凭据、原始截图、provider payload 和设备数据不进入仓库。外部能力不是本次裁剪新增的依赖。

## 基线复现入口

已有对照不能只用导入成功或离线回放替代。真实复现前需准备 `JEV_VLM_API_KEY`、指定 Android 设备/模拟器以及相应 Python 环境；凭据只放在运行环境中。

原版手机 CLI 入口示例（由 `services/original_baselines/README.md` 记录安全边界）：

```bash
python3 -m services.original_baselines phone \
  --adb-path "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --serial "$DEVICE_SERIAL" \
  --tap-bounds "$TOGGLE_LEFT" "$TOGGLE_TOP" "$TOGGLE_RIGHT" "$TOGGLE_BOTTOM" \
  --evidence-dir /tmp/jev-original-mobile-baseline
```

该 CLI 没有 `live observation_provider`，只能展示受控 runner 的启动参数，不能单独完成实体手机的独立判分。实体手机复现必须由协调者调用 `run_phone_baseline(..., observation_provider=live_callback)`，注入真实设备观察回调；本地 `--observation-json` 仍只能作为离线证据。

原版 AndroidWorld 四角色入口，使用具体 `SystemBrightnessMax.is_successful` 判据：

```bash
/tmp/jev-androidworld-venv/bin/python -m services.original_baselines androidworld \
  --adb-path "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --console-port 5556 \
  --grpc-port 8554 \
  --evidence-dir /tmp/jev-original-androidworld-baseline
```

裁剪后的实际参考入口是同一个已准备的 `/tmp/jev-androidworld-venv` 与 API 33 AVD（console `5556`、gRPC `8554`）中的 `run_androidworld_extracted_baseline`。macOS 环境需先设置 `SSL_CERT_FILE` 为 `certifi` CA，并抑制 gRPC 日志：

```bash
/tmp/jev-androidworld-venv/bin/python - <<'PY'
import os

import certifi

os.environ["SSL_CERT_FILE"] = certifi.where()
os.environ["GRPC_VERBOSITY"] = "ERROR"
os.environ["GRPC_ENABLE_FORK_SUPPORT"] = "0"

from services.original_baselines import run_androidworld_extracted_baseline

report = run_androidworld_extracted_baseline(
    adb_path=os.environ["ANDROID_SDK_ROOT"] + "/platform-tools/adb",
    console_port=5556,
    grpc_port=8554,
    evidence_dir="/tmp/jev-pruned-extracted-baseline",
)
print(report["status"], report.get("task", {}).get("independent_score"))
PY
```

记录中的原版手机 `mobile04` 在受控页面得到独立成功；原版 AndroidWorld `SystemBrightnessMax` 与提取入口 `extracted01` 都是五步、15 次请求、独立分数 `0.0` 的失败对照。它们分别见 `.scratch/mobile-agent-v1/evidence/original-baseline-mobile04.json`、`original-baseline-androidworld01.json` 和 `extracted-baseline-androidworld01.json`。本工作树按用户约束不访问模型或设备，因此裁剪后的真实任务重跑仍需在上述环境中完成；离线回归只能确认本地路径、来源哈希和行为测试没有因目录删除失效。
