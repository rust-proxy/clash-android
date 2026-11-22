package rs.clash.traffictest

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ProbeError(
	val messageRes: Int,
) : Exception()

internal suspend fun echo(
	host: String,
	portText: String,
	udp: Boolean,
): String {
	try {
		return runInterruptible(Dispatchers.IO) { exchangeEcho(host, portText, udp) }
	} catch (e: ClosedByInterruptException) {
		// Android NIO surfaces the timeout interrupt as an IOException.
		currentCoroutineContext().ensureActive()
		throw e
	}
}

private fun exchangeEcho(
	host: String,
	portText: String,
	udp: Boolean,
): String {
	if (!InetAddresses.isNumericAddress(host)) throw ProbeError(R.string.invalid_address)
	val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw ProbeError(R.string.invalid_port)
	val target = InetSocketAddress(InetAddresses.parseNumericAddress(host), port)
	val token = UUID.randomUUID().toString().take(8)
	val payload = "traffic-test/$token".toByteArray(Charsets.US_ASCII)
	val request = ByteBuffer.wrap(payload)
	// A UDP packet one byte too long must fail even when its prefix matches.
	val reply = ByteBuffer.allocate(payload.size + if (udp) 1 else 0)
	if (udp) {
		DatagramChannel.open().use { channel ->
			channel.connect(target)
			channel.write(request)
			channel.read(reply)
		}
	} else {
		SocketChannel.open(target).use { channel ->
			while (request.hasRemaining()) channel.write(request)
			while (reply.hasRemaining()) {
				if (channel.read(reply) < 0) throw ProbeError(R.string.early_eof)
			}
		}
	}
	reply.flip()
	if (reply != ByteBuffer.wrap(payload)) throw ProbeError(R.string.echo_mismatch)
	return token
}

internal suspend fun dnsLookup(name: String): List<InetAddress> {
	val domain =
		try {
			IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES)
		} catch (_: IllegalArgumentException) {
			throw ProbeError(R.string.invalid_domain)
		}
	if (domain.trimEnd('.').length !in 1..253 || InetAddresses.isNumericAddress(domain)) {
		throw ProbeError(R.string.invalid_domain)
	}
	return suspendCancellableCoroutine { continuation ->
		val cancel = CancellationSignal()
		continuation.invokeOnCancellation { cancel.cancel() }
		DnsResolver.getInstance().query(
			null, // Use this application's default network, including its VPN.
			domain,
			DnsResolver.FLAG_NO_CACHE_LOOKUP,
			Runnable::run,
			cancel,
			object : DnsResolver.Callback<List<InetAddress>> {
				override fun onAnswer(
					answer: List<InetAddress>,
					rcode: Int,
				) {
					when {
						rcode != 0 -> continuation.resumeWithException(java.io.IOException("DNS RCODE $rcode"))
						answer.isEmpty() -> continuation.resumeWithException(ProbeError(R.string.no_addresses))
						else -> continuation.resume(answer)
					}
				}

				override fun onError(error: DnsResolver.DnsException) {
					continuation.resumeWithException(error)
				}
			},
		)
	}
}
