# clash-android

## 功能清单

- [x] 在 VPN 和 KernelSU TPROXY 之间选择，按应用设置代理范围
- [x] 切换代理组节点，批量测试组内节点延迟
- [x] 查看流量、内存占用和活跃连接，以及每条连接命中的规则和代理链
- [x] 导入本地 Clash 配置，校验并切换配置
- [x] 从订阅链接导入配置，手动更新订阅
- [ ] 在后台定时更新订阅
- [x] 通过 LocalSend 在局域网收发配置，可随配置分享订阅链接
- [x] 设置 IPv6、Fake IP、深色模式和界面缩放
- [x] 在应用内检查、下载并安装 GitHub 稳定版或测试版更新

## 使用 KernelSU TPROXY

在 KernelSU 管理器的「超级用户」中授权 Clash Android。停止当前代理，在「设置 → 代理模式」选择 KernelSU TPROXY，然后从首页启动。原有配置可直接使用。此模式通过内核的 TPROXY 和策略路由转发本机 TCP、UDP，保留 Android VPN 槽位。开启 IPv6 时，设备内核也需要支持 IPv6 TPROXY。后台由一个原生进程加载 APK 中的 Clash 库。退出界面或在系统设置中强行停止 App 后，代理会继续运行。重新打开 App 可接管当前会话，点击首页的停止按钮会退出内核并清理规则。

如果代理内核本身意外退出，重新打开 App 会清理上次会话的规则，之后可以从首页再次启动。

## 软件截图

| 总览 · VPN 运行中 | 总览 · 深色模式 | 代理组选择 |
| --- | --- | --- |
| <img src="docs/screenshots/overview-running.png" width="230" alt="VPN 正在运行，下方显示流量、连接数和内存占用"> | <img src="docs/screenshots/overview-dark.png" width="230" alt="深色模式下的总览，VPN 已停止"> | <img src="docs/screenshots/proxy-group.png" width="230" alt="展开的代理组，当前选中 REJECT 节点"> |

**宽屏总览**

![宽屏窗口中的侧栏和双栏总览，VPN 状态与网络流量并排显示](docs/screenshots/overview-wide.png)

<details>
<summary>设置 · 界面缩放</summary>

在「设置 → 外观 → 界面缩放」中，可以使用设备推荐值，或按 5% 一档手动选择 75%–150%。

<img src="docs/screenshots/ui-scale.png" width="280" alt="界面缩放设置，提供自动推荐和手动调节滑块">

</details>

<!-- 更新截图时，用当前版本和示例配置截取对应页面，替换 docs/screenshots/ 下的同名 PNG。若画面内容变化，同步更新图注和替代文本。 -->

# Develop

每次修改 Rust 部分后使用 `generate-bindings` 更新绑定。

# Requirements

Android SDK & NDK

# Icon

The icon "wind" is provided by [loading.io](https://loading.io/icon/to8zzb)
