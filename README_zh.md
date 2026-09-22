# Jev-MobileAgent

Jev-MobileAgent 是基于 MobileAgent v3.5 的 Android 手机自动操作衍生项目。当前仓库保留 v3.5 模型与参考入口，并逐步加入 Android App、Python 服务端、提取后的角色、明确的契约、离线评测和回归测试。

## 从这里开始

- [文档索引](docs/README.md)
- [项目方向与边界](docs/project-direction.md)
- [开发路线](docs/development-roadmap.md)
- [首版任务与验收索引](.scratch/mobile-agent-v1/index.md)
- [仓库裁剪清单](docs/repository-pruning.md)

主要实现目录是 [agent_core](agent_core/)、[android-app](android-app/)、[contracts](contracts/)、[eval](eval/) 和 [services](services/)，针对性检查位于 [tests](tests/)。

## 来源与许可证

上游来源为 [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent)，v3.5 源码基线记录为 `11cea575561fb7800b5fb6b6cafa56f7a91de11f`。完整保留的上游 v3.5 目录位于 [Mobile-Agent-v3.5](Mobile-Agent-v3.5/)，提取后的角色代码与项目适配层在 [agent_core/vlm/README.md](agent_core/vlm/README.md) 中记录来源路径和哈希。[根 LICENSE](LICENSE) 及保留上游目录中的许可证文件未修改。本次裁剪只删除当前工作树中的文件，不改写 Git 历史。

## 基线与当前限制

已有原版手机基线 `mobile04` 在受控页面完成任务。原版与提取后的 AndroidWorld `SystemBrightnessMax` 对照都在五步后停止，独立分数均为 `0.0`；失败结果保持原样，不将其描述为收益。报告和复现命令见 [.scratch/mobile-agent-v1/evidence](.scratch/mobile-agent-v1/evidence) 与 [services/original_baselines/README.md](services/original_baselines/README.md)。

App 加 VLM 闭环、Jev 选择、树核验、服务器部署和真机中断恢复仍在开发中。真实对照命令需要已配置的 VLM 服务以及 Android 设备或模拟器；离线回归不能替代这些外部能力验收。
