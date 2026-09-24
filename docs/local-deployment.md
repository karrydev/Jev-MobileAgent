# 本机 Android Bridge 演练

> 2026-09-25：以下说明当前桥接式实现或其历史验收。按 [ADR-0003](adr/0003-standalone-android-runtime.md)，最终产品改为手机内编排；这些操作不作为独立 App 用户的使用前提，迁移由任务 26/27 验收。

本文只覆盖开发电脑上的本地服务启停、健康检查、重启和版本回退，供任务 20 的部署准备使用。

## 准备

需要 Python 3 和当前仓库。先从授权来源取得设备桥 Token，并在运行命令的 shell 环境提供 `JEV_ANDROID_AUTH_TOKEN`。控制器会原样继承当前环境，不替换已有值；控制器不会把 Token 放到命令参数、配置文件或自己的输出中。

编辑 [deployment/local-service.json](../deployment/local-service.json) 中的本地 `release_path`、回环 `host`、`port` 或 `device_id`。默认绑定 `127.0.0.1:8765`，版本路径默认为当前仓库。控制器在启动前检查版本提供 `python3 -m services.android_bridge --state-file PATH`。

## 启停与检查

在仓库根目录运行：

```sh
python3 deployment/local_service.py start
python3 deployment/local_service.py health
python3 deployment/local_service.py restart
python3 deployment/local_service.py stop
python3 deployment/local_service.py paths
```

`health` 通过无鉴权 `GET /healthz` 读取并只显示 `service_status`、`persistence_status` 和 `pending_reconciliation_count`。其他响应字段不会显示。PID 记录校验进程组、启动时间、模块名和 state-file 后才允许停止；占用端口、身份不符或无法验证 PID 时会报错且不发送信号。停止、重启和回退都保留 state-file。

## 切换与回退

切换到另一个本地 checkout 会先保存当前路径为上一版本，再停止受控服务并使用相同的临时 state-file 启动新版本：

```sh
python3 deployment/local_service.py switch /path/to/another/checkout
python3 deployment/local_service.py health
python3 deployment/local_service.py rollback
python3 deployment/local_service.py health
```

`rollback` 切回上一版本并把被替换版本留作下一次回退目标。新版本无法启动时控制器会恢复原路径并尝试重新启动原版本。路径记录、PID 与日志位于系统临时目录下的 `jev-mobileagent-local/`；持久 state-file 位于其中的 `state/android-bridge.json`。控制器没有清理命令，不要为了重启或回退删除该文件，否则会丢失恢复核对证据。

## 验收边界

这套脚本只准备本地进程生命周期和 healthz 演练。它没有配置远端主机或 TLS，也没有证明手机脱离 USB/ADB 后可连接；独立 App 的手机内闭环、恢复与最终无开发工具验收由任务 26–28 完成，不再要求远端项目服务器。
