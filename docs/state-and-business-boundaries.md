# 页面状态与业务边界

本轮从原来的 `HomeViewModel` 拆出代理组和 Android VPN 操作，保持现有 Compose、Android 服务和 Rust 内核结构。

## 当前职责

| 层 | 入口 | 职责 |
| --- | --- | --- |
| 首页 | `HomeViewModel.uiState`、`onAction(HomeAction)` | 概览快照、VPN 动作协调与一次性 UI 效果 |
| 代理页 | `PanelViewModel.uiState`、`onAction(PanelAction)` | 代理组、刷新、选择、测速和加载状态 |
| 连接页 | `ConnectionsViewModel.uiState` | 连接列表、流量合计、加载及错误状态 |
| 控制器数据访问 | `OverviewRepository`、`ProxyRepository`、`ConnectionsRepository` | 按业务能力提供可注入接口；`FfiClashRepository` 统一调用 UniFFI 控制器并转换错误 |
| VPN 平台操作 | `VpnController` / `AndroidVpnController` | 从持久化配置读取当前路径、检查系统授权、启动或停止服务 |
| VPN 资源所有权 | `TunService` | 内核实例、TUN 文件描述符及服务资源的创建和清理 |
| Compose 页面 | `HomeScreen`、`PanelScreen`、`ConnectionsScreen` | 收集状态、派发动作、启动权限 Activity、展示提示和导航 |

`AppData.core` 在应用内复用控制器适配器。Repository 不持有页面状态、不启动轮询，也不调用 Snackbar。首页和代理页使用应用自己的只读模型，避免可变的生成类型进入页面状态；测速以 `Testing`、`Timeout`、`Measured` 表达，文案由 UI 使用现有资源生成。

## 生命周期与并发

- 页面通过 `repeatOnLifecycle(RESUMED)` 调用 `observe()`；`collectAsStateWithLifecycle()` 负责状态订阅。每个导航目的地使用自己的 ViewModel。
- 概览每 3 秒读取一次，每个 VPN 会话首次加载等待 1 秒；已有快照时切回概览立即刷新并保留原值；连接列表每 2 秒读取一次；代理页恢复或服务启动时立即刷新一次，不持续轮询。代理页首次加载失败时等待 1 秒重试一次，以兼容控制接口尚未就绪的启动阶段；首次结果返回前显示加载状态。
- 服务停止会取消当前观察中的请求并清空状态。代理页隐藏会取消选择、测速及手动刷新，保留已加载的快照；返回时继续展示快照并刷新，取消的测速恢复之前的延迟结果。代理 ViewModel 只在后台订阅服务状态以使停服缓存失效，不发起后台请求；隐藏或停服期间发出的代理动作不排队重放。
- 概览页隐藏会取消轮询并保留快照；ViewModel 只订阅服务状态，在隐藏期间停服或重启时清除旧会话数据，不进行后台请求。刷新失败仍保留快照，后续轮询可继续恢复。
- 代理选择及后续刷新共享同一把锁，手动和首次刷新也使用该锁。重复点击同一节点测速不会同时启动多个测试。
- 测速目标从当前代理组解析，仅测试其直接包含的叶子节点；嵌套组由各自的测速入口处理。
- 所有请求保留 `CancellationException`；更新状态前检查取消和服务状态，防止停止后回写旧结果。刷新失败保留仍可用的代理列表。

## VPN 权限与一次性效果

`HomeAction.StartVpn` 调用 VPN 平台接口。缺少配置时发送资源消息，需要系统授权时发送 `RequestVpnPermission`；页面只负责启动 Activity 并回传 `PermissionResult`。授权成功后重新读取当前配置和检查权限，拒绝授权不会启动服务。

`StopVpn` 只委托 `TunService` 停止，不再额外调用 `ClashInstance.shutdown()`。服务继续作为内核和 TUN 的唯一清理入口。现有停止后的应用重启行为保留为 `RestartApp` 效果，仍由 UI 执行。权限和提示效果与持久页面状态分开传递。

## 渐进迁移的边界

本轮集中的是概览、代理和连接控制器调用。配置导入、下载和校验仍属于 `ProfileViewModel`；设置页仍由 `SettingsViewModel` 管理；服务内部原生启动、停止仍留在 `TunService`。连接详情暂时继续使用生成的 `Connection` 数据类型，后续可随连接页面改造迁移为应用模型。

后续迁移应沿用“只读 UiState + 类型化动作 + 可注入业务接口”，逐项迁移配置文件事务和订阅下载；需要继续保留下载失败前的配置、取消语义以及服务资源所有权，不把数据访问层扩展为全局页面状态容器。

## 验证范围

`PageStateTest` 使用替代实现覆盖权限拒绝及授权后重新检查、停服丢弃迟到的概览响应、叶子节点测速去重和取消、隐藏页面取消选择及不重放动作、刷新失败保留数据、选择与刷新串行执行，以及连接页隐藏后丢弃迟到响应。代理页还覆盖首次加载状态、立即请求、切换标签保留快照、取消后丢弃迟到刷新、启动重试及取消、隐藏期间停服使缓存失效，以及取消测速恢复之前的结果。概览页还覆盖切回时保留快照并立即刷新、隐藏时停止轮询、取消后丢弃迟到结果、跨 VPN 会话清除缓存，以及刷新失败后恢复。`ServicePollingTest` 继续覆盖原有轮询的停止、恢复和页面取消行为。

本地 JVM 测试和 Kotlin 编译不能证明 Android 权限弹窗、系统服务启动、实际 TUN 清理或页面交互已通过设备验证。
