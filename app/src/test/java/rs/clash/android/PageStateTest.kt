package rs.clash.android

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rs.clash.android.data.ConnectionsRepository
import rs.clash.android.data.MemoryUsage
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
import rs.clash.android.viewmodel.HomeUiState
import rs.clash.android.viewmodel.HomeViewModel
import rs.clash.android.viewmodel.PanelAction
import rs.clash.android.viewmodel.PanelUiState
import rs.clash.android.viewmodel.PanelViewModel
import rs.clash.android.viewmodel.ProxyDelay
import uniffi.clash_android_ffi.ConnectionsResponse

@OptIn(ExperimentalCoroutinesApi::class)
class PageStateTest {
	private val viewModels = ViewModelStore()
	private var panelCount = 0

	@Before
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
	}

	@After
	fun tearDown() {
		viewModels.clear()
		Dispatchers.resetMain()
	}

	private fun panel(
		repository: ProxyRepository,
		running: MutableStateFlow<Boolean> = MutableStateFlow(true),
	): PanelViewModel = PanelViewModel(repository, running).also { viewModels.put("panel-${panelCount++}", it) }

	private fun home(
		repository: OverviewRepository,
		vpn: FakeVpn = FakeVpn().apply { running.value = true },
	): HomeViewModel = HomeViewModel(repository, vpn).also { viewModels.put("home", it) }

	@Test
	fun returningHomePreservesOverviewAndRefreshesImmediatelyWithoutHiddenPolling() =
		runTest {
			val cached = Overview(MemoryUsage(1234, 4096), 7, 8000, 9000)
			val updated = cached.copy(connectionCount = 8, downloadTotal = 10000)
			val release = CompletableDeferred<Unit>()
			var calls = 0
			val home =
				home(
					object : OverviewRepository {
						override suspend fun getOverview(): Overview {
							calls++
							if (calls == 1) return cached
							release.await()
							return updated
						}
					},
				)
			val first = backgroundScope.launch { home.observe() }
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(cached, home.uiState.value.overview)
			first.cancelAndJoin()
			advanceTimeBy(6000)
			runCurrent()
			assertEquals(1, calls)
			val returned = backgroundScope.launch { home.observe() }
			runCurrent()
			assertEquals(2, calls)
			assertEquals(cached, home.uiState.value.overview)
			release.complete(Unit)
			runCurrent()
			assertEquals(updated, home.uiState.value.overview)
			advanceTimeBy(2999)
			runCurrent()
			assertEquals(2, calls)
			advanceTimeBy(1)
			runCurrent()
			assertEquals(3, calls)
			returned.cancelAndJoin()
		}

	@Test
	fun hiddenHomeDiscardsLateResponseAndKeepsLastSnapshot() =
		runTest {
			val cached = Overview(connectionCount = 7)
			val release = CompletableDeferred<Unit>()
			var calls = 0
			val home =
				home(
					object : OverviewRepository {
						override suspend fun getOverview(): Overview {
							if (++calls == 1) return cached
							withContext(NonCancellable) { release.await() }
							return Overview(connectionCount = 99)
						}
					},
				)
			val first = backgroundScope.launch { home.observe() }
			advanceTimeBy(1000)
			runCurrent()
			first.cancelAndJoin()
			val returned = backgroundScope.launch { home.observe() }
			try {
				runCurrent()
				assertEquals(2, calls)
				returned.cancel()
				release.complete(Unit)
				returned.join()
				assertEquals(cached, home.uiState.value.overview)
			} finally {
				release.complete(Unit)
				returned.cancelAndJoin()
			}
		}

	@Test
	fun hiddenHomeInvalidatesOverviewAcrossVpnSessions() =
		runTest {
			val vpn = FakeVpn().apply { running.value = true }
			var calls = 0
			val home =
				home(
					object : OverviewRepository {
						override suspend fun getOverview(): Overview = Overview(connectionCount = ++calls)
					},
					vpn,
				)
			val first = backgroundScope.launch { home.observe() }
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(1, home.uiState.value.overview.connectionCount)
			first.cancelAndJoin()
			vpn.running.value = false
			runCurrent()
			assertEquals(HomeUiState(), home.uiState.value)
			vpn.running.value = true
			runCurrent()
			assertEquals(HomeUiState(isVpnRunning = true), home.uiState.value)
			assertEquals(1, calls)
			val returned = backgroundScope.launch { home.observe() }
			runCurrent()
			assertEquals(1, calls)
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(2, home.uiState.value.overview.connectionCount)
			returned.cancelAndJoin()
		}

	@Test
	fun failedHomeRefreshAfterTabSwitchKeepsSnapshotAndPollingRecovers() =
		runTest {
			val cached = Overview(connectionCount = 7, downloadTotal = 8000)
			var calls = 0
			val home =
				home(
					object : OverviewRepository {
						override suspend fun getOverview(): Overview {
							check(++calls != 2) { "controller unavailable" }
							return if (calls == 1) cached else cached.copy(connectionCount = 9)
						}
					},
				)
			val first = backgroundScope.launch { home.observe() }
			advanceTimeBy(1000)
			runCurrent()
			first.cancelAndJoin()
			val returned = backgroundScope.launch { home.observe() }
			runCurrent()
			assertEquals(2, calls)
			assertEquals(cached, home.uiState.value.overview)
			assertTrue(home.effects.first() is HomeEffect.Message)
			advanceTimeBy(3000)
			runCurrent()
			assertEquals(9, home.uiState.value.overview.connectionCount)
			returned.cancelAndJoin()
		}

	@Test
	fun firstPanelFrameIsLoadingAndReadyControllerLoadsWithoutDelay() =
		runTest {
			val repository = FakeProxies()
			val panel = panel(repository)
			assertTrue(panel.uiState.value.isLoading)
			val observing = backgroundScope.launch { panel.observe() }
			runCurrent()
			assertEquals(1, repository.loads)
			assertTrue(
				panel.uiState.value.proxies
					.isNotEmpty(),
			)
			assertFalse(panel.uiState.value.isLoading)
			observing.cancelAndJoin()
		}

	@Test
	fun returningPanelKeepsCachedProxiesAndDiscardsCancelledRefreshResults() =
		runTest {
			val release = CompletableDeferred<Unit>()
			val repository =
				object : FakeProxies() {
					override suspend fun getProxies(): List<ProxyInfo> {
						val proxies = super.getProxies()
						if (loads == 1) return proxies
						withContext(NonCancellable) { release.await() }
						return emptyList()
					}
				}
			val panel = panel(repository)
			val first = backgroundScope.launch { panel.observe() }
			runCurrent()
			val cached = panel.uiState.value
			first.cancelAndJoin()
			assertEquals(cached, panel.uiState.value)
			val returned = backgroundScope.launch { panel.observe() }
			try {
				runCurrent()
				assertEquals(2, repository.loads)
				assertEquals(cached.proxies, panel.uiState.value.proxies)
				assertTrue(panel.uiState.value.isRefreshing)
				returned.cancel()
				release.complete(Unit)
				returned.join()
				assertEquals(cached, panel.uiState.value)
			} finally {
				release.complete(Unit)
				returned.cancelAndJoin()
			}
		}

	@Test
	fun startupFailureRetriesWhileShowingLoading() =
		runTest {
			val repository = FakeProxies().apply { fail = true }
			val panel = panel(repository)
			val observing = backgroundScope.launch { panel.observe() }
			runCurrent()
			assertEquals(1, repository.loads)
			assertTrue(panel.uiState.value.isLoading)
			assertFalse(panel.uiState.value.hasLoaded)
			repository.fail = false
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(2, repository.loads)
			assertTrue(
				panel.uiState.value.proxies
					.isNotEmpty(),
			)
			assertFalse(panel.uiState.value.isLoading)
			observing.cancelAndJoin()
		}

	@Test
	fun hidingDuringStartupCancelsRetryAndReturningStillStartsInLoadingState() =
		runTest {
			val repository = FakeProxies().apply { fail = true }
			val panel = panel(repository)
			val first = backgroundScope.launch { panel.observe() }
			runCurrent()
			first.cancelAndJoin()
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(1, repository.loads)
			assertTrue(panel.uiState.value.isLoading)
			repository.fail = false
			val returned = backgroundScope.launch { panel.observe() }
			runCurrent()
			assertEquals(2, repository.loads)
			assertFalse(panel.uiState.value.isLoading)
			returned.cancelAndJoin()
		}

	@Test
	fun stoppingVpnWhilePanelIsHiddenInvalidatesCacheWithoutFetching() =
		runTest {
			val running = MutableStateFlow(true)
			val repository = FakeProxies()
			val panel = panel(repository, running)
			val first = backgroundScope.launch { panel.observe() }
			runCurrent()
			assertTrue(
				panel.uiState.value.proxies
					.isNotEmpty(),
			)
			first.cancelAndJoin()
			running.value = false
			runCurrent()
			assertEquals(PanelUiState(), panel.uiState.value)
			running.value = true
			runCurrent()
			assertTrue(
				panel.uiState.value.proxies
					.isEmpty(),
			)
			assertTrue(panel.uiState.value.isLoading)
			assertEquals(1, repository.loads)
			val returned = backgroundScope.launch { panel.observe() }
			runCurrent()
			assertEquals(2, repository.loads)
			assertFalse(panel.uiState.value.isLoading)
			returned.cancelAndJoin()
		}

	@Test
	fun hidingPanelRestoresPreviousDelayAfterCancellingTest() =
		runTest {
			val repository =
				object : FakeProxies() {
					override suspend fun getProxyDelay(name: String): Int = awaitCancellation()
				}
			val panel = panel(repository)
			val observing = backgroundScope.launch { panel.observe() }
			runCurrent()
			val cached = panel.uiState.value
			panel.onAction(PanelAction.TestGroupDelay("group"))
			runCurrent()
			assertEquals(ProxyDelay.Testing, panel.uiState.value.delays["leaf"])
			observing.cancelAndJoin()
			assertEquals(cached, panel.uiState.value)
		}

	@Test
	fun emptyResultAndFailedStartupFinishLoading() =
		runTest {
			val empty =
				panel(
					object : FakeProxies() {
						override suspend fun getProxies(): List<ProxyInfo> = emptyList()
					},
				)
			val observingEmpty = backgroundScope.launch { empty.observe() }
			runCurrent()
			assertTrue(empty.uiState.value.hasLoaded)
			assertTrue(
				empty.uiState.value.proxies
					.isEmpty(),
			)
			assertFalse(empty.uiState.value.isLoading)
			observingEmpty.cancelAndJoin()

			val repository = FakeProxies().apply { fail = true }
			val failed = panel(repository)
			val observingFailed = backgroundScope.launch { failed.observe() }
			runCurrent()
			advanceTimeBy(1000)
			runCurrent()
			assertEquals(2, repository.loads)
			assertFalse(failed.uiState.value.isLoading)
			assertEquals(R.string.home_api_error, failed.effects.first().resource)
			observingFailed.cancelAndJoin()
		}

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
				val panel = panel(repository)
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
			val home = home(FakeOverview(), vpn)
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
				val home = home(repository, vpn)
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
				val cancellation = CompletableDeferred<Unit>()
				var cancelled = false
				val repository =
					object : FakeProxies() {
						override suspend fun getProxyDelay(name: String): Int {
							requested.send(name)
							try {
								awaitCancellation()
							} finally {
								cancelled = true
								cancellation.complete(Unit)
							}
						}
					}
				val panel = panel(repository, running)
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
					cancellation.await()
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
				val refreshed = CompletableDeferred<Unit>()
				var cancelled = false
				val repository =
					object : FakeProxies() {
						override suspend fun getProxies(): List<ProxyInfo> =
							super.getProxies().also {
								if (loads == 2) refreshed.complete(Unit)
							}

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
				val panel = panel(repository)
				val first = launch { panel.observe() }
				val cached = panel.uiState.first { it.proxies.isNotEmpty() }
				panel.onAction(PanelAction.SelectProxy("group", "leaf"))
				selected.await()
				first.cancelAndJoin()
				assertTrue(cancelled)
				assertEquals(cached, panel.uiState.value)
				panel.onAction(PanelAction.Refresh)
				val returned = launch { panel.observe() }
				try {
					refreshed.await()
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
				val panel = panel(repository)
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
