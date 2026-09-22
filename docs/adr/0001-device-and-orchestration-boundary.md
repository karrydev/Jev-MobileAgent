---
status: accepted
---
# App 负责设备，服务端负责 Agent 编排

沿用既有项目方向：Android App 通过用户启用的无障碍服务提供观察、执行与任务控制，Python 编排先运行在开发电脑、后迁到普通服务器。最终使用链路为 App 与服务端连接，不依赖电脑常驻或 ADB。

这样可以复用 MobileAgent v3.5 的角色与模型能力，同时独立验证手机设备通道。代价是必须明确设备协议、生命周期、连接恢复与两端状态；首期不将完整编排搬入 APK，也不以 root、Shizuku 或手机内运行桌面 Android CLI 作为前提。

来源：[项目方向](../project-direction.md)、[复用与改造边界](../research/mobileagent-jev-adaptation-evidence.md)。
