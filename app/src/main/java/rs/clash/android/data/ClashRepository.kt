package rs.clash.android.data

import kotlinx.coroutines.CancellationException
import rs.clash.android.Global
import uniffi.clash_android_ffi.ClashController
import uniffi.clash_android_ffi.ConnectionsResponse
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.formatEyreError

data class MemoryUsage(
	val inuse: Long,
	val oslimit: Long,
)

data class Overview(
	val memory: MemoryUsage? = null,
	val connectionCount: Int = 0,
	val downloadTotal: Long = 0,
	val uploadTotal: Long = 0,
)

data class ProxyInfo(
	val name: String,
	val proxyType: String,
	val all: List<String> = emptyList(),
	val now: String? = null,
	val lastDelay: Int? = null,
)

interface OverviewRepository {
	suspend fun getOverview(): Overview
}

interface ProxyRepository {
	suspend fun getProxies(): List<ProxyInfo>

	suspend fun getProxyDelay(name: String): Int

	suspend fun selectProxy(
		groupName: String,
		proxyName: String,
	)
}

interface ConnectionsRepository {
	suspend fun getConnections(): ConnectionsResponse
}

/** Application-scoped controller access. Page state and polling belong to the caller. */
object AppData {
	val core by lazy { FfiClashRepository("${Global.application.cacheDir}/clash.sock") }
}

class FfiClashRepository(
	socketPath: String,
) : OverviewRepository,
	ProxyRepository,
	ConnectionsRepository {
	private val controller by lazy { ClashController(socketPath) }

	override suspend fun getOverview(): Overview =
		ffiCall {
			val response = controller.getOverview()
			Overview(
				memory = response.memory.let { MemoryUsage(it.inuse, it.oslimit) },
				connectionCount = response.connectionCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt(),
				downloadTotal = response.downloadTotal,
				uploadTotal = response.uploadTotal,
			)
		}

	override suspend fun getProxies(): List<ProxyInfo> =
		ffiCall {
			controller.getProxies().map {
				ProxyInfo(it.name, it.proxyType, it.all.toList(), it.now, it.history.lastOrNull()?.delay)
			}
		}

	override suspend fun getProxyDelay(name: String): Int = ffiCall { controller.getProxyDelay(name, null, null).delay }

	override suspend fun selectProxy(
		groupName: String,
		proxyName: String,
	) = ffiCall { controller.selectProxy(groupName, proxyName) }

	override suspend fun getConnections(): ConnectionsResponse = ffiCall { controller.getConnections() }
}

private suspend fun <T> ffiCall(block: suspend () -> T): T =
	try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: EyreException) {
		throw IllegalStateException(formatEyreError(e), e)
	}
