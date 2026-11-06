package rs.clash.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import rs.clash.android.viewmodel.pollWhileServiceRunning

class ServicePollingTest {
	@Test
	fun stoppingAndRestartingWaitsForThePreviousRequestToFinish() =
		runBlocking {
			withTimeout(5000) {
				val running = MutableStateFlow(false)
				val started = Channel<Int>(Channel.UNLIMITED)
				val cancelling = CompletableDeferred<Unit>()
				val allowCleanup = CompletableDeferred<Unit>()
				var requests = 0
				var active = 0
				var maxActive = 0
				val polling =
					launch(start = CoroutineStart.UNDISPATCHED) {
						pollWhileServiceRunning(running, intervalMillis = 1) {
							val request = ++requests
							active++
							maxActive = maxOf(maxActive, active)
							started.send(request)
							try {
								awaitCancellation()
							} finally {
								if (request == 1) {
									cancelling.complete(Unit)
									withContext(NonCancellable) { withTimeout(5000) { allowCleanup.await() } }
								}
								active--
							}
						}
					}
				yield()
				assertEquals(0, requests)
				running.value = true
				assertEquals(1, started.receive())
				running.value = false
				cancelling.await()
				running.value = true
				yield()
				assertEquals(1, requests)
				allowCleanup.complete(Unit)
				assertEquals(2, started.receive())
				polling.cancelAndJoin()
				assertEquals(1, maxActive)
				assertEquals(0, active)
			}
		}

	@Test
	fun hidingThePageCancelsPollingAndReturningStartsOneFreshRequest() =
		runBlocking {
			withTimeout(5000) {
				val running = MutableStateFlow(true)
				val started = Channel<Unit>(Channel.UNLIMITED)
				var active = false
				val refresh: suspend () -> Unit = {
					active = true
					started.send(Unit)
					try {
						awaitCancellation()
					} finally {
						active = false
					}
				}
				val firstPage = launch { pollWhileServiceRunning(running, 1, refresh = refresh) }
				started.receive()
				firstPage.cancelAndJoin()
				assertFalse(active)
				running.value = false
				running.value = true
				yield()
				assertFalse(active)
				val returnedPage = launch { pollWhileServiceRunning(running, 1, refresh = refresh) }
				started.receive()
				returnedPage.cancelAndJoin()
				assertFalse(active)
			}
		}
}
