package rs.clash.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.clash.android.data.ConnectionsRepository
import rs.clash.android.data.Overview
import rs.clash.android.data.OverviewRepository
import rs.clash.android.data.ProxyInfo
import rs.clash.android.data.ProxyRepository
import rs.clash.android.service.VpnController
import rs.clash.android.service.VpnStartResult
import rs.clash.android.viewmodel.ConnectionsUiState
import rs.clash.android.viewmodel.ConnectionsViewModel
import rs.clash.android.viewmodel.HomeAction
import rs.clash.android.viewmodel.HomeEffect
import rs.clash.android.viewmodel.HomeViewModel
import rs.clash.android.viewmodel.PanelAction
import rs.clash.android.viewmodel.PanelUiState
import rs.clash.android.viewmodel.PanelViewModel
import rs.clash.android.viewmodel.ProxyDelay
import uniffi.clash_android_ffi.ConnectionsResponse

class PageStateTest {
	@Test
	fun connectionPageDiscardsLateResponseAfterItIsHidden() =
		runBlocking {
			withTimeout(5000) {
				val started = CompletableDeferred<Unit>()
				val release = CompletableDeferred<Unit>()
				val repository =
					object : ConnectionsRepository {
						override suspend fun getConnections(): ConnectionsResponse {
							started.complete(Unit)
							withContext(NonCancellable) { release.await() }
							return ConnectionsResponse(100, 200, emptyList())
						}
					}
				val connections = ConnectionsViewModel(repository, MutableStateFlow(true))
				val observing = launch { connections.observe() }
				try {
					started.await()
					observing.cancel()
					release.complete(Unit)
					observing.join()
					assertEquals(ConnectionsUiState(), connections.uiState.value)
				} finally {
					release.complete(Unit)
					observing.cancelAndJoin()
				}
			}
		}

	@Test
	fun refreshAndSelectionNeverOverlapRepositoryMutations() =
		runBlocking {
			withTimeout(5000) {
				val refreshStarted = CompletableDeferred<Unit>()
				val release = CompletableDeferred<Unit>()
				var refreshing = false
				var selectionCalls = 0
				val repository =
					object : FakeProxies() {
						override suspend fun getProxies(): List<ProxyInfo> {
							if (loads == 1) {
								refreshing = true
								refreshStarted.complete(Unit)
								release.await()
								refreshing = false
							}
							return super.getProxies()
						}

						override suspend fun selectProxy(
							groupName: String,
							proxyName: String,
						) {
							assertFalse(refreshing)
							selectionCalls++
							super.selectProxy(groupName, proxyName)
						}
					}
				val panel = PanelViewModel(repository, MutableStateFlow(true))
				val observing = launch { panel.observe() }
				try {
					panel.uiState.first { it.proxies.isNotEmpty() }
					panel.onAction(PanelAction.Refresh)
					refreshStarted.await()
					panel.onAction(PanelAction.SelectProxy("group", "leaf"))
					repeat(5) { yield() }
					assertEquals(0, selectionCalls)
					release.complete(Unit)
					panel.uiState.first { it.proxies.firstOrNull()?.now == "leaf" }
					assertEquals(1, selectionCalls)
					assertEquals(3, repository.loads)
				} finally {
					release.complete(Unit)
					observing.cancelAndJoin()
				}
			}
		}

	@Test
	fun deniedPermissionDoesNotStartVpnAndApprovedPermissionRechecksTheProfile() =
		runBlocking {
			val vpn = FakeVpn()
			val home = HomeViewModel(FakeOverview(), vpn)
			home.onAction(HomeAction.PermissionResult(false))
			assertEquals(0, vpn.starts)
			vpn.result = VpnStartResult.MissingProfile
			home.onAction(HomeAction.PermissionResult(true))
			assertEquals(1, vpn.starts)
			assertEquals(HomeEffect.Message(R.string.home_select_profile_first), home.effects.first())
			home.onAction(HomeAction.StopVpn)
			assertEquals(1, vpn.stops)
			assertEquals(HomeEffect.RestartApp, home.effects.first())
		}

	@Test
	fun overviewStopDiscardsEvenANonCooperativeLateResponse() =
		runBlocking {
			withTimeout(5000) {
				val started = CompletableDeferred<Unit>()
				val release = CompletableDeferred<Unit>()
				val repository =
					object : OverviewRepository {
						override suspend fun getOverview(): Overview {
							started.complete(Unit)
							withContext(NonCancellable) { release.await() }
							return Overview(connectionCount = 99)
						}
					}
				val vpn = FakeVpn().apply { running.value = true }
				val home = HomeViewModel(repository, vpn)
				val observing = launch { home.observe() }
				try {
					started.await()
					vpn.running.value = false
					yield()
					release.complete(Unit)
					home.uiState.first { !it.isVpnRunning }
					assertEquals(Overview(), home.uiState.value.overview)
				} finally {
					release.complete(Unit)
					observing.cancelAndJoin()
				}
			}
		}

