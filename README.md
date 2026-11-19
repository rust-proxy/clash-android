# clash-android

## 软件截图

| 总览 · VPN 运行中 | 总览 · 深色模式 | 代理组选择 |
| --- | --- | --- |
| <img src="docs/screenshots/overview-running.png" width="230" alt="VPN 正在运行，下方显示流量、连接数和内存占用"> | <img src="docs/screenshots/overview-dark.png" width="230" alt="深色模式下的总览，VPN 已停止"> | <img src="docs/screenshots/proxy-group.png" width="230" alt="展开的代理组，当前选中 REJECT 节点"> |

**宽屏总览**

![宽屏窗口中的侧栏和双栏总览，VPN 状态与网络流量并排显示](docs/screenshots/overview-wide.png)

窗口较窄或高度不足时使用底部导航。窗口宽度达到 600dp 时，下载和上传并排显示。达到 840dp 时，VPN 状态和网络流量也会并排显示。调整界面缩放后，布局按新的可用宽度重新选择。

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
