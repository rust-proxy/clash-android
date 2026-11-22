package rs.clash.traffictest

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class Probe(
	val title: Int,
) {
	TCP(R.string.tcp),
	UDP(R.string.udp),
	DNS(R.string.dns),
}

internal data class Result(
	val passed: Boolean,
	val elapsed: Long,
	val detail: String,
)

internal class ProbeViewModel(
	application: Application,
) : AndroidViewModel(application) {
	var host by mutableStateOf("10.0.2.2")
	var port by mutableStateOf("39007")
	var domain by mutableStateOf("example.com")
	val results = mutableStateMapOf<Probe, Result>()
	var running by mutableStateOf(false)
		private set

	fun run() {
		if (running) return
		val address = host.trim()
		val portText = port.trim()
		val dnsName = domain.trim()
		val context = getApplication<Application>()
		results.clear()
		running = true
		for (probe in Probe.entries) {
			viewModelScope.launch {
				val start = SystemClock.elapsedRealtime()
				var passed = false
				val detail =
					try {
						val answer =
							withTimeoutOrNull(5_000) {
								when (probe) {
									Probe.TCP, Probe.UDP -> {
										val token = echo(address, portText, udp = probe == Probe.UDP)
										context.getString(R.string.verified_echo, token)
									}
									Probe.DNS -> dnsLookup(dnsName).mapNotNull { it.hostAddress }.distinct().joinToString("\n")
								}
							}
						passed = answer != null
						answer ?: context.getString(R.string.timeout)
					} catch (e: CancellationException) {
						throw e
					} catch (e: ProbeError) {
						context.getString(e.messageRes)
					} catch (e: Exception) {
						val cause = e.cause ?: e
						cause.localizedMessage ?: cause.javaClass.simpleName
					}
				results[probe] = Result(passed, SystemClock.elapsedRealtime() - start, detail)
				if (results.size == Probe.entries.size) running = false
			}
		}
	}
}