	@Test
	fun delayTestsUseLeavesDeduplicateAndStopWithTheService() =
		runBlocking {
			withTimeout(5000) {
				val running = MutableStateFlow(true)
				val requested = Channel<String>(Channel.UNLIMITED)
				var cancelled = false
				val repository =
					object : FakeProxies() {
						override suspend fun getProxyDelay(name: String): Int {
							requested.send(name)
							try {
								awaitCancellation()
							} finally {
								cancelled = true
							}
						}
					}
				val panel = PanelViewModel(repository, running)
				val observing = launch { panel.observe() }
				try {
					panel.uiState.first { it.proxies.isNotEmpty() }
					panel.onAction(PanelAction.TestGroupDelay("group"))
					assertEquals("leaf", requested.receive())
					panel.onAction(PanelAction.TestGroupDelay("group"))
					repeat(5) { yield() }
					assertTrue(requested.tryReceive().isFailure)
					assertEquals(ProxyDelay.Testing, panel.uiState.value.delays["leaf"])
					running.value = false
					panel.uiState.first { it == PanelUiState() }
					assertTrue(cancelled)
				} finally {
					observing.cancelAndJoin()
				}
			}
		}

	@Test
	fun hidingPanelCancelsSelectionAndDoesNotReplayHiddenActions() =
		runBlocking {
			withTimeout(5000) {
				val selected = CompletableDeferred<Unit>()
				var cancelled = false
				val repository =
					object : FakeProxies() {
						override suspend fun selectProxy(
							groupName: String,
							proxyName: String,
						) {
							selected.complete(Unit)
							try {
								awaitCancellation()
							} finally {
								cancelled = true
							}
						}
					}
				val panel = PanelViewModel(repository, MutableStateFlow(true))
				val first = launch { panel.observe() }
				panel.uiState.first { it.proxies.isNotEmpty() }
				panel.onAction(PanelAction.SelectProxy("group", "leaf"))
				selected.await()
				first.cancelAndJoin()
				assertTrue(cancelled)
				assertEquals(PanelUiState(), panel.uiState.value)
				panel.onAction(PanelAction.Refresh)
				val returned = launch { panel.observe() }
				try {
					panel.uiState.first { it.proxies.isNotEmpty() }
					assertEquals(2, repository.loads)
				} finally {
					returned.cancelAndJoin()
				}
			}
		}

	@Test
	fun failedRefreshPreservesProxiesAndSelectionRefreshesInTheSameOperation() =
		runBlocking {
			withTimeout(5000) {
				val repository = FakeProxies()
				val panel = PanelViewModel(repository, MutableStateFlow(true))
				val observing = launch { panel.observe() }
				try {
					val initial = panel.uiState.first { it.proxies.isNotEmpty() }
					repository.fail = true
					panel.onAction(PanelAction.Refresh)
					assertEquals(R.string.home_api_error, panel.effects.first().resource)
					assertEquals(initial.proxies, panel.uiState.value.proxies)
					assertFalse(panel.uiState.value.isRefreshing)
					repository.fail = false
					panel.onAction(PanelAction.SelectProxy("group", "leaf"))
					panel.uiState.first { it.proxies.first().now == "leaf" }
					assertEquals(3, repository.loads)
				} finally {
					observing.cancelAndJoin()
				}
			}
		}
}

private class FakeVpn : VpnController {
	override val running = MutableStateFlow(false)
	var starts = 0
	var stops = 0
	var result: VpnStartResult = VpnStartResult.Started

	override fun start(): VpnStartResult {
		starts++
		return result
	}

	override fun stop() {
		stops++
		running.value = false
	}
}

private class FakeOverview : OverviewRepository {
	override suspend fun getOverview() = Overview()
}

private open class FakeProxies : ProxyRepository {
	var loads = 0
	var fail = false
	private var selected: String? = null

	override suspend fun getProxies(): List<ProxyInfo> {
		loads++
		check(!fail) { "controller unavailable" }
		return listOf(
			ProxyInfo("group", "Selector", listOf("leaf", "nested", "leaf"), selected),
			ProxyInfo("nested", "URLTest", listOf("leaf")),
			ProxyInfo("leaf", "Shadowsocks", lastDelay = 20),
		)
	}

	override suspend fun getProxyDelay(name: String): Int = 42

	override suspend fun selectProxy(
		groupName: String,
		proxyName: String,
	) {
		selected = proxyName
	}
}
