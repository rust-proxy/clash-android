# Clash Android

基于 clash-rs 的 Android 代理客户端。VPN 模式支持分应用代理，KernelSU TPROXY 可代理全设备的 TCP 和 UDP，并保留系统 VPN 槽位。

[下载 APK](https://github.com/rust-proxy/clash-android/releases) · 支持 `arm64-v8a`、`armeabi-v7a` 和 `x86_64`，通用包包含全部三种架构。

## 主要功能

- 从本地文件或订阅链接导入配置，支持校验、切换和手动更新订阅，也可通过 LocalSend 在局域网分享配置和订阅链接。
- 切换代理节点、批量测速，查看流量、内存和活跃连接，以及每条连接命中的规则和代理链。
- 支持深色模式、界面缩放，以及稳定版和测试版的应用内更新。

## 使用 KernelSU TPROXY

1. 停止当前代理，在「设置 → 代理模式」选择 KernelSU TPROXY。
2. 点击「安装或更新模块」，在 KernelSU 管理器确认安装，并在「超级用户」中授权 Clash Android。
3. 返回首页启动代理。退出或强行停止 App 后代理仍会运行。要停止代理并清理规则，请重新打开 App，点击首页的停止按钮。

「接管系统 DNS」默认将非回环单播的 TCP/UDP 53 流量交给 Clash DNS。严格私人 DNS 和应用内 DoH 使用各自的加密通道。启用 IPv6 需要内核支持 IPv6 TPROXY。

模块使用 fwmark 的第 28 位（`0x10000000`）。请确认系统和其他模块未占用该位，并为出站 `routing-mark` 选用其他位。运行统计和网络诊断可在 KernelSU 管理器的模块 WebUI 查看。

## 截图

<img src="docs/screenshots/overview-running.png" width="230" alt="VPN 运行总览"> <img src="docs/screenshots/proxy-group.png" width="230" alt="代理组节点选择">

[深色模式](docs/screenshots/overview-dark.png) · [宽屏总览](docs/screenshots/overview-wide.png) · [界面缩放](docs/screenshots/ui-scale.png)

## 开发

构建需要 JDK、Android SDK、[项目指定的 NDK](gradle.properties)、Rust 及对应的 Android 目标、`cargo-ndk`、`just` 和 Node.js 22.12+（含 npm）。

| 命令 | 用途 |
| --- | --- |
| `just generate-bindings` | 每次修改 Rust 后更新 Kotlin 绑定 |
| `just phone` | 构建仅含 arm64 的 Debug APK |
| `just build` | 构建三架构 Debug APK |
| `just module` | 生成三架构模块包 `build/kernelsu/clash-android-tproxy.zip` |

### 模拟器流量测试（Android 10+）

在宿主机运行 `python traffic-test/echo_server.py`，再用 `.\gradlew.bat :traffic-test:installDebug` 安装「流量测试」。先关闭代理，以默认目标 `10.0.2.2:39007` 测试 TCP、UDP 和系统 DNS。再将测试应用纳入代理范围，启动代理并复测。验证远端节点时，将目标改为该节点可达的回显服务，对照请求编号、来源地址和 Clash 日志确认路径。

图标 *wind* 来自 [loading.io](https://loading.io/icon/to8zzb)。
